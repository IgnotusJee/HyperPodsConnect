package org.hyperpods.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.hyperpods.connect.ipc.EqualizerCurvePayload
import org.hyperpods.connect.ipc.HeadphoneIpcContract
import org.hyperpods.connect.ipc.HeadphoneSnapshotPayload
import org.hyperpods.connect.ipc.IpcCommandPayload
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual hardware tests for a connected LinkBuds S. Mutating tests restore the initial state. */
@RunWith(AndroidJUnit4::class)
class SonyCustomEqDeviceTest {
    @Test
    fun readReadyProjectionAndCapabilityMatrix() {
        withSnapshotReceiver { context, events ->
            val snapshot = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )

            assertEquals("sony", snapshot.vendorId)
            assertEquals("LinkBuds S", snapshot.deviceName)
            assertEquals("READY", snapshot.connection.name)
            assertTrue(snapshot.protocolReady)
            assertEquals("BLE_GATT", snapshot.transport)
            assertEquals("EARBUDS_WITH_CASE", snapshot.topology)
            assertEquals(setOf("LEFT", "RIGHT", "CASE"), snapshot.batteries.map { it.component }.toSet())

            val readable = snapshot.capabilities.filter { it.canRead }
            assertTrue(readable.any { it.featureId == "EQUALIZER" })
            assertTrue(readable.any { it.featureId == "NOISE_CONTROL" })
            readable.sortedBy { it.featureId }.forEach { capability ->
                val value = snapshot.features[capability.featureId]
                println(
                    "SONY_CAPABILITY id=${capability.featureId} read=${capability.canRead} " +
                        "write=${capability.canWrite} values=${capability.allowedValues} " +
                        "confirmed=${value?.confirmed} source=${value?.source}",
                )
            }
            println(
                "SONY_READY firmware=${snapshot.firmware} batteries=${snapshot.batteries} " +
                    "wearing=${snapshot.wearing}",
            )
        }
    }

    @Test
    fun changeEqualizerPresetWithSameRequestIdAndRestore() {
        withSnapshotReceiver { context, events ->
            var latest = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            latest = request(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_REFRESH_FEATURE,
                    value = "EQUALIZER",
                ),
                latest,
            )
            assertEquals("sony", latest.vendorId)
            assertEquals("LinkBuds S", latest.deviceName)
            val capability = latest.capabilities.single { it.featureId == "EQUALIZER" }
            assertTrue(capability.canWrite)
            val originalPreset = requireNotNull(latest.features["EQUALIZER"]?.confirmed)
            val alternatePreset = capability.allowedValues.firstOrNull { it != originalPreset }
                ?: error("No alternate EQ preset is available")
            var changed = false
            try {
                latest = setPresetAndReadBack(context, events, alternatePreset, latest)
                assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
                assertEquals(alternatePreset, latest.features["EQUALIZER"]?.confirmed)
                assertEquals("READ_BACK", latest.features["EQUALIZER"]?.source)
                changed = true
                println("SONY_EQ_PRESET_CHANGED from=$originalPreset to=$alternatePreset")
            } finally {
                if (changed && latest.features["EQUALIZER"]?.confirmed != originalPreset) {
                    latest = setPresetAndReadBack(context, events, originalPreset, latest)
                    assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
                    assertEquals(originalPreset, latest.features["EQUALIZER"]?.confirmed)
                    assertEquals("READ_BACK", latest.features["EQUALIZER"]?.source)
                    println("SONY_EQ_PRESET_RESTORED preset=$originalPreset")
                }
            }
        }
    }

    @Test
    fun changeNoiseAndTransparencyControlsAndRestore() {
        withSnapshotReceiver { context, events ->
            var latest = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            val initial = latest
            assertEquals("sony", initial.vendorId)
            assertEquals("LinkBuds S", initial.deviceName)
            val capabilities = initial.capabilities.associateBy { it.featureId }
            val noiseCapability = requireNotNull(capabilities["NOISE_CONTROL"])
            val levelCapability = requireNotNull(capabilities["AMBIENT_SOUND_LEVEL"])
            val vocalCapability = requireNotNull(capabilities["TRANSPARENCY_VOCAL_ENHANCEMENT"])
            assertTrue(noiseCapability.canWrite)
            assertTrue(levelCapability.canWrite)
            assertTrue(vocalCapability.canWrite)
            assertEquals(listOf("false", "true"), vocalCapability.allowedValues)

            val originalNoise = requireNotNull(initial.features["NOISE_CONTROL"]?.confirmed)
            val originalLevel = requireNotNull(initial.features["AMBIENT_SOUND_LEVEL"]?.confirmed)
            val originalVocal = requireNotNull(
                initial.features["TRANSPARENCY_VOCAL_ENHANCEMENT"]?.confirmed,
            )
            try {
                if (latest.features["NOISE_CONTROL"]?.confirmed != "TRANSPARENCY") {
                    latest = writeAndAssertReadBack(
                        context,
                        events,
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        "TRANSPARENCY",
                        "NOISE_CONTROL",
                        latest,
                    )
                }

                val alternateLevel = levelCapability.allowedValues.first { it != originalLevel }
                latest = writeAndAssertReadBack(
                    context,
                    events,
                    HeadphoneIpcContract.TYPE_SET_AMBIENT_SOUND_LEVEL,
                    alternateLevel,
                    "AMBIENT_SOUND_LEVEL",
                    latest,
                )
                latest = writeAndAssertReadBack(
                    context,
                    events,
                    HeadphoneIpcContract.TYPE_SET_AMBIENT_SOUND_LEVEL,
                    originalLevel,
                    "AMBIENT_SOUND_LEVEL",
                    latest,
                )

                val alternateVocal = (!originalVocal.toBooleanStrict()).toString()
                latest = writeAndAssertReadBack(
                    context,
                    events,
                    HeadphoneIpcContract.TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT,
                    alternateVocal,
                    "TRANSPARENCY_VOCAL_ENHANCEMENT",
                    latest,
                )
                latest = writeAndAssertReadBack(
                    context,
                    events,
                    HeadphoneIpcContract.TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT,
                    originalVocal,
                    "TRANSPARENCY_VOCAL_ENHANCEMENT",
                    latest,
                )
                println(
                    "SONY_TRANSPARENCY_RESTORED level=$originalLevel vocal=$originalVocal",
                )
            } finally {
                val current = runCatching {
                    request(
                        context,
                        events,
                        IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
                        latest,
                    )
                }.getOrNull() ?: latest
                if (current.features["AMBIENT_SOUND_LEVEL"]?.confirmed != originalLevel) {
                    latest = writeAndAssertReadBack(
                        context,
                        events,
                        HeadphoneIpcContract.TYPE_SET_AMBIENT_SOUND_LEVEL,
                        originalLevel,
                        "AMBIENT_SOUND_LEVEL",
                        current,
                    )
                } else {
                    latest = current
                }
                if (
                    latest.features["TRANSPARENCY_VOCAL_ENHANCEMENT"]?.confirmed != originalVocal
                ) {
                    latest = writeAndAssertReadBack(
                        context,
                        events,
                        HeadphoneIpcContract.TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT,
                        originalVocal,
                        "TRANSPARENCY_VOCAL_ENHANCEMENT",
                        latest,
                    )
                }
                if (latest.features["NOISE_CONTROL"]?.confirmed != originalNoise) {
                    latest = writeAndAssertReadBack(
                        context,
                        events,
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        originalNoise,
                        "NOISE_CONTROL",
                        latest,
                    )
                }
                assertEquals(originalNoise, latest.features["NOISE_CONTROL"]?.confirmed)
                assertEquals(originalLevel, latest.features["AMBIENT_SOUND_LEVEL"]?.confirmed)
                assertEquals(
                    originalVocal,
                    latest.features["TRANSPARENCY_VOCAL_ENHANCEMENT"]?.confirmed,
                )
                println("SONY_NOISE_RESTORED mode=$originalNoise")
            }
        }
    }

    @Test
    fun restorePreset16AndVerify() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            val initial = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            val restored = setPresetAndReadBack(context, events, "sony:eq:16", initial)
            assertEquals("sony:eq:16", restored.features["EQUALIZER"]?.confirmed)
            assertEquals("sony:eq:16", restored.equalizerCurve?.confirmed?.slotId)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun modify400HzReadBackAndRestore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )

        var initial: HeadphoneSnapshotPayload? = null
        var latest: HeadphoneSnapshotPayload? = null
        var originalPresetId: String? = null
        var selectedCustomOneForTest = false
        var customOneCurveChanged = false
        try {
            initial = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            initial = request(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_REFRESH_FEATURE,
                    value = "EQUALIZER",
                ),
                initial,
            )
            assertEquals("sony", initial.vendorId)
            assertEquals("LinkBuds S", initial.deviceName)
            assertTrue(initial.protocolReady)

            val capability = initial.capabilities.single { it.featureId == "EQUALIZER" }
            val spec = requireNotNull(capability.equalizerCurveSpec)
            originalPresetId = requireNotNull(initial.features["EQUALIZER"]?.confirmed)
            if (originalPresetId != "sony:eq:a1") {
                selectedCustomOneForTest = true
                initial = setPresetAndReadBack(context, events, "sony:eq:a1", initial)
                assertEquals(
                    "select Custom 1 operation=${initial.operation}",
                    "READ_BACK_CONFIRMED",
                    initial.operation?.phase,
                )
                assertEquals("sony:eq:a1", initial.features["EQUALIZER"]?.confirmed)
                initial = request(
                    context,
                    events,
                    IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_REFRESH_FEATURE,
                        value = "EQUALIZER",
                    ),
                    initial,
                )
            }
            val original = requireNotNull(initial.equalizerCurve?.confirmed)
            assertEquals("sony:eq:a1", original.slotId)
            assertTrue(original.slotId in spec.writableSlotIds)
            assertEquals(6, spec.bands.size)
            assertEquals(listOf(null, 400, 1_000, 2_500, 6_300, 16_000), spec.bands.map { it.centerFrequencyHz })

            val bandIndex = 1
            val band = spec.bands[bandIndex]
            val delta = if (original.gains[bandIndex] < band.maxGain) band.step else -band.step
            val updated = original.copy(
                gains = original.gains.toMutableList().apply {
                    this[bandIndex] = (this[bandIndex] + delta).coerceIn(band.minGain, band.maxGain)
                },
            )
            check(updated != original) { "400 Hz has no writable range" }
            println("SONY_CUSTOM_EQ_INITIAL slot=${original.slotId} gains=${original.gains}")

            latest = setCurveAndReadBack(
                context,
                events,
                updated,
                initial,
            )
            assertEquals(
                "operation=${latest.operation} curve=${latest.equalizerCurve}",
                "READ_BACK_CONFIRMED",
                latest.operation?.phase,
            )
            customOneCurveChanged = true
            assertEquals(updated, latest.equalizerCurve?.confirmed)
            assertEquals("READ_BACK", latest.equalizerCurve?.source)
            println("SONY_CUSTOM_EQ_CHANGED index=$bandIndex gains=${updated.gains}")
        } finally {
            val original = initial?.equalizerCurve?.confirmed
            if (
                original != null &&
                customOneCurveChanged &&
                latest?.equalizerCurve?.confirmed != original
            ) {
                val restored = setCurveAndReadBack(
                    context,
                    events,
                    original,
                    latest ?: initial,
                )
                assertEquals(
                    "restore operation=${restored.operation} curve=${restored.equalizerCurve}",
                    "READ_BACK_CONFIRMED",
                    restored.operation?.phase,
                )
                assertEquals(original, restored.equalizerCurve?.confirmed)
                assertEquals("READ_BACK", restored.equalizerCurve?.source)
                println("SONY_CUSTOM_EQ_RESTORED gains=${original.gains}")
                latest = restored
                customOneCurveChanged = false
            }
            if (selectedCustomOneForTest && originalPresetId != null) {
                val restoredPreset = setPresetAndReadBack(
                    context = context,
                    events = events,
                    presetId = originalPresetId,
                    target = latest ?: requireNotNull(initial),
                )
                assertEquals(
                    "restore preset operation=${restoredPreset.operation}",
                    "READ_BACK_CONFIRMED",
                    restoredPreset.operation?.phase,
                )
                assertEquals(
                    originalPresetId,
                    restoredPreset.features["EQUALIZER"]?.confirmed,
                )
                println("SONY_CUSTOM_EQ_PRESET_RESTORED preset=$originalPresetId")
            }
            context.unregisterReceiver(receiver)
        }
    }

    private fun snapshotReceiver(
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
    ) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = intent ?: return
            HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                events.offer(
                    current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot,
                )
            }
        }
    }

    private fun withSnapshotReceiver(
        block: (Context, LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            block(context, events)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun setPresetAndReadBack(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        presetId: String,
        target: HeadphoneSnapshotPayload,
    ): HeadphoneSnapshotPayload = request(
        context,
        events,
        IpcCommandPayload(
            HeadphoneIpcContract.TYPE_SET_EQUALIZER,
            value = presetId,
        ),
        target,
    )

    private fun setCurveAndReadBack(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        curve: EqualizerCurvePayload,
        target: HeadphoneSnapshotPayload,
    ): HeadphoneSnapshotPayload = request(
        context,
        events,
        IpcCommandPayload(
            type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
            curve = curve,
        ),
        target,
    )

    private fun writeAndAssertReadBack(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        type: String,
        value: String,
        featureId: String,
        target: HeadphoneSnapshotPayload,
    ): HeadphoneSnapshotPayload {
        val result = request(context, events, IpcCommandPayload(type, value), target)
        assertEquals("operation=${result.operation}", "READ_BACK_CONFIRMED", result.operation?.phase)
        assertEquals(value, result.features[featureId]?.confirmed)
        assertEquals("READ_BACK", result.features[featureId]?.source)
        return result
    }

    private fun request(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        payload: IpcCommandPayload,
        target: HeadphoneSnapshotPayload? = null,
    ): HeadphoneSnapshotPayload {
        val requestId = "sony-device-test-${UUID.randomUUID()}"
        context.sendIdentitySharedBroadcast(
            HeadphoneIpcContract.commandIntent(
                command = payload,
                requestId = requestId,
                deviceId = target?.deviceId,
                vendorId = target?.vendorId,
            ),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            if (event.first != requestId) continue
            val phase = event.second.operation?.phase
            val isWrite = payload.type.startsWith("set_")
            val terminal = phase in setOf(
                "STATE_CONFIRMED",
                "READ_BACK_CONFIRMED",
                "FAILED",
                "TIMED_OUT",
                "CANCELLED",
            )
            if (!isWrite || (event.second.operation?.requestId == requestId && terminal)) {
                return event.second
            }
        }
        error("timed out waiting for snapshot requestId=$requestId")
    }
}
