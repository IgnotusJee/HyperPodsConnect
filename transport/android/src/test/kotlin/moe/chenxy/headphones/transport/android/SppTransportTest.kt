package moe.chenxy.headphones.transport.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real dispatchers on purpose.
 *
 * The behaviour under test is blocking IO interacting with cancellation, and a
 * virtual-time scheduler would skip exactly the races worth checking.
 */
class SppTransportTest {

    private fun transport(
        socket: FakeSppSocket,
        connectTimeoutMillis: Long = 2_000L,
        writeTimeoutMillis: Long = 1_000L,
    ) = SppTransport(
        socketFactory = { socket },
        ioDispatcher = Dispatchers.IO,
        connectTimeoutMillis = connectTimeoutMillis,
        writeTimeoutMillis = writeTimeoutMillis,
    )

    @Test
    fun `open reaches the open state and starts reading`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)

        transport.open()

        assertTrue(transport.state.value is TransportState.Open)
        assertTrue(socket.connected)
        transport.close()
    }

    @Test
    fun `incoming delivers chunks exactly as the socket produced them`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)
        transport.open()

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.take(2).toList()
        }
        // Deliberately split mid-frame: the transport must not try to reassemble.
        socket.deliver(byteArrayOf(0xAA.toByte(), 0x07, 0x00))
        socket.deliver(byteArrayOf(0x00, 0x06, 0x01))

        val chunks = withTimeout(5_000) { received.await() }

        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x07, 0x00), chunks[0])
        assertArrayEquals(byteArrayOf(0x00, 0x06, 0x01), chunks[1])
        transport.close()
    }

    @Test
    fun `connect failure surfaces as a failed state and never opens`() = runBlocking {
        val socket = FakeSppSocket(FakeSppSocket.ConnectBehaviour.Throw("no route"))
        val transport = transport(socket)

        transport.open()

        val state = transport.state.value
        assertTrue(state is TransportState.Failed)
        assertEquals(DisconnectCause.TRANSPORT_ERROR, (state as TransportState.Failed).failure.cause)
        assertTrue(state.failure.detail!!.contains("no route"))
    }

    @Test
    fun `socket permission failure is classified and is not retryable`() = runBlocking {
        val transport = SppTransport(
            socketFactory = { throw SecurityException("bluetooth connect denied") },
            ioDispatcher = Dispatchers.IO,
        )

        transport.open()

        val state = transport.state.value as TransportState.Failed
        assertEquals(DisconnectCause.PERMISSION_DENIED, state.failure.cause)
        assertFalse(state.failure.cause.isRetryable)
    }

    @Test
    fun `a connect that never returns is bounded by the timeout`() = runBlocking {
        val socket = FakeSppSocket(FakeSppSocket.ConnectBehaviour.BlockForever)
        val transport = transport(socket, connectTimeoutMillis = 200L)

        val elapsed = kotlin.system.measureTimeMillis { transport.open() }

        assertTrue("open should return promptly, took ${elapsed}ms", elapsed < 4_000)
        val state = transport.state.value
        assertTrue(state is TransportState.Failed)
        assertTrue((state as TransportState.Failed).failure.detail!!.contains("timed out"))
        // Closing the socket is what unblocks the parked connect thread.
        assertTrue(socket.closeCount.get() >= 1)
        assertEquals(0, socket.activeConnects.get())
    }

    @Test
    fun `cancelling open closes and joins a blocking connect`() = runBlocking {
        val socket = FakeSppSocket(FakeSppSocket.ConnectBehaviour.BlockForever)
        val transport = transport(socket, connectTimeoutMillis = 10_000L)

        val opening = launch(Dispatchers.Default) { transport.open() }
        assertTrue("connect did not start", socket.awaitConnectStarted())

        opening.cancelAndJoin()

        assertEquals(TransportState.Closed, transport.state.value)
        assertEquals(0, socket.activeConnects.get())
        assertFalse(transport.isReaderActive())
        assertFalse(transport.isWriteActive())
    }

    @Test
    fun `end of stream is reported as a lost link rather than an error`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)
        transport.open()

        socket.deliverEndOfStream()

        val state = withTimeout(5_000) {
            transport.state.first { it is TransportState.Failed }
        } as TransportState.Failed
        assertEquals(DisconnectCause.LINK_LOST, state.failure.cause)
    }

    @Test
    fun `writes are serialised so frames cannot interleave`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)
        transport.open()

        val frames = (0 until 20).map { index -> ByteArray(16) { index.toByte() } }
        frames.map { frame -> launch { transport.write(frame) } }.forEach { it.join() }

        val written = socket.writtenBytes()
        assertEquals(20, written.size)
        // Every write arrived whole; a frame made of two different values would
        // mean two writers had interleaved.
        written.forEach { frame ->
            assertEquals(1, frame.toSet().size)
            assertEquals(16, frame.size)
        }
        transport.close()
    }

    @Test
    fun `a write on a closed transport is rejected rather than throwing`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)
        transport.open()
        transport.close()

        val result = transport.write(byteArrayOf(1, 2, 3))

        assertTrue(result is TransportWriteResult.Rejected)
        assertEquals(
            DisconnectCause.TRANSPORT_ERROR,
            (result as TransportWriteResult.Rejected).failure.cause,
        )
    }

    @Test
    fun `a stuck write is bounded and tears the transport down`() = runBlocking {
        val socket = FakeSppSocket(writeBehaviour = FakeSppSocket.WriteBehaviour.BlockForever)
        val transport = transport(socket, writeTimeoutMillis = 200L)
        transport.open()

        val result = withTimeout(5_000) { transport.write(byteArrayOf(1)) }

        assertTrue(result is TransportWriteResult.Rejected)
        assertTrue(transport.state.value is TransportState.Failed)
        assertEquals(0, socket.activeWrites.get())
        assertFalse(transport.isWriteActive())
    }

    @Test
    fun `close joins a blocking write and reader`() = runBlocking {
        val socket = FakeSppSocket(writeBehaviour = FakeSppSocket.WriteBehaviour.BlockForever)
        val transport = transport(socket, writeTimeoutMillis = 10_000L)
        transport.open()

        val writing = async(Dispatchers.Default) { runCatching { transport.write(byteArrayOf(1)) } }
        assertTrue("write did not start", socket.awaitWriteStarted())

        transport.close()
        withTimeout(5_000) { writing.await() }

        assertEquals(TransportState.Closed, transport.state.value)
        assertEquals(0, socket.activeWrites.get())
        assertFalse(transport.isWriteActive())
        assertFalse(transport.isReaderActive())
    }

    @Test
    fun `close stops the reader and is idempotent`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)
        transport.open()
        assertTrue(transport.isReaderActive())

        transport.close()
        transport.close()

        assertFalse("reader must not outlive the transport", transport.isReaderActive())
        assertEquals(TransportState.Closed, transport.state.value)
    }

    @Test
    fun `a requested close is distinguishable from a failure`() = runBlocking {
        val requested = transport(FakeSppSocket())
        requested.open()
        requested.close(DisconnectCause.REQUESTED)
        assertEquals(TransportState.Closed, requested.state.value)

        val lost = FakeSppSocket()
        val failing = transport(lost)
        failing.open()
        lost.deliverEndOfStream()
        val state = withTimeout(5_000) { failing.state.first { it is TransportState.Failed } }
        // The session layer needs this difference to decide whether to reconnect.
        assertTrue((state as TransportState.Failed).failure.cause.isRetryable)
    }

    @Test
    fun `closing a failed transport settles it in closed state`() = runBlocking {
        val transport = transport(FakeSppSocket(FakeSppSocket.ConnectBehaviour.Throw("no route")))

        transport.open()
        assertTrue(transport.state.value is TransportState.Failed)

        transport.close()

        assertEquals(TransportState.Closed, transport.state.value)
        assertFalse(transport.isReaderActive())
        assertFalse(transport.isWriteActive())
    }

    @Test
    fun `twenty connect and disconnect cycles leave nothing running`() = runBlocking {
        // Mirrors the acceptance criterion for the real device.
        repeat(20) { cycle ->
            val socket = FakeSppSocket()
            val transport = transport(socket)

            transport.open()
            assertTrue("cycle $cycle failed to open", transport.state.value is TransportState.Open)

            transport.write(byteArrayOf(cycle.toByte()))
            transport.close()

            assertEquals("cycle $cycle", TransportState.Closed, transport.state.value)
            assertFalse("cycle $cycle leaked a reader", transport.isReaderActive())
            assertTrue("cycle $cycle did not close its socket", socket.closeCount.get() >= 1)
        }
    }

    @Test
    fun `opening twice does not start a second reader`() = runBlocking {
        val socket = FakeSppSocket()
        val transport = transport(socket)

        transport.open()
        transport.open()

        assertTrue(transport.state.value is TransportState.Open)
        assertEquals(0, socket.closeCount.get())
        transport.close()
    }
}
