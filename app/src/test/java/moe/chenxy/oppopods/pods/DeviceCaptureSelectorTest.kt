package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settles what the official app's ANC selectors mean, and which features of this
 * project's batch status query the device actually supports.
 *
 * Both answers come from probing the device directly, because the official app
 * only ever exercises its own selectors and its own feature list.
 */
class DeviceCaptureSelectorTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-selectors.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    private fun payloadOf(frame: ByteArray) = frame.copyOfRange(9, frame.size)

    private fun responseFor(selectorHi: Int, selectorLo: Int) = frames.first {
        cmdOf(it) == Cmd.ANC_MODE_RESPONSE &&
            (it[10].toInt() and 0xFF) == selectorHi &&
            (it[11].toInt() and 0xFF) == selectorLo
    }

    @Test
    fun `ANC response echoes its selector and carries a status byte`() {
        val response = responseFor(0x02, 0x03)
        val payload = payloadOf(response)

        assertEquals(5, payload.size)
        assertEquals(0x00, payload[0].toInt())              // status
        assertEquals(0x02, payload[1].toInt())              // selector high
        assertEquals(0x03, payload[2].toInt())              // selector low
    }

    /**
     * The app queries `02 03` and `02 04` after every ANC write, which looks
     * like a readback. It is not: both answer `06 00` in deep-ANC and again in
     * transparency, so they are static values and cannot confirm a mode change.
     */
    @Test
    fun `selectors 0203 and 0204 return a constant unrelated to the current mode`() {
        assertArrayEquals(
            OppoTestFixtures.hex("00 02 03 06 00"),
            payloadOf(responseFor(0x02, 0x03)),
        )
        assertArrayEquals(
            OppoTestFixtures.hex("00 02 04 06 00"),
            payloadOf(responseFor(0x02, 0x04)),
        )
    }

    @Test
    fun `unsupported selector is reported by a non zero status`() {
        val payload = payloadOf(responseFor(0x03, 0x01))

        assertEquals(0x01, payload[0].toInt())
        assertArrayEquals(OppoTestFixtures.hex("00 00"), payload.copyOfRange(3, 5))
        // A failed query must not be mistaken for a mode reading.
        assertNull(AncModeParser.parse(responseFor(0x03, 0x01)))
    }

    @Test
    fun `this project's batch query is answered but two features differ`() {
        val query = frames.first { cmdOf(it) == 0x010D }
        assertArrayEquals(Enums.QUERY_STATUS, query)

        val response = frames.first { cmdOf(it) == 0x810D }
        val payload = payloadOf(response)
        assertEquals(0x00, payload[0].toInt())

        val requested = payloadOf(query).drop(1).map { it.toInt() and 0xFF }
        val answered = payload.drop(2).chunked(2).associate { (id, value) ->
            (id.toInt() and 0xFF) to (value.toInt() and 0xFF)
        }

        assertEquals(11, requested.size)
        assertEquals(10, answered.size)

        // Silently dropped from the reply, so unsupported on this model.
        assertEquals(setOf(0x1C), requested.toSet() - answered.keys)
        // Asked for by this project, not by the official app, and supported.
        assertTrue(answered.containsKey(0x13))
        assertEquals(0x01, answered[0x13])
    }

    /**
     * Pairs with the spatial-switch capture: the device omits 0x37 from this
     * project's query simply because we never ask for it, while the official app
     * does and gets it back.
     */
    @Test
    fun `this project never asks for a feature the device does support`() {
        val requested = payloadOf(frames.first { cmdOf(it) == 0x010D })
            .drop(1).map { it.toInt() and 0xFF }.toSet()

        assertFalse(requested.contains(0x37))
    }

    @Test
    fun `selector stream survives byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
