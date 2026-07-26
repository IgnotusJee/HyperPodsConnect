package moe.chenxy.headphones.transport.android

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A socket that behaves like the real one where it matters.
 *
 * Reads genuinely block, so tests exercise the same "parked in read until close
 * wakes it" path the device does. A fake that returned immediately would let a
 * transport that never unblocks its reader pass.
 */
class FakeSppSocket(
    private val connectBehaviour: ConnectBehaviour = ConnectBehaviour.Succeed,
    private val writeBehaviour: WriteBehaviour = WriteBehaviour.Succeed,
) : SppSocket {

    sealed interface ConnectBehaviour {
        data object Succeed : ConnectBehaviour
        data class Throw(val message: String) : ConnectBehaviour
        /** Blocks until the socket is closed, standing in for an unreachable peer. */
        data object BlockForever : ConnectBehaviour
    }

    sealed interface WriteBehaviour {
        data object Succeed : WriteBehaviour
        data class Throw(val message: String) : WriteBehaviour
        data object BlockForever : WriteBehaviour
    }

    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val unblock = CountDownLatch(1)
    private val connectStarted = CountDownLatch(1)
    private val writeStarted = CountDownLatch(1)

    val writes = mutableListOf<ByteArray>()
    val closeCount = AtomicInteger(0)
    val activeConnects = AtomicInteger(0)
    val activeWrites = AtomicInteger(0)

    @Volatile
    var connected: Boolean = false
        private set

    /** Queues bytes the transport will observe on its next read. */
    fun deliver(bytes: ByteArray) {
        inbound.put(bytes)
    }

    /** Queues an end-of-stream, as a peer closing cleanly would. */
    fun deliverEndOfStream() {
        inbound.put(ByteArray(0))
    }

    override fun connect() {
        when (connectBehaviour) {
            ConnectBehaviour.Succeed -> connected = true
            is ConnectBehaviour.Throw -> throw IOException(connectBehaviour.message)
            ConnectBehaviour.BlockForever -> {
                // Mirrors a real blocking connect: only close() gets us out.
                connectStarted.countDown()
                activeConnects.incrementAndGet()
                try {
                    unblock.await()
                    throw IOException("socket closed while connecting")
                } finally {
                    activeConnects.decrementAndGet()
                }
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (closed.get()) throw IOException("socket closed")
            val next = inbound.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (next.isEmpty()) return -1
            next.copyInto(buffer)
            return next.size
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed.get()) throw IOException("socket closed")
        when (writeBehaviour) {
            WriteBehaviour.Succeed -> synchronized(writes) { writes += bytes }
            is WriteBehaviour.Throw -> throw IOException(writeBehaviour.message)
            WriteBehaviour.BlockForever -> {
                writeStarted.countDown()
                activeWrites.incrementAndGet()
                try {
                    unblock.await()
                    if (closed.get()) throw IOException("socket closed while writing")
                } finally {
                    activeWrites.decrementAndGet()
                }
            }
        }
    }

    override fun close() {
        closeCount.incrementAndGet()
        closed.set(true)
        connected = false
        unblock.countDown()
    }

    fun writtenBytes(): List<ByteArray> = synchronized(writes) { writes.toList() }

    fun awaitConnectStarted(timeoutMillis: Long = 2_000): Boolean =
        connectStarted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitWriteStarted(timeoutMillis: Long = 2_000): Boolean =
        writeStarted.await(timeoutMillis, TimeUnit.MILLISECONDS)
}
