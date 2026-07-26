/**
 * Vendor-neutral BLE GATT tracing.
 *
 * Two things make this harder than the SPP side.
 *
 * Writes span two API generations: the pre-33 form mutates the characteristic
 * with setValue() and then calls writeCharacteristic(characteristic), while the
 * API 33+ form passes the payload directly. Both are hooked, and the pre-33
 * path reads the value back off the characteristic at write time so the payload
 * is captured regardless of when setValue happened.
 *
 * Receives never reach the base class: apps subclass BluetoothGattCallback and
 * override the methods without calling super, so hooking BluetoothGattCallback
 * itself observes nothing. The concrete subclass is discovered from the
 * connectGatt() argument and hooked on first sight.
 */

import Java from "frida-java-bridge";
import { emit, emitError, HandleRegistry, javaBytesToArrayBuffer } from "./common";

const gatts = new HandleRegistry("gatt");
const hookedCallbackClasses = new Set<string>();

function uuidOf(obj: any): string {
    try {
        return String(obj.getUuid());
    } catch (_error) {
        return "unknown";
    }
}

function characteristicInfo(characteristic: any): Record<string, unknown> {
    const info: Record<string, unknown> = { characteristic: uuidOf(characteristic) };
    try {
        const service = characteristic.getService();
        if (service !== null) info.service = uuidOf(service);
    } catch (_error) {
        /* ignore */
    }
    return info;
}

function valueOf(holder: any): ArrayBuffer | undefined {
    try {
        const value = holder.getValue();
        if (value === null) return undefined;
        return javaBytesToArrayBuffer(value, 0, value.length);
    } catch (_error) {
        return undefined;
    }
}

function hookCallbackClass(className: string): void {
    if (hookedCallbackClasses.has(className)) return;
    hookedCallbackClasses.add(className);

    let clazz: any;
    try {
        clazz = Java.use(className);
    } catch (error) {
        emitError(`resolve callback ${className}`, error);
        return;
    }

    const installed: string[] = [];

    const hookValueCallback = (name: string, kind: string) => {
        const overloads = clazz[name]?.overloads ?? [];
        for (const overload of overloads) {
            const types = overload.argumentTypes.map((t: any) => t.className);
            // Post-33 delivers the payload as an explicit byte[] argument;
            // pre-33 requires reading it back off the characteristic.
            const valueArgIndex = types.indexOf("[B");
            overload.implementation = function (...args: any[]) {
                try {
                    const characteristic = args[1];
                    const payload =
                        valueArgIndex >= 0
                            ? javaBytesToArrayBuffer(args[valueArgIndex], 0, args[valueArgIndex].length)
                            : valueOf(characteristic);
                    const status = types[types.length - 1] === "int" ? args[args.length - 1] : undefined;
                    emit(
                        kind,
                        {
                            direction: "rx",
                            gattId: gatts.idFor(args[0]),
                            ...characteristicInfo(characteristic),
                            status,
                        },
                        payload,
                    );
                } catch (error) {
                    emitError(`${className}.${name}`, error);
                }
                return overload.call(this, ...args);
            };
            installed.push(`${name}(${types.join(",")})`);
        }
    };

    hookValueCallback("onCharacteristicChanged", "gatt.notify");
    hookValueCallback("onCharacteristicRead", "gatt.read");

    for (const [name, kind] of [
        ["onDescriptorWrite", "gatt.descriptor.write"],
        ["onServicesDiscovered", "gatt.services"],
        ["onMtuChanged", "gatt.mtu"],
        ["onConnectionStateChange", "gatt.state"],
    ] as Array<[string, string]>) {
        const overloads = clazz[name]?.overloads ?? [];
        for (const overload of overloads) {
            const types = overload.argumentTypes.map((t: any) => t.className);
            overload.implementation = function (...args: any[]) {
                try {
                    const fields: Record<string, unknown> = { gattId: gatts.idFor(args[0]) };
                    if (name === "onMtuChanged") {
                        fields.mtu = args[1];
                        fields.status = args[2];
                    } else if (name === "onConnectionStateChange") {
                        fields.status = args[1];
                        fields.newState = args[2];
                    } else if (name === "onServicesDiscovered") {
                        fields.status = args[1];
                    } else if (name === "onDescriptorWrite") {
                        fields.descriptor = uuidOf(args[1]);
                        fields.status = args[2];
                    }
                    emit(kind, fields);
                } catch (error) {
                    emitError(`${className}.${name}`, error);
                }
                return overload.call(this, ...args);
            };
            installed.push(`${name}(${types.join(",")})`);
        }
    }

    emit("gatt.callback.hooked", { className, methods: installed });
}

