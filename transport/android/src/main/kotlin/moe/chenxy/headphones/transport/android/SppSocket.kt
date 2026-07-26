package moe.chenxy.headphones.transport.android

import java.io.IOException

/**
 * The bit of a Bluetooth SPP socket the transport actually needs.
 *
 * `BluetoothSocket` is a final Android class, so depending on it directly would
 * put every connect, read, write and close decision behind an emulator or
 * Robolectric. Narrowing to this interface keeps the transport's logic testable
 * as plain Kotlin, and leaves [AndroidSppSocket] as the only place that has to
 * be verified on a device.
 */
interface SppSocket {

    /** Blocks until connected or throws. Callers apply their own timeout. */
    @Throws(IOException::class)
    fun connect()

    /**
     * Blocks for at least one byte. Returns the count, or -1 at end of stream.
     *
     * A return value says nothing about frame boundaries: one call can deliver
     * part of a frame or several frames at once.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /**
     * Closes the socket. Must be safe to call more than once and from a thread
     * other than the reader's, since unblocking a stuck read is exactly what it
     * is for.
     */
    fun close()
}

/** Creates a socket per connection attempt; a closed socket cannot be reopened. */
fun interface SppSocketFactory {
    @Throws(IOException::class)
    fun create(): SppSocket
}
