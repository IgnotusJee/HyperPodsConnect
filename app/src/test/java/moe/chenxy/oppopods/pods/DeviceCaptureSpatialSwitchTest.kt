package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spatial sound switch (feature 0x1B) captured from a real OPPO Enco Air5s.
 *
 * Its presence in the batch response is the runtime capability evidence; the
 * Bluetooth name is deliberately irrelevant.
 */
class DeviceCaptureSpatialSwitchTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-spatial-switch.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    private fun assertSameExceptSeq(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.filter { it != 6 }.forEach { assertEquals(expected[it], actual[it]) }
    }

    @Test
    fun `switch packets this project builds match the ones the official app sends`() {
        val sets = frames.filter { cmdOf(it) == 0x0403 }
        assertEquals(3, sets.size)

        sets.forEach { frame ->
            val featureId = frame[9].toInt() and 0xFF
            val enabled = (frame[10].toInt() and 0xFF) == 1
            // Named after the Free4 in this project, but the Air5s uses the
            // same feature id, so the constant is not model specific.
            assertEquals(GameModeFeature.FREE4_SPATIAL_SOUND, featureId)
            assertSameExceptSeq(Enums.spatialSoundSwitchPacket(enabled), frame)
        }
        assertEquals(listOf(false, true, false), sets.map { (it[10].toInt() and 0xFF) == 1 })
    }

    @Test
    fun `every switch response reports success`() {
        val responses = frames.filter { cmdOf(it) == 0x8403 }

        assertEquals(3, responses.size)
        responses.forEach { response ->
            val payload = response.copyOfRange(9, response.size)
            assertEquals(1, payload.size)
            assertEquals(0x00, payload[0].toInt())
        }
    }

    /**
     * The device answers a set with a bare status byte and no echo of the new
     * value, so the existing parser — which expects the feature id and value in
     * the payload — cannot confirm the write from the response alone.
     */
    @Test
    fun `set response alone cannot confirm the new value`() {
        val response = frames.first { cmdOf(it) == 0x8403 }

        assertNull(SpatialAudioParser.parseSpatialSoundSwitchSetResponse(response))
    }

    /**
     * The device asks for twelve features on readback; this project's batch
     * query asks for eleven and omits some of them. Recorded so the divergence
     * is visible rather than discovered later as missing state.
     */
    @Test
    fun `device batch status query covers more features than this project asks for`() {
        val query = frames.first { cmdOf(it) == 0x010D }
        val payload = query.copyOfRange(9, query.size)
        val capturedFeatures = payload.drop(1).map { it.toInt() and 0xFF }.toSet()

        assertEquals(0x0C, payload[0].toInt())
        assertEquals(12, capturedFeatures.size)

        val ourPayload = Enums.QUERY_STATUS.copyOfRange(9, Enums.QUERY_STATUS.size)
        val ourFeatures = ourPayload.drop(1).map { it.toInt() and 0xFF }.toSet()
        assertEquals(11, ourFeatures.size)

        assertEquals(setOf(0x1D, 0x1E, 0x37), capturedFeatures - ourFeatures)
        assertEquals(setOf(0x13, 0x1C), ourFeatures - capturedFeatures)
    }

    @Test
    fun `captured switch stream survives byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
