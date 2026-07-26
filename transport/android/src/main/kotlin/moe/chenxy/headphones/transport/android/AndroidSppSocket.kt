package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * The only Android-aware part of this module.
 *
 * Streams are fetched once and cached: `getInputStream()` on a closed socket
 * throws, and the reader must be able to unblock through [close] rather than
 * discovering the failure while asking for a stream.
 */
class AndroidSppSocket(
    private val device: BluetoothDevice,
    private val serviceUuid: UUID,
    private val secure: Boolean = true,
) : SppSocket {

    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    override fun connect() {
        val created = if (secure) {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
        }
        synchronized(lock) {
            socket = created
        }
        try {
            created.connect()
            val connectedInput = created.inputStream
            val connectedOutput = created.outputStream
            synchronized(lock) {
                if (socket !== created) {
                    created.close()
                    throw IOException("socket closed while connecting")
                }
                inputStream = connectedInput
                outputStream = connectedOutput
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (socket === created) {
                    socket = null
                    inputStream = null
                    outputStream = null
                }
            }
            try {
                created.close()
            } catch (_: IOException) {
            }
            throw error
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        val stream = synchronized(lock) {
            if (socket == null) null else inputStream
        } ?: throw IOException("socket not connected")
        return stream.read(buffer)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray) {
        val stream = synchronized(lock) {
            if (socket == null) null else outputStream
        } ?: throw IOException("socket not connected")
        stream.write(bytes)
        stream.flush()
    }

    override fun close() {
        // Deliberately swallowing: close is called on the failure path and from
        // cancellation, where an exception would mask the real cause.
        val active = synchronized(lock) {
            val current = socket
            socket = null
            inputStream = null
            outputStream = null
            current
        }
        try {
            active?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun factory(
            device: BluetoothDevice,
            serviceUuid: UUID,
            secure: Boolean = true,
        ): SppSocketFactory = SppSocketFactory { AndroidSppSocket(device, serviceUuid, secure) }
    }
}
