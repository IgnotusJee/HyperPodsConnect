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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual hardware test. Requires LinkBuds S Custom 1 to be active. */
@RunWith(AndroidJUnit4::class)
class SonyCustomEqDeviceTest {
    @Test
    fun modify400HzReadBackAndRestore() {
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

        var initial: HeadphoneSnapshotPayload? = null
        var latest: HeadphoneSnapshotPayload? = null
        try {
            initial = request(
                context,
                events,
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
            )
            assertEquals("sony", initial.vendorId)
            assertEquals("LinkBuds S", initial.deviceName)
            assertTrue(initial.protocolReady)

            val capability = initial.capabilities.single { it.featureId == "EQUALIZER" }
            val spec = requireNotNull(capability.equalizerCurveSpec)
            val original = requireNotNull(initial.equalizerCurve?.confirmed)
            assertEquals("sony:eq:a1", original.slotId)
            assertTrue(original.slotId in spec.writableSlotIds)
            assertEquals(6, spec.bands.size)
            assertEquals(listOf(null, 400, 1_000, 2_500, 6_300, 16_000), spec.bands.map { it.centerFrequencyHz })

            val bandIndex = 1
            val updated = original.copy(
                gains = original.gains.toMutableList().apply {
                    this[bandIndex] = (this[bandIndex] - spec.bands[bandIndex].step)
                        .coerceAtLeast(spec.bands[bandIndex].minGain)
                },
            )
            check(updated != original) { "400 Hz is already at the lower boundary" }
            println("SONY_CUSTOM_EQ_INITIAL slot=${original.slotId} gains=${original.gains}")

            latest = request(
                context,
                events,
                IpcCommandPayload(
                    type = HeadphoneIpcContract.TYPE_SET_EQUALIZER_CURVE,
                    curve = updated,
                ),
                initial,
            )
            assertEquals("READ_BACK_CONFIRMED", latest.operation?.phase)
            assertEquals(updated, latest.equalizerCurve?.confirmed)
            assertEquals("READ_BACK", latest.equalizerCurve?.source)
            println("SONY_CUSTOM_EQ_CHANGED index=$bandIndex gains=${updated.gains}")
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
                    latest ?: initial,
                )
                assertEquals("READ_BACK_CONFIRMED", restored.operation?.phase)
                assertEquals(original, restored.equalizerCurve?.confirmed)
                assertEquals("READ_BACK", restored.equalizerCurve?.source)
                println("SONY_CUSTOM_EQ_RESTORED gains=${original.gains}")
            }
            context.unregisterReceiver(receiver)
        }
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
            if (payload.type == HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT ||
                phase in setOf("READ_BACK_CONFIRMED", "FAILED", "TIMED_OUT", "CANCELLED")
            ) return event.second
        }
        error("timed out waiting for snapshot requestId=$requestId")
    }
}
