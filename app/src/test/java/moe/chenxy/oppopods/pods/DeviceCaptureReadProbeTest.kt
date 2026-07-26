package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settles the two read paths passive capture could not reach.
 *
 * The official app never sends either command, so watching it can only ever show
 * their absence. These frames come from deliberately issuing this project's own
 * queries on the live socket and recording what the device sent back; both the
 * request and the reply were then found byte-for-byte in the raw HCI capture.
 *
 * Result: both queries work. The earlier "unverified" status was pessimistic —
 * the app simply prefers other paths.
 */
class DeviceCaptureReadProbeTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-read-probe.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    private fun frameWith(command: Int) = frames.first { cmdOf(it) == command }

    @Test
    fun `battery query sent is exactly what this project builds`() {
        assertArrayEquals(
            OppoPackets.buildPacket(Cmd.QUERY_BATTERY),
            frameWith(Cmd.QUERY_BATTERY),
        )
    }

    @Test
    fun `device answers the battery query this project sends`() {
        val response = frameWith(0x8106)

        assertEquals(0xF0, response[6].toInt() and 0xFF)   // echoes our sequence
        val battery = BatteryParser.parse(response)!!
        assertEquals(100, battery.left?.level)
        assertEquals(100, battery.right?.level)
        assertFalse(battery.left?.isCharging == true)
        assertNull(battery.case)
    }

    @Test
    fun `ANC query sent is exactly what this project builds`() {
        assertArrayEquals(
            OppoPackets.buildPacket(Cmd.QUERY_ANC_MODE, payload = OppoTestFixtures.hex("01 01")),
            frameWith(Cmd.QUERY_ANC_MODE),
        )
    }

    @Test
    fun `device answers the ANC query with a value this project can decode`() {
        val response = frameWith(Cmd.ANC_MODE_RESPONSE)

        assertEquals(0xF0, response[6].toInt() and 0xFF)
        assertArrayEquals(
            OppoTestFixtures.hex("00 01 01 10 00"),
            response.copyOfRange(9, response.size),
        )
        // 10 00 after the 01 01 marker; the app showed 降噪 / 深度降噪 at the time.
        assertEquals(NoiseControlMode.NOISE_CANCELLATION_DEEP, AncModeParser.parse(response))
    }

    @Test
    fun `probe stream survives byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
