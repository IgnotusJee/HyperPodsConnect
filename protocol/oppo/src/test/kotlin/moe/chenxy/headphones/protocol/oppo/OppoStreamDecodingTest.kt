package moe.chenxy.headphones.protocol.oppo

import moe.chenxy.headphones.protocol.oppo.frame.OppoFrameStreamDecoder
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 2 acceptance criteria, exercised against real captures.
 *
 * Fragmentation is not hypothetical here: a captured Air5s response arrives as a
 * 3-byte read followed by a 15-byte read, which the original one-read-per-packet
 * reader lost entirely.
 */
class OppoStreamDecodingTest {

    private val coldInit = OppoFixtures.deviceCaptureFrames("encoair5s-cold-init.hex")
    private val stream = coldInit.reduce { acc, frame -> acc + frame }

    private fun decodeWholeStream(chunkSizes: List<Int>): List<ByteArray> {
        val decoder = OppoFrameStreamDecoder()
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        for (size in chunkSizes) {
            if (offset >= stream.size) break
            val end = minOf(offset + size, stream.size)
            frames += decoder.feed(stream.copyOfRange(offset, end))
            offset = end
        }
        if (offset < stream.size) frames += decoder.feed(stream.copyOfRange(offset, stream.size))
        return frames
    }

    @Test
    fun `every chunking of a real capture yields identical frames`() {
        // Includes the 3-then-rest split observed on the wire.
        val chunkings = listOf(
            List(stream.size) { 1 },
            listOf(3, stream.size - 3),
            listOf(7, 5, 23, 1, 64),
            listOf(stream.size),
            listOf(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
        )

        chunkings.forEach { chunking ->
            val frames = decodeWholeStream(chunking)
            assertEquals("chunking $chunking", coldInit.size, frames.size)
            coldInit.forEachIndexed { index, expected -> assertArrayEquals(expected, frames[index]) }
        }
    }

    @Test
    fun `many frames delivered in one chunk are split individually`() {
        val frames = OppoFrameStreamDecoder().feed(stream)

        assertEquals(coldInit.size, frames.size)
        assertTrue(frames.size > 5)
    }

    @Test
    fun `a split frame is unusable alone but recovered by the decoder`() {
        val response = coldInit.first {
            OppoMessageCodec.decode(it)?.command == OppoCommand.responseOf(OppoCommand.QUERY_NOTIFICATION_SUPPORT)
        }
        val head = response.copyOfRange(0, 3)
        val tail = response.copyOfRange(3, response.size)

        // Either half on its own is not a frame.
        assertNull(OppoMessageCodec.decode(tail))

        val decoder = OppoFrameStreamDecoder()
        assertEquals(emptyList<ByteArray>(), decoder.feed(head))
        assertArrayEquals(response, decoder.feed(tail).single())
    }

    @Test
    fun `garbage before a frame is skipped and the frame still decodes`() {
        val battery = OppoMessageCodec.encode(OppoCommand.QUERY_BATTERY)
        val noise = OppoFixtures.hex("01 02 AA 00 55 FF")

        val frames = OppoFrameStreamDecoder().feed(noise + battery)

        assertArrayEquals(battery, frames.single())
    }

    @Test
    fun `an oversized declared length cannot stall the stream`() {
        val decoder = OppoFrameStreamDecoder(maxEncodedLength = 128)
        val bogus = OppoFixtures.hex("AA 81 01")   // declares 129
        val real = OppoMessageCodec.encode(OppoCommand.QUERY_EQ)

        assertEquals(emptyList<ByteArray>(), decoder.feed(bogus))
        assertArrayEquals(real, decoder.feed(real).single())
    }

    @Test
    fun `reset drops a partial frame from a previous connection generation`() {
        val decoder = OppoFrameStreamDecoder()
        decoder.feed(coldInit.first().copyOfRange(0, 4))
        assertEquals(4, decoder.pendingByteCount())

        decoder.reset()
        val next = OppoMessageCodec.encode(OppoCommand.QUERY_BATTERY)

        assertArrayEquals(next, decoder.feed(next).single())
        assertEquals(0, decoder.pendingByteCount())
    }

    @Test
    fun `an unknown command decodes and is preserved rather than dropped`() {
        val unknown = OppoMessageCodec.encode(0x0999, payload = OppoFixtures.hex("DE AD BE EF"))

        val message = OppoMessageCodec.decode(OppoFrameStreamDecoder().feed(unknown).single())

        assertNotNull(message)
        assertEquals(0x0999, message!!.command)
        assertArrayEquals(OppoFixtures.hex("DE AD BE EF"), message.payload)
        assertTrue(message.isComplete)
    }

    @Test
    fun `a truncated frame is reported as incomplete instead of vanishing`() {
        val full = OppoMessageCodec.encode(0x0106, payload = OppoFixtures.hex("01 02 03 04"))
        // Keep the header's promise of four payload bytes but deliver two.
        val truncated = full.copyOfRange(0, full.size - 2)

        val message = OppoMessageCodec.decode(truncated)

        assertNotNull(message)
        assertEquals(4, message!!.declaredPayloadLength)
        assertEquals(2, message.payload.size)
        assertTrue(!message.isComplete)
    }

    @Test
    fun `encode and decode round trip including a multi byte varint`() {
        val payload = ByteArray(200) { (it and 0xFF).toByte() }

        val message = OppoMessageCodec.decode(OppoMessageCodec.encode(0x1234, 0x56, payload))!!

        assertEquals(0x1234, message.command)
        assertEquals(0x56, message.sequence)
        assertArrayEquals(payload, message.payload)
        assertTrue(message.isComplete)
    }
}
