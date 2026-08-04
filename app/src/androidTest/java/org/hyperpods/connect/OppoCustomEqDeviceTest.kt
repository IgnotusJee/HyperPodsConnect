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
import org.hyperpods.connect.ipc.HeadphoneActionContract
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerFeature
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual hardware test. Requires one connected OPPO headset and the LSPosed scope to be active. */
@RunWith(AndroidJUnit4::class)
class OppoCustomEqDeviceTest {

    @Test
    fun readCustomEqWithoutLogging() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshots = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(snapshots)
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            val initial = requestReady(context, snapshots)
            val refreshed = request(
                context,
                snapshots,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REFRESH_FEATURE, value = "EQUALIZER"),
                initial,
            )
            println(
                "OPPO_CUSTOM_EQ_NO_LOG ids=${customSlotIds(refreshed)} " +
                    "curve=${refreshed.equalizerCurve?.confirmed} operation=${refreshed.operation}",
            )
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun createModifyReadBackRestoreAndDeleteTemporarySlot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshots = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val snapshotReceiver = snapshotReceiver(snapshots)
        val sessionToken = "oppo-custom-eq-test-${UUID.randomUUID()}"
        val frequencies = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        val neutralGains = List(frequencies.size) { 0 }
        var target: HeadphoneSnapshotPayload? = null
        var assignedSlot: OppoCustomEqualizerSlot? = null
        var rawUnlocked = false
        var preservedCustomIds = emptySet<String>()
        var originalPresetId: String? = null
        var originalCurve: EqualizerCurvePayload? = null

        context.registerReceiver(
            snapshotReceiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            println("OPPO_CUSTOM_EQ_PHASE receivers-registered")
            val initial = requestReady(context, snapshots)
            assertEquals("oppo", initial.vendorId)
            assertTrue(initial.protocolReady)
            val initialCustomIds = customSlotIds(initial)
            val eqCapability = initial.capabilities.single { it.featureId == "EQUALIZER" }
            originalPresetId = initial.features["EQUALIZER"]?.confirmed
            originalCurve = initial.equalizerCurve?.confirmed
            val interruptedId = initialCustomIds.singleOrNull { id ->
                eqCapability.valueLabels[id] in setOf("CodexTmp", "HyperPods Custom")
            }
            preservedCustomIds = initialCustomIds.filterNot { it == interruptedId }.toSet()
            println(
                "OPPO_CUSTOM_EQ_PHASE initial ids=$initialCustomIds " +
                    "selected=$originalPresetId interrupted=$interruptedId",
            )
            sendLegacy(context, HeadphoneActionContract.ACTION_RFCOMM_DEBUG_UNLOCK) {
                putExtra(HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
            }
            rawUnlocked = true
            println("OPPO_CUSTOM_EQ_PHASE raw-unlock-requested")

            if (interruptedId == null) {
                assertTrue(
                    "Air5s must have one free custom slot for this reversible test",
                    OppoCustomEqualizerFeature.CREATION_SLOT_ID in
                        requireNotNull(eqCapability.equalizerCurveSpec).writableSlotIds,
                )
                target = request(
                    context,
                    snapshots,
                    IpcCommandPayload(
                        type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                        curve = EqualizerCurvePayload(
                            OppoCustomEqualizerFeature.CREATION_SLOT_ID,
                            neutralGains,
                        ),
                    ),
                    initial,
                )
                assertEquals("READ_BACK", target?.equalizerCurve?.source)
                val createdId = customSlotIds(requireNotNull(target))
                    .single { it !in initialCustomIds }
                println("OPPO_CUSTOM_EQ_PHASE production-add-read-back slot=$createdId")
            } else {
                target = request(
                    context,
                    snapshots,
                    IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, value = interruptedId),
                    initial,
                )
                println("OPPO_CUSTOM_EQ_PHASE interrupted-slot-recovered slot=$interruptedId")
            }
            val createdId = customSlotIds(requireNotNull(target))
                .single { it !in preservedCustomIds }
            if (target?.equalizerCurve?.confirmed?.slotId != createdId) {
                target = request(
                    context,
                    snapshots,
                    IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, value = createdId),
                    target,
                )
            }
            println("OPPO_CUSTOM_EQ_PHASE slot-selected")
            assignedSlot = slotFromSnapshot(requireNotNull(target), "HyperPods Custom")
            val original = requireNotNull(target?.equalizerCurve?.confirmed)
            val spec = requireNotNull(
                target?.capabilities?.single { it.featureId == "EQUALIZER" }?.equalizerCurveSpec,
            )
            assertEquals(frequencies, spec.bands.map { it.centerFrequencyHz })
            if (initialCustomIds.isEmpty()) assertEquals(neutralGains, original.gains)
            println(
                "OPPO_CUSTOM_EQ_CREATED slot=${original.slotId} gains=${original.gains} " +
                    "bands=${spec.bands.map { it.centerFrequencyHz }}",
            )

            val updated = original.copy(
                gains = original.gains.toMutableList().apply { this[0] += spec.bands[0].step },
            )
            val changed = request(
                context,
                snapshots,
                IpcCommandPayload(
                    type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                    curve = updated,
                ),
                target,
            )
            println(
                "OPPO_CUSTOM_EQ_CHANGE_RESULT operation=${changed.operation} " +
                    "curve=${changed.equalizerCurve}",
            )
            assertEquals(updated, changed.equalizerCurve?.confirmed)
            assertEquals("READ_BACK", changed.equalizerCurve?.source)
            println("OPPO_CUSTOM_EQ_CHANGED gains=${updated.gains}")

            target = request(
                context,
                snapshots,
                IpcCommandPayload(
                    type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                    curve = original,
                ),
                changed,
            )
            assertEquals(original, target?.equalizerCurve?.confirmed)
            assertEquals("READ_BACK", target?.equalizerCurve?.source)
            println("OPPO_CUSTOM_EQ_RESTORED gains=${original.gains}")

            target = request(
                context,
                snapshots,
                IpcCommandPayload(
                    type = HeadphoneIpcContract.TYPE_RENAME_EQUALIZER_PRESET,
                    value = original.slotId,
                    name = "CodexTmp",
                ),
                target,
            )
            assertEquals(
                "CodexTmp",
                requireNotNull(target).capabilities.single { it.featureId == "EQUALIZER" }
                    .valueLabels[original.slotId],
            )
            assertEquals(original, target?.equalizerCurve?.confirmed)
            assignedSlot = slotFromSnapshot(requireNotNull(target), "CodexTmp")
            println("OPPO_CUSTOM_EQ_RENAMED slot=${original.slotId} name=CodexTmp")
        } finally {
            if (assignedSlot == null && target != null) {
                assignedSlot = runCatching {
                    slotFromSnapshot(requireNotNull(target), "HyperPods Custom")
                }
                    .getOrNull()
            }
            assignedSlot?.let { slot ->
                println("OPPO_CUSTOM_EQ_PHASE cleanup-delete-start slot=${slot.slotId}")
                val productionDelete = runCatching {
                    request(
                        context,
                        snapshots,
                        IpcCommandPayload(
                            HeadphoneIpcContract.TYPE_DELETE_EQUALIZER_PRESET,
                            value = slot.slotId,
                        ),
                        target,
                    )
                }
                val deleted = productionDelete.getOrNull()
                println(
                    "OPPO_CUSTOM_EQ_DELETE_RESULT ids=${deleted?.let(::customSlotIds)} " +
                        "curve=${deleted?.equalizerCurve?.confirmed} operation=${deleted?.operation}",
                )
                if (deleted == null || customSlotIds(deleted).toSet() != preservedCustomIds) {
                    sendRawForReadback(
                        context,
                        sessionToken,
                        requireNotNull(OppoCustomEqualizerFeature.delete(slot)),
                    )
                    Thread.sleep(1_000)
                    request(
                        context,
                        snapshots,
                        IpcCommandPayload(
                            HeadphoneIpcContract.TYPE_REFRESH_FEATURE,
                            value = "EQUALIZER",
                        ),
                        target,
                    )
                    error("production delete did not receive device readback confirmation")
                }
                println("OPPO_CUSTOM_EQ_DELETED slot=${slot.slotId}")
                originalPresetId?.takeIf { it != slot.slotId }?.let { presetId ->
                    val restoredSelection = request(
                        context,
                        snapshots,
                        IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, value = presetId),
                        deleted,
                    )
                    assertEquals(presetId, restoredSelection.features["EQUALIZER"]?.confirmed)
                    originalCurve?.takeIf { it.slotId == presetId }?.let { curve ->
                        assertEquals(curve, restoredSelection.equalizerCurve?.confirmed)
                    }
                    println("OPPO_CUSTOM_EQ_SELECTION_RESTORED preset=$presetId")
                }
            }
            if (rawUnlocked) {
                sendLegacy(context, HeadphoneActionContract.ACTION_RFCOMM_DEBUG_LOCK) {
                    putExtra(HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
                }
            }
            context.unregisterReceiver(snapshotReceiver)
        }
    }

    @Test
    fun captureCustomEqRefresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshots = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val logs = LinkedBlockingQueue<String>()
        val snapshotReceiver = snapshotReceiver(snapshots)
        val logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != HeadphoneActionContract.ACTION_RFCOMM_LOG) return
                logs.offer(
                    "${intent.getStringExtra("tag")}: ${intent.getStringExtra("message")}",
                )
            }
        }
        context.registerReceiver(
            snapshotReceiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        context.registerReceiver(
            logReceiver,
            IntentFilter(HeadphoneActionContract.ACTION_RFCOMM_LOG),
            Context.RECEIVER_EXPORTED,
        )
        try {
            context.sendIdentitySharedBroadcast(
                Intent(HeadphoneActionContract.ACTION_RFCOMM_LOG_CONNECT).apply {
                    setPackage(HeadphoneIpcContract.BLUETOOTH_HOST_PACKAGE)
                },
            )
            val initial = request(
                context,
                snapshots,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            val refreshed = request(
                context,
                snapshots,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_REFRESH_FEATURE,
                    value = "EQUALIZER",
                ),
                initial,
            )
            println(
                "OPPO_CUSTOM_EQ_REFRESH curve=${refreshed.equalizerCurve?.confirmed} " +
                    "spec=${refreshed.capabilities.single { it.featureId == "EQUALIZER" }.equalizerCurveSpec}",
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < deadline) {
                val line = logs.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if ("RFCOMM/TX" in line || "RFCOMM/RX" in line) println("OPPO_CUSTOM_EQ_WIRE $line")
            }
        } finally {
            context.sendIdentitySharedBroadcast(
                Intent(HeadphoneActionContract.ACTION_RFCOMM_LOG_DISCONNECT).apply {
                    setPackage(HeadphoneIpcContract.BLUETOOTH_HOST_PACKAGE)
                },
            )
            context.unregisterReceiver(logReceiver)
            context.unregisterReceiver(snapshotReceiver)
        }
    }

    @Test
    fun modifyOneBandReadBackAndRestore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                val snapshot = HeadphoneIpcContract.decodeSnapshot(current) ?: return
                events.offer(
                    current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot,
                )
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )

        var initial: HeadphoneSnapshotPayload? = null
        var changed = false
        try {
            initial = request(context, events, IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT))
            assertEquals("oppo", initial.vendorId)
            assertTrue(initial.protocolReady)
            assertEquals("OPPO Enco Air5s", initial.deviceName)

            val eqCapability = initial.capabilities.single { it.featureId == "EQUALIZER" }
            val spec = assertNotNull(eqCapability.equalizerCurveSpec).let {
                requireNotNull(eqCapability.equalizerCurveSpec)
            }
            org.junit.Assume.assumeNotNull(initial.equalizerCurve?.confirmed)
            val original = requireNotNull(initial.equalizerCurve?.confirmed)
            assertTrue(original.slotId in spec.writableSlotIds)
            assertEquals(spec.bands.size, original.gains.size)

            val bandIndex = original.gains.indices.firstOrNull { index ->
                original.gains[index] < spec.bands[index].maxGain
            } ?: original.gains.indices.first { index ->
                original.gains[index] > spec.bands[index].minGain
            }
            val delta = if (original.gains[bandIndex] < spec.bands[bandIndex].maxGain) {
                spec.bands[bandIndex].step
            } else {
                -spec.bands[bandIndex].step
            }
            val updated = original.copy(
                gains = original.gains.toMutableList().apply { this[bandIndex] += delta },
            )

            println(
                "OPPO_CUSTOM_EQ_INITIAL slot=${original.slotId} gains=${original.gains} " +
                    "bands=${spec.bands.map { it.centerFrequencyHz }}",
            )
            val changedSnapshot = request(
                context,
                events,
                IpcCommandPayload(
                    type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                    curve = updated,
                ),
                initial,
            )
            assertEquals("READ_BACK_CONFIRMED", changedSnapshot.operation?.phase)
            assertEquals(updated, changedSnapshot.equalizerCurve?.confirmed)
            assertEquals("READ_BACK", changedSnapshot.equalizerCurve?.source)
            changed = true
            println("OPPO_CUSTOM_EQ_CHANGED index=$bandIndex gains=${updated.gains}")
        } finally {
            val original = initial?.equalizerCurve?.confirmed
            if (original != null) {
                val restored = request(
                    context,
                    events,
                    IpcCommandPayload(
                        type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                        curve = original,
                    ),
                    initial,
                )
                assertEquals("READ_BACK_CONFIRMED", restored.operation?.phase)
                assertEquals(original, restored.equalizerCurve?.confirmed)
                println("OPPO_CUSTOM_EQ_RESTORED changed=$changed gains=${original.gains}")
            }
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun readCurrentCurveAfterReconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                    events.offer(
                        current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot,
                    )
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            val snapshot = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            assertEquals("oppo", snapshot.vendorId)
            assertTrue(snapshot.protocolReady)
            val curve = requireNotNull(snapshot.equalizerCurve?.confirmed)
            println("OPPO_CUSTOM_EQ_RECONNECTED slot=${curve.slotId} gains=${curve.gains}")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun verifyReadyProjectionAndCapabilityMatrix() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            val snapshot = requestReady(context, events)
            assertEquals("oppo", snapshot.vendorId)
            assertEquals("OPPO Enco Air5s", snapshot.deviceName)
            assertEquals("EARBUDS_WITH_CASE", snapshot.topology)
            assertEquals("CLASSIC_SPP", snapshot.transport)
            assertTrue(snapshot.batteries.any { it.component == "LEFT" && it.level in 0..100 })
            assertTrue(snapshot.batteries.any { it.component == "RIGHT" && it.level in 0..100 })

            val capabilities = snapshot.capabilities.associateBy { it.featureId }
            val noiseControl = requireNotNull(capabilities["NOISE_CONTROL"])
            assertTrue(noiseControl.canRead)
            assertTrue(noiseControl.canWrite)
            assertEquals(
                setOf("OFF", "NOISE_CANCELLATION", "TRANSPARENCY"),
                noiseControl.allowedValues.toSet(),
            )

            val booleanFeatures = listOf(
                "TRANSPARENCY_VOCAL_ENHANCEMENT",
                "LOW_LATENCY",
                "SPATIAL_SOUND_SWITCH",
                "DUAL_DEVICE_CONNECTION",
            )
            booleanFeatures.forEach { featureId ->
                assertEquals(
                    "$featureId must expose canonical boolean values",
                    listOf("false", "true"),
                    requireNotNull(capabilities[featureId]).allowedValues,
                )
            }
            val spatialAudio = requireNotNull(capabilities["SPATIAL_AUDIO"])
            assertTrue(spatialAudio.canRead)
            assertTrue(snapshot.features["SPATIAL_AUDIO"]?.confirmed != null)
            if (spatialAudio.canWrite) {
                assertTrue("OFF" in spatialAudio.allowedValues)
                assertTrue(spatialAudio.allowedValues.any { it != "OFF" })
            }
            val spatialSwitch = requireNotNull(capabilities["SPATIAL_SOUND_SWITCH"])
            if (spatialSwitch.canRead) {
                assertTrue(snapshot.features["SPATIAL_SOUND_SWITCH"]?.confirmed != null)
            }
            println(
                "OPPO_READY_PROJECTION topology=${snapshot.topology} batteries=${snapshot.batteries} " +
                    "wearing=${snapshot.wearing} anc=${noiseControl.allowedValues} " +
                    "booleanValues=${booleanFeatures.associateWith { capabilities[it]?.allowedValues }} " +
                    "spatialAudio=$spatialAudio spatialSwitch=$spatialSwitch",
            )
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun verifyReversibleEqualizerPreset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        var latest: HeadphoneSnapshotPayload? = null
        var originalPreset: String? = null
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            latest = requestReady(context, events)
            val capability = latest.capabilities.single { it.featureId == "EQUALIZER" }
            assertTrue(capability.canRead)
            assertTrue(capability.canWrite)
            originalPreset = requireNotNull(latest.features["EQUALIZER"]?.confirmed)
            val alternatePreset = capability.allowedValues
                .first { it != originalPreset && !it.startsWith("oppo:eq:custom:") }

            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, alternatePreset),
                latest,
            )
            assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
            assertEquals(alternatePreset, latest.features["EQUALIZER"]?.confirmed)
            assertEquals("READ_BACK", latest.features["EQUALIZER"]?.source)
            println("OPPO_EQ_CHANGED $originalPreset->$alternatePreset requestId=${latest.operation?.requestId}")

            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, originalPreset),
                latest,
            )
            assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
            assertEquals(originalPreset, latest.features["EQUALIZER"]?.confirmed)
            assertEquals("READ_BACK", latest.features["EQUALIZER"]?.source)
            println("OPPO_EQ_RESTORED $originalPreset requestId=${latest.operation?.requestId}")
        } finally {
            val target = latest
            val original = originalPreset
            if (target != null && original != null &&
                target.features["EQUALIZER"]?.confirmed != original
            ) {
                runCatching {
                    writeAndAwaitTerminal(
                        context,
                        events,
                        IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, original),
                        target,
                    )
                }
            }
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun verifyCapabilitiesAndReversibleNoiseControlAndLowLatency() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        var latest: HeadphoneSnapshotPayload? = null
        var originalNoiseControl: String? = null
        var originalLowLatency: String? = null
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            latest = requestReady(context, events)
            val initial = requireNotNull(latest)
            assertEquals("oppo", initial.vendorId)
            assertEquals("OPPO Enco Air5s", initial.deviceName)
            assertEquals("CLASSIC_SPP", initial.transport)
            val capabilities = initial.capabilities.associateBy { it.featureId }
            println(
                "OPPO_CAPABILITIES firmware=${initial.firmware} " +
                    "features=${capabilities.values.filter { it.canRead }.map { capability ->
                        "${capability.featureId}(write=${capability.canWrite},allowed=${capability.allowedValues})"
                    }}",
            )

            val noiseCapability = requireNotNull(capabilities["NOISE_CONTROL"])
            assertTrue(noiseCapability.canRead)
            assertTrue(noiseCapability.canWrite)
            originalNoiseControl = requireNotNull(initial.features["NOISE_CONTROL"]?.confirmed)
            if (initial.wearing.values.any { it == "WEARING" }) {
                val alternateNoiseControl = noiseCapability.allowedValues
                    .first { it != originalNoiseControl }
                latest = writeAndAwaitTerminal(
                    context,
                    events,
                    IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        alternateNoiseControl,
                    ),
                    requireNotNull(latest),
                )
                assertEquals(alternateNoiseControl, latest.features["NOISE_CONTROL"]?.confirmed)
                assertTrue(
                    "ANC may be confirmed by the device notification or explicit readback",
                    latest.features["NOISE_CONTROL"]?.source in setOf("NOTIFICATION", "READ_BACK"),
                )
                println("OPPO_ANC_CHANGED $originalNoiseControl->$alternateNoiseControl")

                latest = writeAndAwaitTerminal(
                    context,
                    events,
                    IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        originalNoiseControl,
                    ),
                    requireNotNull(latest),
                )
                assertEquals(originalNoiseControl, latest.features["NOISE_CONTROL"]?.confirmed)
                println("OPPO_ANC_RESTORED $originalNoiseControl")
            } else {
                println("OPPO_ANC_SKIPPED wearing=${initial.wearing} current=$originalNoiseControl")
            }

            val lowLatencyCapability = requireNotNull(capabilities["LOW_LATENCY"])
            assertTrue(lowLatencyCapability.canRead)
            assertTrue(lowLatencyCapability.canWrite)
            originalLowLatency = requireNotNull(initial.features["LOW_LATENCY"]?.confirmed)
            val alternateLowLatency = (!originalLowLatency.toBooleanStrict()).toString()
            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_LOW_LATENCY,
                    alternateLowLatency,
                ),
                requireNotNull(latest),
            )
            assertEquals(alternateLowLatency, latest.features["LOW_LATENCY"]?.confirmed)
            assertEquals("READ_BACK", latest.features["LOW_LATENCY"]?.source)
            println("OPPO_LOW_LATENCY_CHANGED $originalLowLatency->$alternateLowLatency")

            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_LOW_LATENCY,
                    originalLowLatency,
                ),
                requireNotNull(latest),
            )
            assertEquals(originalLowLatency, latest.features["LOW_LATENCY"]?.confirmed)
            println("OPPO_LOW_LATENCY_RESTORED $originalLowLatency")
        } finally {
            latest?.let { snapshot ->
                originalNoiseControl?.takeIf {
                    snapshot.features["NOISE_CONTROL"]?.confirmed != it
                }?.let { original ->
                    runCatching {
                        writeAndAwaitTerminal(
                            context,
                            events,
                            IpcCommandPayload(
                                HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                                original,
                            ),
                            snapshot,
                        )
                    }
                }
                originalLowLatency?.takeIf {
                    snapshot.features["LOW_LATENCY"]?.confirmed != it
                }?.let { original ->
                    runCatching {
                        writeAndAwaitTerminal(
                            context,
                            events,
                            IpcCommandPayload(
                                HeadphoneIpcContract.TYPE_SET_LOW_LATENCY,
                                original,
                            ),
                            snapshot,
                        )
                    }
                }
            }
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun verifyReversibleSpatialSoundSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = snapshotReceiver(events)
        var latest: HeadphoneSnapshotPayload? = null
        var original: String? = null
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )
        try {
            latest = requestReady(context, events)
            val capability = latest.capabilities.single {
                it.featureId == "SPATIAL_SOUND_SWITCH"
            }
            assertTrue(capability.canRead)
            assertTrue(capability.canWrite)
            assertEquals(listOf("false", "true"), capability.allowedValues)
            original = requireNotNull(latest.features["SPATIAL_SOUND_SWITCH"]?.confirmed)
            val alternate = (!original.toBooleanStrict()).toString()

            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_SPATIAL_SOUND_SWITCH,
                    alternate,
                ),
                latest,
            )
            assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
            assertEquals(alternate, latest.features["SPATIAL_SOUND_SWITCH"]?.confirmed)
            assertEquals("READ_BACK", latest.features["SPATIAL_SOUND_SWITCH"]?.source)
            println("OPPO_SPATIAL_SWITCH_CHANGED $original->$alternate")

            latest = writeAndAwaitTerminal(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_SPATIAL_SOUND_SWITCH,
                    original,
                ),
                latest,
            )
            assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
            assertEquals(original, latest.features["SPATIAL_SOUND_SWITCH"]?.confirmed)
            assertEquals("READ_BACK", latest.features["SPATIAL_SOUND_SWITCH"]?.source)
            println("OPPO_SPATIAL_SWITCH_RESTORED $original")
        } finally {
            val snapshot = latest
            val initial = original
            if (snapshot != null && initial != null &&
                snapshot.features["SPATIAL_SOUND_SWITCH"]?.confirmed != initial
            ) {
                runCatching {
                    writeAndAwaitTerminal(
                        context,
                        events,
                        IpcCommandPayload(
                            HeadphoneIpcContract.TYPE_SET_SPATIAL_SOUND_SWITCH,
                            initial,
                        ),
                        snapshot,
                    )
                }
            }
            context.unregisterReceiver(receiver)
        }
    }

    private fun writeAndAwaitTerminal(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        payload: IpcCommandPayload,
        target: HeadphoneSnapshotPayload,
    ): HeadphoneSnapshotPayload = request(context, events, payload, target)

    private fun request(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        payload: IpcCommandPayload,
        target: HeadphoneSnapshotPayload? = null,
    ): HeadphoneSnapshotPayload {
        val requestId = "device-test-${UUID.randomUUID()}"
        println("OPPO_CUSTOM_EQ_REQUEST sending type=${payload.type} id=$requestId")
        context.sendIdentitySharedBroadcast(
            HeadphoneIpcContract.commandIntent(
                command = payload,
                requestId = requestId,
                deviceId = target?.deviceId,
                vendorId = target?.vendorId,
            ),
        )
        println("OPPO_CUSTOM_EQ_REQUEST sent type=${payload.type} id=$requestId")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val event = events.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            println(
                "OPPO_CUSTOM_EQ_REQUEST event expected=$requestId actual=${event.first} " +
                    "phase=${event.second.operation?.phase} source=${event.second.equalizerCurve?.source}",
            )
            val phase = event.second.operation?.phase
            val isWrite = payload.type.startsWith("set_") ||
                payload.type == HeadphoneIpcContract.TYPE_RENAME_EQUALIZER_PRESET ||
                payload.type == HeadphoneIpcContract.TYPE_DELETE_EQUALIZER_PRESET
            val terminal = phase in setOf(
                "STATE_CONFIRMED",
                "READ_BACK_CONFIRMED",
                "FAILED",
                "TIMED_OUT",
                "CANCELLED",
            )
            if (
                event.first == requestId &&
                (!isWrite || (terminal && event.second.operation?.requestId == requestId))
            ) {
                return event.second
            }
        }
        error("timed out waiting for snapshot requestId=$requestId")
    }

    private fun requestReady(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
    ): HeadphoneSnapshotPayload {
        var last: HeadphoneSnapshotPayload? = null
        repeat(30) {
            last = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            if (last?.protocolReady == true) return requireNotNull(last)
            Thread.sleep(500)
        }
        error("OPPO protocol did not become ready; last connection=${last?.connection}")
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

    private fun rfcommLogReceiver(logs: LinkedBlockingQueue<String>) =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != HeadphoneActionContract.ACTION_RFCOMM_LOG) return
                logs.offer("${intent.getStringExtra("tag")}: ${intent.getStringExtra("message")}")
            }
        }

    private fun sendRawForReadback(
        context: Context,
        sessionToken: String,
        frame: ByteArray,
    ) {
        println(
            "OPPO_CUSTOM_EQ_RAW_TX " +
                frame.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
        )
        sendLegacy(context, HeadphoneActionContract.ACTION_RFCOMM_DEBUG_SEND) {
            putExtra("hex", frame.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            putExtra(HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
        }
        // Raw debug writes deliberately do not participate in the session waiter. The subsequent
        // typed 0x0122 refresh is the authoritative device acknowledgement and state confirmation.
        Thread.sleep(300)
    }

    private fun customSlotIds(snapshot: HeadphoneSnapshotPayload): List<String> =
        snapshot.capabilities.single { it.featureId == "EQUALIZER" }.allowedValues
            .filter { it.startsWith("oppo:eq:custom:") }

    private fun slotFromSnapshot(
        snapshot: HeadphoneSnapshotPayload,
        fallbackName: String,
    ): OppoCustomEqualizerSlot {
        val capability = snapshot.capabilities.single { it.featureId == "EQUALIZER" }
        val curve = requireNotNull(snapshot.equalizerCurve?.confirmed)
        val spec = requireNotNull(capability.equalizerCurveSpec)
        return OppoCustomEqualizerSlot(
            selected = true,
            minGain = spec.bands.minOf { it.minGain },
            maxGain = spec.bands.maxOf { it.maxGain },
            eqId = requireNotNull(OppoCustomEqualizerFeature.eqId(curve.slotId)),
            name = capability.valueLabels[curve.slotId] ?: fallbackName,
            frequenciesHz = spec.bands.map { requireNotNull(it.centerFrequencyHz) },
            gains = curve.gains,
        )
    }

    private inline fun sendLegacy(
        context: Context,
        action: String,
        configure: Intent.() -> Unit = {},
    ) {
        context.sendIdentitySharedBroadcast(
            Intent(action).apply {
                setPackage(HeadphoneIpcContract.BLUETOOTH_HOST_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                configure()
            },
        )
    }
}
