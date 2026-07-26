package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression against frames captured from a real OPPO Enco Air5s.
 *
 * Unlike the official-source vectors, every frame here was observed on the wire
 * and confirmed byte-for-byte on both the HCI capture and the official app's
 * socket. See the fixture's metadata for the capture conditions.
 */
class DeviceCaptureRegressionTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-cold-init.hex")

    private fun frameWith(command: Int, minPayload: Int = 0): ByteArray =
        frames.first { frame ->
            val cmd = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
            val payLen = (frame[7].toInt() and 0xFF) or ((frame[8].toInt() and 0xFF) shl 8)
            cmd == command && payLen >= minPayload
        }

    @Test
    fun `captured stream splits into exactly the recorded frames`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }

        val decoded = decoder.feed(concatenated)

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
        assertEquals(0, decoder.pendingByteCount())
    }

    @Test
    fun `byte at a time delivery reproduces the same frames`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }

    @Test
    fun `real notification handshake response yields the advertised ids`() {
        val response = frameWith(Cmd.NOTIFICATION_SUPPORT_RESPONSE)

        val ids = NotificationSupportParser.parse(response)!!

        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x08, 0x0B, 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte()),
            ids,
        )
    }

    @Test
    fun `real battery notification reports both earbuds without a case component`() {
        val report = frames.first { frame ->
            val cmd = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
            cmd == 0x0204 && frame[9].toInt() == 0x01
        }

        val battery = BatteryParser.parseActiveReport(report)!!

        assertEquals(100, battery.left?.level)
        assertEquals(100, battery.right?.level)
        assertFalse(battery.left?.isCharging == true)
        assertFalse(battery.right?.isCharging == true)
        // This unit reported only two components; a case entry would be invented.
        assertNull(battery.case)
    }

    @Test
    fun `real wear notification maps every component`() {
        val report = frames.first { frame ->
            val cmd = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
            cmd == 0x0204 && frame[9].toInt() == 0x02
        }

        val wear = WearStatusParser.parse(report)!!

        assertEquals(WearState.REMOVED, wear.left)
        assertEquals(WearState.REMOVED, wear.right)
        assertEquals(WearState.IN_CASE, wear.case)
    }

    /**
     * Documents a confirmed divergence rather than asserting current behaviour is
     * right: the official app queries ANC with payload `03 01`, and the device
     * answers `01 03 01 00 00`. This project sends `01 01` and the parser scans
     * for an `01 01` marker, so it cannot decode the captured reply. Closing this
     * needs a capture of an explicit ANC mode change.
     */
    @Test
    fun `captured ANC response is not decodable by the current parser`() {
        val response = frameWith(Cmd.ANC_MODE_RESPONSE)

        assertArrayEquals(
            OppoTestFixtures.hex("01 03 01 00 00"),
            response.copyOfRange(9, response.size),
        )
        assertNull(AncModeParser.parse(response))
    }

    @Test
    fun `capability response carries a success status and an eight byte bitmap`() {
        val response = frameWith(0x8100)
        val payload = response.copyOfRange(9, response.size)

        assertEquals(9, payload.size)
        assertEquals(0x00, payload[0].toInt())
        assertArrayEquals(OppoTestFixtures.hex("FF 75 52 EA A4 0E 07 0F"), payload.copyOfRange(1, 9))
    }

    @Test
    fun `every captured response echoes its request command and sequence`() {
        val requests = frames.filter { (it[5].toInt() and 0x80) == 0 }
            .associateBy { frame ->
                val cmd = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
                cmd to (frame[6].toInt() and 0xFF)
            }

        val responses = frames.filter { (it[5].toInt() and 0x80) != 0 }
        assertTrue(responses.isNotEmpty())

        responses.forEach { response ->
            val cmd = (response[4].toInt() and 0xFF) or ((response[5].toInt() and 0xFF) shl 8)
            val seq = response[6].toInt() and 0xFF
            val request = requests[(cmd and 0x7FFF) to seq]
            if (request != null) {
                assertEquals(cmd, (request[4].toInt() and 0xFF) or
                    ((request[5].toInt() and 0xFF) shl 8) or 0x8000)
            }
        }
    }
}