export function installGattHooks(): void {
    const BluetoothDevice = Java.use("android.bluetooth.BluetoothDevice");
    const BluetoothGatt = Java.use("android.bluetooth.BluetoothGatt");

    // Discover the app's concrete callback subclass from every connectGatt form.
    for (const overload of BluetoothDevice.connectGatt.overloads) {
        const types = overload.argumentTypes.map((t: any) => t.className);
        const callbackIndex = types.indexOf("android.bluetooth.BluetoothGattCallback");
        overload.implementation = function (...args: any[]) {
            if (callbackIndex >= 0 && args[callbackIndex] !== null) {
                try {
                    hookCallbackClass(args[callbackIndex].$className);
                } catch (error) {
                    emitError("connectGatt discover", error);
                }
            }
            const gatt = overload.call(this, ...args);
            try {
                emit("gatt.connect", {
                    gattId: gatt !== null ? gatts.idFor(gatt) : null,
                    transport: types.includes("int") ? args[types.indexOf("int")] : undefined,
                });
            } catch (error) {
                emitError("connectGatt emit", error);
            }
            return gatt;
        };
    }

    for (const overload of BluetoothGatt.writeCharacteristic.overloads) {
        const types = overload.argumentTypes.map((t: any) => t.className);
        const valueArgIndex = types.indexOf("[B");
        overload.implementation = function (...args: any[]) {
            let payload: ArrayBuffer | undefined;
            let writeType: unknown;
            try {
                payload =
                    valueArgIndex >= 0
                        ? javaBytesToArrayBuffer(args[valueArgIndex], 0, args[valueArgIndex].length)
                        : valueOf(args[0]);
                writeType = valueArgIndex >= 0 ? args[args.length - 1] : args[0].getWriteType();
            } catch (error) {
                emitError("writeCharacteristic snapshot", error);
            }
            const result = overload.call(this, ...args);
            try {
                emit(
                    "gatt.write",
                    {
                        direction: "tx",
                        gattId: gatts.idFor(this),
                        ...characteristicInfo(args[0]),
                        writeType,
                        result,
                    },
                    payload,
                );
            } catch (error) {
                emitError("writeCharacteristic emit", error);
            }
            return result;
        };
    }

    for (const overload of BluetoothGatt.writeDescriptor.overloads) {
        const types = overload.argumentTypes.map((t: any) => t.className);
        const valueArgIndex = types.indexOf("[B");
        overload.implementation = function (...args: any[]) {
            let payload: ArrayBuffer | undefined;
            try {
                payload =
                    valueArgIndex >= 0
                        ? javaBytesToArrayBuffer(args[valueArgIndex], 0, args[valueArgIndex].length)
                        : valueOf(args[0]);
            } catch (error) {
                emitError("writeDescriptor snapshot", error);
            }
            const result = overload.call(this, ...args);
            emit(
                "gatt.descriptor.tx",
                { direction: "tx", gattId: gatts.idFor(this), descriptor: uuidOf(args[0]), result },
                payload,
            );
            return result;
        };
    }

    BluetoothGatt.requestMtu.implementation = function (mtu: number) {
        const result = this.requestMtu(mtu);
        emit("gatt.mtu.request", { gattId: gatts.idFor(this), mtu, result });
        return result;
    };

    BluetoothGatt.discoverServices.implementation = function () {
        const result = this.discoverServices();
        emit("gatt.discover.request", { gattId: gatts.idFor(this), result });
        return result;
    };

    // setValue() is the pre-33 payload staging step. Tracing it makes the
    // write path reconstructible even if the app mutates and writes far apart.
    const Characteristic = Java.use("android.bluetooth.BluetoothGattCharacteristic");
    const setValueBytes = Characteristic.setValue.overloads.find(
        (o: any) => o.argumentTypes.length === 1 && o.argumentTypes[0].className === "[B",
    );
    if (setValueBytes !== undefined) {
        setValueBytes.implementation = function (value: any) {
            const result = setValueBytes.call(this, value);
            try {
                if (value !== null) {
                    emit(
                        "gatt.setvalue",
                        { ...characteristicInfo(this) },
                        javaBytesToArrayBuffer(value, 0, value.length),
                    );
                }
            } catch (error) {
                emitError("setValue emit", error);
            }
            return result;
        };
    }

    emit("gatt.hooks.ready", {});
}
