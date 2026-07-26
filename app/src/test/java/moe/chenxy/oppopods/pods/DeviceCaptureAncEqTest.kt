package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ANC and EQ regression against a real OPPO Enco Air5s, captured while the
 * earbuds were worn. Both the set commands and the resulting state reports were
 * observed on the HCI capture and on the official app's socket.
 */
class DeviceCaptureAncEqTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-anc-eq.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    private fun payloadOf(frame: ByteArray) = frame.copyOfRange(9, frame.size)

    private fun framesWith(command: Int) = frames.filter { cmdOf(it) == command }

    private fun ancNotifyWith(v1: Int, v2: Int) = frames.first {
        cmdOf(it) == 0x0204 &&
            it[9].toInt() == 0x03 &&
            (it[12].toInt() and 0xFF) == v1 &&
            (it[13].toInt() and 0xFF) == v2
    }

    @Test
    fun `ANC set packets this project builds match the ones the official app sends`() {
        val captured = framesWith(0x0404).associateBy { it.last().toInt() and 0xFF }

        // Sequence is device-assigned, so compare everything except that byte.
        fun assertSameExceptSeq(expected: ByteArray, actual: ByteArray) {
            assertEquals(expected.size, actual.size)
            expected.indices.filter { it != 6 }.forEach { assertEquals(expected[it], actual[it]) }
        }

        assertSameExceptSeq(Enums.ANC_OFF, captured.getValue(0x01))
        assertSameExceptSeq(Enums.ANC_NOISE_CANCEL, captured.getValue(0x02))
        assertSameExceptSeq(Enums.ANC_TRANSPARENCY, captured.getValue(0x04))
    }

    @Test
    fun `every captured set response reports success in its first payload byte`() {
        val responses = framesWith(0x8404) + framesWith(0x8406)

        assertEquals(6, responses.size)
        responses.forEach { response ->
            val payload = payloadOf(response)
            assertEquals(1, payload.size)
            assertEquals(0x00, payload[0].toInt())
        }
    }

    @Test
    fun `real ANC notifications decode to the modes the app displayed`() {
        assertEquals(NoiseControlMode.TRANSPARENCY, AncModeParser.parse(ancNotifyWith(0x00, 0x01)))
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION_DEEP,
            AncModeParser.parse(ancNotifyWith(0x10, 0x00)),
        )
        assertEquals(NoiseControlMode.OFF, AncModeParser.parse(ancNotifyWith(0x08, 0x00)))
    }

    @Test
    fun `EQ set packet layout matches this project's builder`() {
        val captured = framesWith(0x0406)
        assertEquals(3, captured.size)

        captured.forEach { frame ->
            val preset = frame.last().toInt() and 0xFF
            val expected = Enums.eqPresetPacket(preset)
            assertEquals(expected.size, frame.size)
            expected.indices.filter { it != 6 }.forEach { assertEquals(expected[it], frame[it]) }
        }
        assertEquals(setOf(0x00, 0x01, 0x02), captured.map { it.last().toInt() and 0xFF }.toSet())
    }

    @Test
    fun `real EQ query responses decode to the presets this unit supports`() {
        val responses = framesWith(0x810F)

        assertEquals(3, responses.size)
        assertEquals(
            listOf(0, 1, 2),
            responses.map { EqPresetParser.parse(it) }.sortedBy { it ?: -1 },
        )
    }

    /**
     * The official app queries ANC state with payload `02 03`, and the reply
     * echoes that selector rather than the `01 01` marker this project's parser
     * scans for. Writes are confirmed identical, so only the read path diverges.
     */
    @Test
    fun `captured ANC query response echoes the app's selector and stays undecodable`() {
        val query = framesWith(0x010C).single()
        val response = framesWith(0x810C).single()

        assertArrayEquals(OppoTestFixtures.hex("02 03"), payloadOf(query))
        assertArrayEquals(OppoTestFixtures.hex("00 02 03 06 00"), payloadOf(response))
        assertNull(AncModeParser.parse(response))
    }

    @Test
    fun `captured ANC and EQ stream survives byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
