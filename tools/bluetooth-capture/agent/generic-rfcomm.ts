/**
 * Vendor-neutral RFCOMM/SPP tracing.
 *
 * The concrete stream classes behind BluetoothSocket.getInputStream() and
 * getOutputStream() are package-private and have been renamed across AOSP
 * versions, so they are discovered at runtime from the returned object rather
 * than hardcoded. Hooks are installed once per concrete class.
 *
 * Only the (byte[], int, int) overloads are hooked. The no-offset variants
 * delegate to them internally, so hooking both would double-count every byte.
 */

import Java from "frida-java-bridge";
import { emit, emitError, HandleRegistry, javaBytesToArrayBuffer, stackHash } from "./common";

const sockets = new HandleRegistry("spp");
const hookedStreamClasses = new Set<string>();

function describeSocket(socket: any): Record<string, unknown> {
    const info: Record<string, unknown> = { socketId: sockets.idFor(socket) };
    try {
        const device = socket.getRemoteDevice();
        if (device !== null) {
            // Addresses are hashed here; the collector maps them to aliases and
            // the raw value never reaches a repository file.
            info.peerHash = String(device.getAddress()).replace(/:/g, "").toUpperCase().slice(-4);
        }
    } catch (_error) {
        /* getRemoteDevice can throw on a closed socket; absence is fine. */
    }
    try {
        info.connected = socket.isConnected();
    } catch (_error) {
        /* ignore */
    }
    return info;
}

function hookStreamClass(className: string, socketRef: any, isInput: boolean, Throwable: any): void {
    if (hookedStreamClasses.has(className)) return;
    hookedStreamClasses.add(className);

    let clazz: any;
    try {
        clazz = Java.use(className);
    } catch (error) {
        emitError(`resolve ${className}`, error);
        return;
    }

    const methodName = isInput ? "read" : "write";
    const overloads = clazz[methodName]?.overloads ?? [];
    const target = overloads.find(
        (o: any) =>
            o.argumentTypes.length === 3 &&
            o.argumentTypes[0].className === "[B" &&
            o.argumentTypes[1].className === "int" &&
            o.argumentTypes[2].className === "int",
    );

    if (target === undefined) {
        emit("spp.hook.missing", { className, method: methodName, overloadCount: overloads.length });
        return;
    }

    // A preloaded class has no socket to attribute yet; the id resolves once the
    // socket is seen through connect() or a stream accessor.
    const socketId = socketRef !== null ? sockets.idFor(socketRef) : "spp-preloaded";

    if (isInput) {
        target.implementation = function (buffer: any, offset: number, length: number) {
            const result = target.call(this, buffer, offset, length);
            if (result > 0) {
                try {
                    emit(
                        "spp.data",
                        {
                            direction: "rx",
                            socketId,
                            offset,
                            requested: length,
                            stack: stackHash(Throwable),
                        },
                        javaBytesToArrayBuffer(buffer, offset, result),
                    );
                } catch (error) {
                    emitError("spp read emit", error);
                }
            }
            return result;
        };
    } else {
        target.implementation = function (buffer: any, offset: number, length: number) {
            // Snapshot before the call: the caller may reuse the buffer after.
            let payload: ArrayBuffer | undefined;
            try {
                payload = javaBytesToArrayBuffer(buffer, offset, length);
            } catch (error) {
                emitError("spp write snapshot", error);
            }
            const result = target.call(this, buffer, offset, length);
            if (payload !== undefined) {
                emit(
                    "spp.data",
                    { direction: "tx", socketId, offset, requested: length, stack: stackHash(Throwable) },
                    payload,
                );
            }
            return result;
        };
    }

    emit("spp.hook.installed", { className, method: methodName, socketId });
}

/**
 * Hooks stream classes that are already loaded.
 *
 * Discovery through getInputStream()/getOutputStream() only fires when the app
 * asks for a stream. An app that opened its socket before the agent attached
 * never calls them again, so attaching to a running app would install nothing
 * and the capture would look silently idle. Sweeping the loaded classes closes
 * that gap; the factory hooks still cover classes loaded later.
 */
function hookAlreadyLoadedStreamClasses(Throwable: any): void {
    let names: string[] = [];
    try {
        names = Java.enumerateLoadedClassesSync().filter(
            (name: string) => name.startsWith("android.bluetooth.") && name.includes("Stream"),
        );
    } catch (error) {
        emitError("enumerate loaded stream classes", error);
        return;
    }

    for (const name of names) {
        const isInput = name.toLowerCase().includes("input");
        const isOutput = name.toLowerCase().includes("output");
        if (!isInput && !isOutput) continue;
        hookStreamClass(name, null, isInput, Throwable);
    }
    emit("spp.preloaded.scan", { scanned: names.length, classes: names });
}

export function installRfcommHooks(): void {
    const BluetoothSocket = Java.use("android.bluetooth.BluetoothSocket");
    const Throwable = Java.use("java.lang.Throwable");

    BluetoothSocket.connect.implementation = function () {
        const info = describeSocket(this);
        emit("spp.connect.begin", info);
        try {
            this.connect();
        } catch (error) {
            emit("spp.connect.failed", { ...info, message: String(error) });
            throw error;
        }
        emit("spp.connect.ok", describeSocket(this));
    };

    BluetoothSocket.close.implementation = function () {
        emit("spp.close", { socketId: sockets.idFor(this) });
        this.close();
    };

    for (const [accessor, isInput] of [
        ["getInputStream", true],
        ["getOutputStream", false],
    ] as Array<[string, boolean]>) {
        const original = (BluetoothSocket as any)[accessor];
        original.implementation = function () {
            const stream = original.call(this);
            if (stream !== null) {
                try {
                    const className = stream.$className ?? Java.use("java.lang.Object").getClass
                        .call(stream)
                        .getName();
                    hookStreamClass(className, this, isInput, Throwable);
                } catch (error) {
                    emitError(`discover ${accessor}`, error);
                }
            }
            return stream;
        };
    }

    hookAlreadyLoadedStreamClasses(Throwable);
    emit("spp.hooks.ready", {});
}
