package moe.chenxy.oppopods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import moe.chenxy.oppopods.ipc.EqualizerCurvePayload
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.ipc.IpcCommandPayload
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
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
            sendLegacy(context, LegacyPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK) {
                putExtra(LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
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
                assertEquals("READ_BACK_CONFIRMED", target?.operation?.phase)
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
            assertEquals("READ_BACK_CONFIRMED", target?.operation?.phase)
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
                if (deleted?.operation?.phase != "READ_BACK_CONFIRMED" ||
                    customSlotIds(deleted).toSet() != preservedCustomIds
                ) {
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
                    assertEquals("READ_BACK_CONFIRMED", restoredSelection.operation?.phase)
                    assertEquals(presetId, restoredSelection.features["EQUALIZER"]?.confirmed)
                    originalCurve?.takeIf { it.slotId == presetId }?.let { curve ->
                        assertEquals(curve, restoredSelection.equalizerCurve?.confirmed)
                    }
                    println("OPPO_CUSTOM_EQ_SELECTION_RESTORED preset=$presetId")
                }
            }
            if (rawUnlocked) {
                sendLegacy(context, LegacyPodsAction.ACTION_RFCOMM_DEBUG_LOCK) {
                    putExtra(LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
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
                if (intent?.action != LegacyPodsAction.ACTION_RFCOMM_LOG) return
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
            IntentFilter(LegacyPodsAction.ACTION_RFCOMM_LOG),
            Context.RECEIVER_EXPORTED,
        )
        try {
            context.sendIdentitySharedBroadcast(
                Intent(LegacyPodsAction.ACTION_RFCOMM_LOG_CONNECT).apply {
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
                Intent(LegacyPodsAction.ACTION_RFCOMM_LOG_DISCONNECT).apply {
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
            if (event.first == requestId) {
                val phase = event.second.operation?.phase
                val isWrite = payload.type.startsWith("set_") ||
                    payload.type == HeadphoneIpcContract.TYPE_RENAME_EQUALIZER_PRESET ||
                    payload.type == HeadphoneIpcContract.TYPE_DELETE_EQUALIZER_PRESET
                val curveReadBack = payload.curve != null &&
                    event.second.equalizerCurve?.confirmed == payload.curve &&
                    event.second.equalizerCurve?.source == "READ_BACK"
                val terminal = phase in setOf(
                    "STATE_CONFIRMED",
                    "READ_BACK_CONFIRMED",
                    "FAILED",
                    "TIMED_OUT",
                    "CANCELLED",
                )
                if (!isWrite || terminal || curveReadBack) return event.second
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
                if (intent?.action != LegacyPodsAction.ACTION_RFCOMM_LOG) return
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
        sendLegacy(context, LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND) {
            putExtra("hex", frame.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            putExtra(LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN, sessionToken)
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
