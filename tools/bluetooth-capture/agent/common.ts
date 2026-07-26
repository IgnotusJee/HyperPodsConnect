/**
 * Shared event plumbing for the capture agent.
 *
 * Every hook in this agent is observe-only: it calls the original method,
 * returns its value unchanged, lets exceptions propagate, and runs on the
 * caller's thread. Nothing here may alter app behaviour.
 */

export type Direction = "tx" | "rx";

export interface AgentEvent {
    seq: number;
    /** Milliseconds since the Unix epoch, from the app's own clock. */
    wallMs: number;
    /** Monotonic microseconds, for ordering events that share a millisecond. */
    monotonicUs: number;
    kind: string;
    pid: number;
    tid: number;
    [key: string]: unknown;
}

let sequence = 0;
let startMonotonic = 0;

export function initClock(): void {
    startMonotonic = Date.now() * 1000;
}

function nowMonotonicUs(): number {
    // Frida's JS runtime has no high-resolution monotonic clock exposed, so
    // derive a monotonic-ish counter from the sequence to break ties within a
    // millisecond. Absolute correlation always uses wallMs plus the HCI window.
    return startMonotonic + sequence;
}

/**
 * Publishes one event. When `payload` is present it travels over Frida's
 * binary side-channel instead of being hex-encoded into JSON, which keeps large
 * RFCOMM/GATT buffers cheap and byte-exact.
 */
export function emit(kind: string, fields: Record<string, unknown>, payload?: ArrayBuffer): void {
    const event: AgentEvent = {
        seq: sequence++,
        wallMs: Date.now(),
        monotonicUs: nowMonotonicUs(),
        kind,
        pid: Process.id,
        tid: Process.getCurrentThreadId(),
        ...fields,
    };
    if (payload !== undefined) {
        event.payloadBytes = payload.byteLength;
        send(event, payload);
    } else {
        send(event);
    }
}

export function emitError(where: string, error: unknown): void {
    emit("agent.error", {
        where,
        message: String(error),
    });
}

/** Stable per-object identity so events can be grouped into logical sessions. */
export class HandleRegistry {
    private readonly ids = new Map<string, number>();
    private next = 1;

    constructor(private readonly prefix: string) {}

    idFor(obj: any): string {
        let key: string;
        try {
            key = obj.hashCode().toString();
        } catch (_error) {
            key = String(obj);
        }
        let id = this.ids.get(key);
        if (id === undefined) {
            id = this.next++;
            this.ids.set(key, id);
        }
        return `${this.prefix}-${id}`;
    }
}

/**
 * Copies a Java byte[] slice into an ArrayBuffer without mutating it.
 *
 * Java bytes are signed; masking to 0xFF keeps the wire representation exact.
 */
export function javaBytesToArrayBuffer(bytes: any, offset: number, length: number): ArrayBuffer {
    const out = new Uint8Array(length);
    for (let i = 0; i < length; i++) {
        out[i] = bytes[offset + i] & 0xff;
    }
    return out.buffer;
}

/** Truncated stack hash, enough to cluster call sites without storing frames. */
export function stackHash(throwableClass: any): string {
    try {
        const frames = throwableClass.$new().getStackTrace();
        let hash = 0;
        const limit = Math.min(frames.length, 24);
        for (let i = 0; i < limit; i++) {
            const frame = frames[i].toString();
            for (let c = 0; c < frame.length; c++) {
                hash = ((hash << 5) - hash + frame.charCodeAt(c)) | 0;
            }
        }
        return (hash >>> 0).toString(16);
    } catch (_error) {
        return "unknown";
    }
}
