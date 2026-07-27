package moe.chenxy.headphones.transport.android

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.GattChunkPolicy
import moe.chenxy.headphones.core.transport.GattMtuFailurePolicy
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattTransportTest {
    @Test
    fun `open serialises connect mtu discovery and CCCD subscription`() = runBlocking {
        val fake = FakeGattClient()
        val transport = transport(fake, spec(requestMtu = 100))

        transport.open()

        assertEquals(
            listOf("connect", "mtu", "discover", "setNotification", "descriptor"),
            fake.operations,
        )
        assertEquals(1, fake.maxOutstanding.get())
        assertEquals(97, transport.maxWriteSize.value)
        assertTrue(transport.state.value is TransportState.Open)
        transport.close()
    }

    @Test
    fun `MTU status failure continues with default payload when profile permits`() = runBlocking {
        val fake = FakeGattClient().apply { mtuStatus = 133 }
        val transport = transport(
            fake,
            spec(
                requestMtu = 200,
                mtuFailurePolicy = GattMtuFailurePolicy.CONTINUE_WITH_DEFAULT,
            ),
        )

        transport.open()

        assertTrue(transport.state.value is TransportState.Open)
        assertEquals(20, transport.maxWriteSize.value)
        assertTrue("service discovery must still run", "discover" in fake.operations)
        transport.close()
    }

    @Test
    fun `MTU status failure closes connection when profile requires it`() = runBlocking {
        val fake = FakeGattClient().apply { mtuStatus = 133 }
        val transport = transport(
            fake,
            spec(
                requestMtu = 200,
                mtuFailurePolicy = GattMtuFailurePolicy.FAIL_CONNECTION,
            ),
        )

        transport.open()

        val state = transport.state.value as TransportState.Failed
        assertEquals(133, state.failure.vendorStatus)
        assertFalse("discovery must not run", "discover" in fake.operations)
        assertTrue(fake.closeCount.get() >= 1)
    }

    @Test
    fun `writes split by negotiated MTU and vendor writable length`() = runBlocking {
        val fake = FakeGattClient().apply { negotiatedMtu = 100 }
        val transport = transport(
            fake,
            spec(requestMtu = 100, writableLength = 30),
        )
        transport.open()

        val result = transport.write(ByteArray(65) { it.toByte() })

        assertEquals(TransportWriteResult.Written, result)
        assertEquals(listOf(30, 30, 5), fake.writes.map(ByteArray::size))
        assertArrayEquals(ByteArray(30) { it.toByte() }, fake.writes[0])
        assertEquals(1, fake.maxOutstanding.get())
        transport.close()
    }

    @Test
    fun `oversized write can be rejected by profile chunk policy`() = runBlocking {
        val fake = FakeGattClient()
        val transport = transport(
            fake,
            spec(writableLength = 12, chunkPolicy = GattChunkPolicy.REJECT_OVERSIZED),
        )
        transport.open()

        val result = transport.write(ByteArray(13))

        assertTrue(result is TransportWriteResult.Rejected)
        assertTrue((result as TransportWriteResult.Rejected).failure.detail!!.contains("exceeds"))
        assertTrue(fake.writes.isEmpty())
        transport.close()
    }

    @Test
    fun `write without response is chunked and locally throttled without callbacks`() = runBlocking {
        val fake = FakeGattClient()
        val transport = transport(
            fake,
            spec(
                writableLength = 10,
                writeMode = GattWriteMode.WITHOUT_RESPONSE,
                withoutResponseThrottleMillis = 25,
            ),
        )
        transport.open()

        val elapsed = kotlin.system.measureTimeMillis {
            assertEquals(TransportWriteResult.Written, transport.write(ByteArray(25)))
        }

        assertEquals(listOf(10, 10, 5), fake.writes.map(ByteArray::size))
        assertTrue("three chunks should be throttled, elapsed=$elapsed", elapsed >= 60)
        transport.close()
    }

    @Test
    fun `notifications preserve arbitrary fragmentation and order`() = runBlocking {
        val fake = FakeGattClient()
        val transport = transport(fake)
        transport.open()
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.take(3).toList()
        }

        fake.notify(byteArrayOf(1))
        fake.notify(byteArrayOf(2, 3))
        fake.notify(byteArrayOf(4, 5, 6))

        val values = withTimeout(2_000) { received.await() }
        assertEquals(3, values.size)
        assertArrayEquals(byteArrayOf(1), values[0])
        assertArrayEquals(byteArrayOf(2, 3), values[1])
        assertArrayEquals(byteArrayOf(4, 5, 6), values[2])
        transport.close()
    }

    @Test
    fun `characteristic read returns and publishes immutable bytes`() = runBlocking {
        val fake = FakeGattClient().apply { readValue = byteArrayOf(7, 8, 9) }
        val transport = transport(fake)
        transport.open()
        val incoming = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.take(1).toList().single()
        }

        val result = transport.read() as GattReadResult.Read

        fake.readValue[0] = 99
        assertArrayEquals(byteArrayOf(7, 8, 9), result.value)
        assertArrayEquals(byteArrayOf(7, 8, 9), withTimeout(2_000) { incoming.await() })
        transport.close()
    }

    @Test
    fun `write timeout fails transport and leaves no continuation`() = runBlocking {
        val fake = FakeGattClient().apply { blockedOperation = "write" }
        val transport = transport(fake, operationTimeoutMillis = 100)
        transport.open()

        val result = withTimeout(2_000) { transport.write(byteArrayOf(1)) }

        assertTrue(result is TransportWriteResult.Rejected)
        assertTrue(transport.state.value is TransportState.Failed)
        assertTrue(transport.isOperationQueueClosedForTest())
        assertTrue(fake.closeCount.get() >= 1)
    }

    @Test
    fun `disconnect during operation fails waiter and leaves no continuation`() = runBlocking {
        val fake = FakeGattClient().apply { blockedOperation = "write" }
        val transport = transport(fake, operationTimeoutMillis = 2_000)
        transport.open()
        val writing = async { transport.write(byteArrayOf(1)) }
        awaitOutstanding(fake, "write")

        fake.emitDisconnect(status = 8)

        val result = withTimeout(2_000) { writing.await() }
        assertTrue(result is TransportWriteResult.Rejected)
        val state = transport.state.value as TransportState.Failed
        assertEquals(DisconnectCause.LINK_LOST, state.failure.cause)
        assertTrue(transport.isOperationQueueClosedForTest())
    }

    @Test
    fun `requested close during operation stays closed and releases waiter`() = runBlocking {
        val fake = FakeGattClient().apply { blockedOperation = "write" }
        val transport = transport(fake, operationTimeoutMillis = 2_000)
        transport.open()
        val writing = async { transport.write(byteArrayOf(1)) }
        awaitOutstanding(fake, "write")

        transport.close()

        assertTrue(withTimeout(2_000) { writing.await() } is TransportWriteResult.Rejected)
        assertEquals(TransportState.Closed, transport.state.value)
        assertTrue(transport.isOperationQueueClosedForTest())
    }

    @Test
    fun `adapter off and bond removal are normalised as non retryable failures`() = runBlocking {
        listOf(DisconnectCause.ADAPTER_OFF, DisconnectCause.BOND_REMOVED).forEach { cause ->
            val fake = FakeGattClient()
            val transport = transport(fake)
            transport.open()

            fake.emitDisconnect(cause = cause)

            val state = transport.state.value as TransportState.Failed
            assertEquals(cause, state.failure.cause)
            assertFalse(cause.isRetryable)
        }
    }

    @Test
    fun `callback from old generation cannot complete current operation`() = runBlocking {
        val first = FakeGattClient()
        val second = FakeGattClient().apply { blockedOperation = "write" }
        val clients = ArrayDeque(listOf(first, second))
        val transport = GattTransport(
            spec(),
            GattClientFactory { clients.removeFirst() },
            operationTimeoutMillis = 2_000,
        )
        transport.open()
        val firstGeneration = first.generationId
        transport.close()
        transport.open()
        val writing = async { transport.write(byteArrayOf(1)) }
        awaitOutstanding(second, "write")

        first.emitWrite(generation = firstGeneration)
        delay(50)
        assertFalse("old callback completed new write", writing.isCompleted)

        second.emitWrite()
        assertEquals(TransportWriteResult.Written, withTimeout(2_000) { writing.await() })
        transport.close()
    }

    @Test
    fun `GATT status error is returned with vendor status without corrupting link`() = runBlocking {
        val fake = FakeGattClient().apply { writeStatus = 19 }
        val transport = transport(fake)
        transport.open()

        val result = transport.write(byteArrayOf(1)) as TransportWriteResult.Rejected

        assertEquals(19, result.failure.vendorStatus)
        assertTrue(transport.state.value is TransportState.Open)
        transport.close()
    }

    @Test
    fun `permission failure is normalised`() = runBlocking {
        val fake = FakeGattClient().apply { permissionDeniedOperation = "write" }
        val transport = transport(fake)
        transport.open()

        val result = transport.write(byteArrayOf(1)) as TransportWriteResult.Rejected

        assertEquals(DisconnectCause.PERMISSION_DENIED, result.failure.cause)
        transport.close()
    }

    @Test
    fun `missing required attribute fails before CCCD write`() = runBlocking {
        val fake = FakeGattClient().apply { rxPresent = false }
        val transport = transport(fake)

        transport.open()

        val state = transport.state.value as TransportState.Failed
        assertTrue(state.failure.detail!!.contains(FakeGattClient.RX_UUID))
        assertFalse("descriptor write must not run", "descriptor" in fake.operations)
    }

    @Test
    fun `concurrent writes never create concurrent platform operations`() = runBlocking {
        val fake = FakeGattClient()
        val transport = transport(fake)
        transport.open()

        (0 until 20)
            .map { index -> launch { transport.write(ByteArray(40) { index.toByte() }) } }
            .forEach { it.join() }

        assertEquals(1, fake.maxOutstanding.get())
        assertEquals(40, fake.writes.size)
        transport.close()
    }

    private fun transport(
        fake: FakeGattClient,
        spec: TransportSpec.Gatt = spec(),
        operationTimeoutMillis: Long = 1_000,
    ) = GattTransport(
        spec,
        GattClientFactory { fake },
        operationTimeoutMillis = operationTimeoutMillis,
    )

    private fun spec(
        requestMtu: Int? = null,
        writableLength: Int? = null,
        writeMode: GattWriteMode = GattWriteMode.WITH_RESPONSE,
        mtuFailurePolicy: GattMtuFailurePolicy = GattMtuFailurePolicy.CONTINUE_WITH_DEFAULT,
        chunkPolicy: GattChunkPolicy = GattChunkPolicy.SPLIT,
        withoutResponseThrottleMillis: Long = 0,
    ) = TransportSpec.Gatt(
        serviceUuid = FakeGattClient.SERVICE_UUID,
        txCharacteristicUuid = FakeGattClient.TX_UUID,
        rxCharacteristicUuid = FakeGattClient.RX_UUID,
        cccdUuid = FakeGattClient.CCCD_UUID,
        requestMtu = requestMtu,
        writableLength = writableLength,
        writeMode = writeMode,
        mtuFailurePolicy = mtuFailurePolicy,
        chunkPolicy = chunkPolicy,
        withoutResponseThrottleMillis = withoutResponseThrottleMillis,
    )

    private suspend fun awaitOutstanding(fake: FakeGattClient, operation: String) {
        withTimeout(1_000) {
            while (operation !in fake.outstanding) delay(5)
        }
    }
}
