package org.hyperpods.connect.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OppoFrameStreamDecoderTest {
    private val batteryQuery = OppoPackets.buildPacket(Cmd.QUERY_BATTERY)
    private val eqQuery = OppoPackets.buildPacket(Cmd.QUERY_EQ_PRESET)

    @Test
    fun `arbitrary split points produce one complete frame`() {
        for (splitAt in 1 until batteryQuery.size) {
            val decoder = OppoFrameStreamDecoder()

            assertEquals(emptyList<ByteArray>(), decoder.feed(batteryQuery.copyOfRange(0, splitAt)))
            val frames = decoder.feed(batteryQuery.copyOfRange(splitAt, batteryQuery.size))

            assertEquals(1, frames.size)
            assertArrayEquals(batteryQuery, frames.single())
        }
    }

    @Test
    fun `current read chunk assumption demonstrably loses a split protocol response`() {
        val response = OppoPackets.buildPacket(
            Cmd.NOTIFICATION_SUPPORT_RESPONSE,
            payload = byteArrayOf(0x00, 0x02, 0x01, 0x03),
        )
        val firstRead = response.copyOfRange(0, 6)
        val secondRead = response.copyOfRange(6, response.size)

        // This models the current controller path: each read chunk is sent straight
        // to a business parser. Neither half can be parsed on its own.
        assertNull(NotificationSupportParser.parse(firstRead))
        assertNull(NotificationSupportParser.parse(secondRead))

        val decoder = OppoFrameStreamDecoder()
        assertEquals(emptyList<ByteArray>(), decoder.feed(firstRead))
        val recovered = decoder.feed(secondRead).single()
        assertArrayEquals(byteArrayOf(0x01, 0x03), NotificationSupportParser.parse(recovered)!!)
    }

    @Test
    fun `one byte feeds and coalesced frames preserve boundaries`() {
        val decoder = OppoFrameStreamDecoder()
        val output = mutableListOf<ByteArray>()
        (batteryQuery + eqQuery).forEach { output += decoder.feed(byteArrayOf(it)) }

        assertEquals(2, output.size)
        assertArrayEquals(batteryQuery, output[0])
        assertArrayEquals(eqQuery, output[1])
    }

    @Test
    fun `multiple frames in one feed are emitted independently`() {
        val frames = OppoFrameStreamDecoder().feed(batteryQuery + eqQuery)

        assertEquals(2, frames.size)
        assertArrayEquals(batteryQuery, frames[0])
        assertArrayEquals(eqQuery, frames[1])
    }

    @Test
    fun `garbage and invalid length resynchronize at next marker`() {
        val input = byteArrayOf(0x01, 0x02, 0xAA.toByte(), 0x00, 0x55) + batteryQuery

        val frames = OppoFrameStreamDecoder().feed(input)

        assertEquals(1, frames.size)
        assertArrayEquals(batteryQuery, frames.single())
    }

    @Test
    fun `oversized length is rejected without allocating declared payload`() {
        val decoder = OppoFrameStreamDecoder(maxEncodedLength = 128)
        val invalid = byteArrayOf(0xAA.toByte(), 0x81.toByte(), 0x01) // length 129

        assertEquals(emptyList<ByteArray>(), decoder.feed(invalid))
        val frames = decoder.feed(batteryQuery)

        assertEquals(1, frames.size)
        assertArrayEquals(batteryQuery, frames.single())
    }

    @Test
    fun `two byte varint frame can arrive in fragments`() {
        val longFrame = OppoPackets.buildPacket(0x1234, payload = ByteArray(130))
        val decoder = OppoFrameStreamDecoder()

        assertEquals(emptyList<ByteArray>(), decoder.feed(longFrame.copyOfRange(0, 2)))
        assertEquals(emptyList<ByteArray>(), decoder.feed(longFrame.copyOfRange(2, 90)))
        val frames = decoder.feed(longFrame.copyOfRange(90, longFrame.size))

        assertArrayEquals(longFrame, frames.single())
    }

    @Test
    fun `malformed three byte varint resynchronizes at the next marker`() {
        val malformed = byteArrayOf(
            0xAA.toByte(),
            0x80.toByte(),
            0x80.toByte(),
            0x80.toByte(),
        )

        val frames = OppoFrameStreamDecoder().feed(malformed + batteryQuery)

        assertEquals(1, frames.size)
        assertArrayEquals(batteryQuery, frames.single())
    }

    @Test
    fun `reset discards a partial frame from the previous connection generation`() {
        val decoder = OppoFrameStreamDecoder()
        assertEquals(emptyList<ByteArray>(), decoder.feed(batteryQuery.copyOfRange(0, 5)))
        assertEquals(5, decoder.pendingByteCount())

        decoder.reset()
        val frames = decoder.feed(eqQuery)

        assertEquals(1, frames.size)
        assertArrayEquals(eqQuery, frames.single())
    }

    @Test
    fun `configured maximum must be representable and include link control bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            OppoFrameStreamDecoder(maxEncodedLength = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OppoFrameStreamDecoder(maxEncodedLength = 1 shl 21)
        }
    }
}
