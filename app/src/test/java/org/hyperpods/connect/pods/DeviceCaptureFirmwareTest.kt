package org.hyperpods.connect.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Firmware query captured from a real OPPO Enco Air5s.
 *
 * The project has no parser for this command yet; the test pins the observed
 * wire format so one can be written against real bytes instead of a guess.
 */
class DeviceCaptureFirmwareTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-firmware.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    @Test
    fun `firmware query is a bare command with no payload`() {
        val query = frames.first { cmdOf(it) == 0x0105 }

        assertArrayEquals(OppoTestFixtures.hex("AA 07 00 00 05 01 20 00 00"), query)
        assertEquals(0, (query[7].toInt() and 0xFF) or ((query[8].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `firmware response carries the version as component field value triples`() {
        val response = frames.first { cmdOf(it) == 0x8105 }
        val payload = response.copyOfRange(9, response.size)

        assertEquals(0x00, payload[0].toInt())
        assertEquals("1,2,163,2,2,163,3,1,01,3,2,102", String(payload, 2, payload.size - 2, Charsets.US_ASCII))

        // component -> field -> value, matching the app's "163.163.102" display.
        val triples = String(payload, 2, payload.size - 2, Charsets.US_ASCII)
            .split(',')
            .chunked(3)
            .associate { (component, field, value) -> component.toInt() to field.toInt() to value }

        assertEquals("163", triples[1 to 2])
        assertEquals("163", triples[2 to 2])
        assertEquals("102", triples[3 to 2])
    }

    @Test
    fun `firmware frames survive byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
