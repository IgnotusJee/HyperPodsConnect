/**
 * Capture agent entry point.
 *
 * Frida 17 ships a bare GumJS runtime that no longer bundles the Java bridge,
 * so it is imported explicitly here and the whole thing is built with
 * frida-compile into a single file. A REPL-style Java.perform script handed
 * straight to Python's create_script() will not run under this runtime.
 */

import Java from "frida-java-bridge";
import { emit, emitError, initClock } from "./common";
import { installRfcommHooks, sendReadProbe } from "./generic-rfcomm";
import { installGattHooks } from "./generic-gatt";

interface HealthReport {
    ready: boolean;
    pid: number;
    javaAvailable: boolean;
    androidVersion: string | null;
    packageName: string | null;
    hooks: Record<string, boolean>;
    errors: string[];
}

const health: HealthReport = {
    ready: false,
    pid: Process.id,
    javaAvailable: false,
    androidVersion: null,
    packageName: null,
    hooks: { rfcomm: false, gatt: false },
    errors: [],
};

function describeRuntime(): void {
    try {
        const Build = Java.use("android.os.Build$VERSION");
        health.androidVersion = `${Build.RELEASE.value} (API ${Build.SDK_INT.value})`;
    } catch (error) {
        health.errors.push(`Build.VERSION: ${error}`);
    }
    try {
        const ActivityThread = Java.use("android.app.ActivityThread");
        const application = ActivityThread.currentApplication();
        if (application !== null) {
            health.packageName = String(application.getApplicationContext().getPackageName());
        }
    } catch (error) {
        health.errors.push(`packageName: ${error}`);
    }
}

function install(): void {
    initClock();
    health.javaAvailable = Java.available;

    if (!Java.available) {
        health.errors.push("Java runtime unavailable; refusing to install hooks");
        emit("agent.health", health as unknown as Record<string, unknown>);
        return;
    }

    Java.perform(() => {
        describeRuntime();

        try {
            installRfcommHooks();
            health.hooks.rfcomm = true;
        } catch (error) {
            health.errors.push(`rfcomm: ${error}`);
            emitError("installRfcommHooks", error);
        }

        try {
            installGattHooks();
            health.hooks.gatt = true;
        } catch (error) {
            health.errors.push(`gatt: ${error}`);
            emitError("installGattHooks", error);
        }

        health.ready = health.hooks.rfcomm || health.hooks.gatt;
        emit("agent.health", health as unknown as Record<string, unknown>);
    });
}

rpc.exports = {
    /** Collector calls this after load to confirm the agent is alive and armed. */
    health(): HealthReport {
        return health;
    },
    /** Explicit marker so host-side step boundaries land in the same stream. */
    mark(label: string, phase: string): void {
        emit("host.mark", { label, phase });
    },
    /**
     * Proves the payload path without touching the radio.
     *
     * Builds a throwaway BluetoothGattCharacteristic and calls setValue() on
     * it. That method only stores bytes in a field of an object this agent just
     * created, so nothing reaches the Bluetooth stack and the host app's own
     * state is untouched, but the capture chain (hook, binary channel, writer)
     * runs exactly as it would for real traffic.
     *
     * Output is tagged selfTest so it can never be mistaken for device evidence.
     */
    /**
     * Sends one allowlisted read-only query so a command the official app never
     * issues can still be tested against the device. Rejects anything else; this
     * is not a raw console.
     */
    probeRead(hex: string): { sent: boolean; reason?: string } {
        return sendReadProbe(hex);
    },
    selfTest(byteValues: number[]): boolean {
        let ok = false;
        Java.perform(() => {
            try {
                const Characteristic = Java.use("android.bluetooth.BluetoothGattCharacteristic");
                const UUID = Java.use("java.util.UUID");
                const characteristic = Characteristic.$new(
                    UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
                    0x08 /* PROPERTY_WRITE */,
                    0x10 /* PERMISSION_WRITE */,
                );
                emit("selftest.begin", { byteCount: byteValues.length });
                characteristic.setValue(byteValues);
                ok = true;
            } catch (error) {
                emitError("selfTest", error);
            }
        });
        return ok;
    },
};

install();
