package org.hyperpods.connect.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch status readback and battery reporting, captured from a real Air5s.
 *
 * The battery path is the notable part: across a 12 MB capture covering cold
 * start, ANC, EQ, firmware and switch traffic, the official app never sent
 * `0x0106`. Battery arrives only through the subscribed `0x0204` channel, and
 * the batch query returns feature toggles with no battery in them.
 */
class DeviceCaptureStatusReadbackTest {
    private val frames = OppoTestFixtures.deviceCaptureFrames("encoair5s-status-readback.hex")

    private fun cmdOf(frame: ByteArray) =
        (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)

    private fun batteryReports() = frames.filter { cmdOf(it) == 0x0204 && it[9].toInt() == 0x01 }

    @Test
    fun `batch status response decodes with the existing parser`() {
        val response = frames.first { cmdOf(it) == 0x810D }

        val status = GameModeParser.parseStatus(response)

        assertNotNull(status)
        assertFalse(status!!.mainEnabled == true)          // feature 0x28 = 0
        assertTrue(status.lowLatencyEnabled == true)       // feature 0x06 = 1
        assertFalse(status.dualDeviceConnectionEnabled == true)  // feature 0x11 = 0
    }

    @Test
    fun `two readbacks differ only in the spatial switch byte`() {
        val readbacks = frames.filter { cmdOf(it) == 0x810D }
        assertEquals(2, readbacks.size)

        val (first, second) = readbacks
        assertEquals(first.size, second.size)
        // Sequence differs too; everything else but the 0x1B value must match.
        val differing = first.indices.filter { first[it] != second[it] && it != 6 }
        assertEquals(1, differing.size)

        val index = differing.single()
        assertEquals(0x1B, first[index - 1].toInt())
        assertEquals(0x01, first[index].toInt())
        assertEquals(0x00, second[index].toInt())
    }

    @Test
    fun `battery notification carries charging state and an optional case`() {
        val reports = batteryReports()
        assertEquals(3, reports.size)

        val twoComponent = BatteryParser.parseActiveReport(reports[0])!!
        assertEquals(100, twoComponent.left?.level)
        assertEquals(100, twoComponent.right?.level)
        assertFalse(twoComponent.left?.isCharging == true)

        val mixed = BatteryParser.parseActiveReport(reports[1])!!
        assertEquals(100, mixed.left?.level)
        assertFalse(mixed.left?.isCharging == true)
        assertEquals(100, mixed.right?.level)
        assertTrue(mixed.right?.isCharging == true)
        assertEquals(40, mixed.case?.level)

        val bothCharging = BatteryParser.parseActiveReport(reports[2])!!
        assertTrue(bothCharging.left?.isCharging == true)
        assertTrue(bothCharging.right?.isCharging == true)
        assertEquals(40, bothCharging.case?.level)
    }

    /**
     * Pins the observed fact rather than an assumption: this project polls with
     * `Cmd.QUERY_BATTERY`, the official app never does, and nothing in the
     * capture shows the device answering it. Treat our polling path as
     * unverified on this model until a capture proves otherwise.
     */
    @Test
    fun `the battery poll this project sends never appears in the capture`() {
        // Notifications share the request-side command space (0x0204 has no
        // 0x8000 bit), so direction cannot be read off a frame alone; assert on
        // the command set instead.
        val commands = frames.map { cmdOf(it) }.toSet()

        assertFalse(commands.contains(Cmd.QUERY_BATTERY))
        assertFalse(commands.contains(Cmd.QUERY_BATTERY or 0x8000))
        assertTrue(commands.contains(0x010D))
        assertTrue(commands.contains(0x0204))
    }

    @Test
    fun `captured readback stream survives byte at a time reassembly`() {
        val decoder = OppoFrameStreamDecoder()
        val concatenated = frames.reduce { acc, frame -> acc + frame }
        val decoded = mutableListOf<ByteArray>()

        concatenated.forEach { decoded += decoder.feed(byteArrayOf(it)) }

        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { index, expected -> assertArrayEquals(expected, decoded[index]) }
    }
}
