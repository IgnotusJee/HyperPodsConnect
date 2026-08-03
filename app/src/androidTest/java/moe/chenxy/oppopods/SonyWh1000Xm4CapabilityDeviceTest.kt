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
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.ipc.IpcCommandPayload
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual hardware gate for the V1 SPP capability-driven Sony path. */
@RunWith(AndroidJUnit4::class)
class SonyWh1000Xm4CapabilityDeviceTest {
    @Test
    fun restoreCustomOneEqualizerAndVerify() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                    events.offer(current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot)
                }
            }
        }
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
            context.sendIdentitySharedBroadcast(
                HeadphoneIpcContract.commandIntent(
                    command = IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_EQUALIZER,
                        "sony:eq:a1",
                    ),
                    requestId = "sony-wh-eq-restore-${UUID.randomUUID()}",
                    deviceId = initial.deviceId,
                    vendorId = initial.vendorId,
                ),
            )
            Thread.sleep(2_000)
            val restored = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            assertEquals("sony:eq:a1", restored.features["EQUALIZER"]?.confirmed)
            assertEquals("READ_BACK", restored.features["EQUALIZER"]?.source)
            assertEquals("READ_BACK_CONFIRMED", restored.operation?.phase)
            println("SONY_WH_EQ_FORCED_RESTORED sony:eq:a1")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun restoreNoiseCancellationAndVerify() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                    events.offer(current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot)
                }
            }
        }
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
            val requestId = "sony-wh-restore-${UUID.randomUUID()}"
            context.sendIdentitySharedBroadcast(
                HeadphoneIpcContract.commandIntent(
                    command = IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        "NOISE_CANCELLATION",
                    ),
                    requestId = requestId,
                    deviceId = initial.deviceId,
                    vendorId = initial.vendorId,
                ),
            )
            Thread.sleep(2_000)
            val restored = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            assertEquals("NOISE_CANCELLATION", restored.features["NOISE_CONTROL"]?.confirmed)
            assertEquals("READ_BACK", restored.features["NOISE_CONTROL"]?.source)
            assertEquals("READ_BACK_CONFIRMED", restored.operation?.phase)
            println("SONY_WH_ANC_FORCED_RESTORED NOISE_CANCELLATION")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun readCurrentSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                    events.offer(current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot)
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
            println(
                "SONY_WH_CURRENT ready=${snapshot.protocolReady} anc=" +
                    "${snapshot.features["NOISE_CONTROL"]?.confirmed} eq=" +
                    "${snapshot.features["EQUALIZER"]?.confirmed} topology=${snapshot.topology} " +
                    "batteries=${snapshot.batteries} operation=${snapshot.operation}",
            )
            assertEquals("WH-1000XM4", snapshot.deviceName)
            assertTrue(snapshot.protocolReady)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun verifyCapabilitiesAndReversibleAncAndEqPreset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val current = intent ?: return
                HeadphoneIpcContract.decodeSnapshot(current)?.let { snapshot ->
                    events.offer(current.getStringExtra(HeadphoneIpcContract.EXTRA_REQUEST_ID) to snapshot)
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT),
            Context.RECEIVER_EXPORTED,
        )

        var latest: HeadphoneSnapshotPayload? = null
        var originalNoiseControl: String? = null
        var originalEqualizer: String? = null
        try {
            latest = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            val initial = latest
            assertEquals("sony", initial.vendorId)
            assertEquals("WH-1000XM4", initial.deviceName)
            assertTrue(initial.protocolReady)
            assertEquals("CLASSIC_SPP", initial.transport)

            val capabilities = initial.capabilities.associateBy { it.featureId }
            listOf(
                "NOISE_CONTROL",
                "AMBIENT_SOUND_LEVEL",
                "TRANSPARENCY_VOCAL_ENHANCEMENT",
                "EQUALIZER",
            ).forEach { featureId ->
                val capability = requireNotNull(capabilities[featureId])
                assertTrue("$featureId must be readable", capability.canRead)
                assertTrue("$featureId must be writable", capability.canWrite)
                assertEquals("VERIFIED", capability.evidence)
            }
            println(
                "SONY_WH_CAPABILITIES firmware=${initial.firmware} " +
                    "features=${capabilities.values.filter { it.canRead }.map { it.featureId }}",
            )

            val noiseCapability = requireNotNull(capabilities["NOISE_CONTROL"])
            originalNoiseControl = requireNotNull(initial.features["NOISE_CONTROL"]?.confirmed)
            val alternateNoiseControl = if (originalNoiseControl == "OFF") {
                "NOISE_CANCELLATION"
            } else {
                "OFF"
            }
            assertTrue(alternateNoiseControl in noiseCapability.allowedValues)
            latest = writeAndReadBack(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                    alternateNoiseControl,
                ),
                latest,
            )
            assertReadBack(latest, "NOISE_CONTROL", alternateNoiseControl)
            println("SONY_WH_ANC_CHANGED $originalNoiseControl->$alternateNoiseControl")

            latest = writeAndReadBack(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                    originalNoiseControl,
                ),
                latest,
            )
            assertReadBack(latest, "NOISE_CONTROL", originalNoiseControl)
            println("SONY_WH_ANC_RESTORED $originalNoiseControl")

            val equalizerCapability = requireNotNull(capabilities["EQUALIZER"])
            originalEqualizer = requireNotNull(initial.features["EQUALIZER"]?.confirmed)
            val alternateEqualizer = equalizerCapability.allowedValues
                .firstOrNull { it != originalEqualizer }
                ?: error("no alternate EQ preset advertised")
            latest = writeAndReadBack(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_EQUALIZER,
                    alternateEqualizer,
                ),
                latest,
            )
            assertReadBack(latest, "EQUALIZER", alternateEqualizer)
            println("SONY_WH_EQ_CHANGED $originalEqualizer->$alternateEqualizer")

            latest = writeAndReadBack(
                context,
                events,
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_EQUALIZER,
                    originalEqualizer,
                ),
                latest,
            )
            assertReadBack(latest, "EQUALIZER", originalEqualizer)
            println("SONY_WH_EQ_RESTORED $originalEqualizer")
        } finally {
            val target = latest
            if (target != null && originalNoiseControl != null &&
                target.features["NOISE_CONTROL"]?.confirmed != originalNoiseControl
            ) {
                writeAndReadBack(
                    context,
                    events,
                    IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                        originalNoiseControl,
                    ),
                    target,
                )
            }
            if (target != null && originalEqualizer != null &&
                target.features["EQUALIZER"]?.confirmed != originalEqualizer
            ) {
                writeAndReadBack(
                    context,
                    events,
                    IpcCommandPayload(
                        HeadphoneIpcContract.TYPE_SET_EQUALIZER,
                        originalEqualizer,
                    ),
                    target,
                )
            }
            context.unregisterReceiver(receiver)
        }
    }

    private fun assertReadBack(
        snapshot: HeadphoneSnapshotPayload,
        featureId: String,
        expected: String,
    ) {
        assertEquals("READ_BACK_CONFIRMED", snapshot.operation?.phase)
        assertEquals(expected, snapshot.features[featureId]?.confirmed)
        assertEquals("READ_BACK", snapshot.features[featureId]?.source)
    }

    private fun writeAndReadBack(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        payload: IpcCommandPayload,
        target: HeadphoneSnapshotPayload,
    ): HeadphoneSnapshotPayload {
        context.sendIdentitySharedBroadcast(
            HeadphoneIpcContract.commandIntent(
                command = payload,
                requestId = "sony-wh-write-${UUID.randomUUID()}",
                deviceId = target.deviceId,
                vendorId = target.vendorId,
            ),
        )
        Thread.sleep(1_500)
        return request(
            context,
            events,
            IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
        )
    }

    private fun request(
        context: Context,
        events: LinkedBlockingQueue<Pair<String?, HeadphoneSnapshotPayload>>,
        payload: IpcCommandPayload,
        target: HeadphoneSnapshotPayload? = null,
    ): HeadphoneSnapshotPayload {
        val requestId = "sony-wh-device-test-${UUID.randomUUID()}"
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
            if (payload.type == HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT ||
                phase in setOf("READ_BACK_CONFIRMED", "FAILED", "TIMED_OUT", "CANCELLED")
            ) return event.second
        }
        error("timed out waiting for snapshot requestId=$requestId")
    }
}
