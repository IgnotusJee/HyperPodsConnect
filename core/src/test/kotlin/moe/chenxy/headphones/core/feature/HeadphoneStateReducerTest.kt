package moe.chenxy.headphones.core.feature

import moe.chenxy.headphones.core.operation.FeatureCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reducer's contract, expressed as the failures it is meant to prevent.
 *
 * Each case here corresponds to something the previous implementation got wrong
 * or could not express, confirmed against captures from a real Enco Air5s.
 */
class HeadphoneStateReducerTest {

    private val empty = HeadphoneState()

    private fun reduce(vararg updates: StateUpdate) =
        HeadphoneStateReducer.reduceAll(empty, updates.toList())

    @Test
    fun `a write acknowledgement never confirms a value`() {
        val state = reduce(
            StateUpdate.LocalPending(FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY), 1),
            StateUpdate.WriteAcknowledged(FeatureId.NOISE_CONTROL, accepted = true, atMillis = 2),
        )

        // The device said "accepted" and nothing else, so the value is still unknown.
        assertNull(state.noiseControl.confirmed)
        assertEquals(NoiseControlMode.TRANSPARENCY, state.noiseControl.pending)
        assertTrue(state.noiseControl.hasPendingChange)
    }

    @Test
    fun `only a device report confirms and it clears the matching pending`() {
        val state = reduce(
            StateUpdate.LocalPending(FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY), 1),
            StateUpdate.WriteAcknowledged(FeatureId.NOISE_CONTROL, accepted = true, atMillis = 2),
            StateUpdate.DeviceReported(
                DeviceReport.NoiseControl(NoiseControlMode.TRANSPARENCY),
                ValueSource.READ_BACK,
                atMillis = 3,
            ),
        )

        assertEquals(NoiseControlMode.TRANSPARENCY, state.noiseControl.confirmed)
        assertNull(state.noiseControl.pending)
        assertFalse(state.noiseControl.hasPendingChange)
        assertEquals(ValueSource.READ_BACK, state.noiseControl.source)
    }

    @Test
    fun `a readback that disagrees keeps the intent visible`() {
        val state = reduce(
            StateUpdate.LocalPending(FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY), 1),
            StateUpdate.DeviceReported(
                DeviceReport.NoiseControl(NoiseControlMode.NOISE_CANCELLATION_DEEP),
                ValueSource.READ_BACK,
                atMillis = 2,
            ),
        )

        // The write did not take. Silently adopting the device value would hide that.
        assertEquals(NoiseControlMode.NOISE_CANCELLATION_DEEP, state.noiseControl.confirmed)
        assertEquals(NoiseControlMode.TRANSPARENCY, state.noiseControl.pending)
        assertTrue(state.noiseControl.hasPendingChange)
    }

    @Test
    fun `an abandoned write drops the intent without touching the confirmed value`() {
        val state = reduce(
            StateUpdate.DeviceReported(
                DeviceReport.NoiseControl(NoiseControlMode.OFF), ValueSource.NOTIFICATION, 1,
            ),
            StateUpdate.LocalPending(FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY), 2),
            StateUpdate.PendingAbandoned(FeatureId.NOISE_CONTROL, atMillis = 3),
        )

        assertEquals(NoiseControlMode.OFF, state.noiseControl.confirmed)
        assertNull(state.noiseControl.pending)
    }

    @Test
    fun `transparency voice enhancement follows the same pending confirmation rule`() {
        val acknowledged = reduce(
            StateUpdate.LocalPending(
                FeatureCommand.SetTransparencyVocalEnhancement(true),
                1,
            ),
            StateUpdate.WriteAcknowledged(
                FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
                accepted = true,
                atMillis = 2,
            ),
        )
        assertNull(acknowledged.transparencyVocalEnhancement.confirmed)
        assertEquals(true, acknowledged.transparencyVocalEnhancement.pending)

        val confirmed = HeadphoneStateReducer.reduce(
            acknowledged,
            StateUpdate.DeviceReported(
                DeviceReport.TransparencyVocalEnhancement(true),
                ValueSource.NOTIFICATION,
                3,
            ),
        )
        assertEquals(true, confirmed.transparencyVocalEnhancement.confirmed)
        assertNull(confirmed.transparencyVocalEnhancement.pending)
    }

    @Test
    fun `battery components are replaced so a vanished case cannot linger`() {
        val withCase = HeadphoneStateReducer.reduce(
            empty,
            StateUpdate.DeviceReported(
                DeviceReport.Batteries(
                    mapOf(
                        BatteryComponent.LEFT to BatteryState(100, charging = false),
                        BatteryComponent.RIGHT to BatteryState(100, charging = true),
                        BatteryComponent.CASE to BatteryState(40, charging = false),
                    ),
                ),
                ValueSource.NOTIFICATION,
                1,
            ),
        )
        assertEquals(3, withCase.batteries.size)

        // A later report with only the earbuds must not keep the stale case entry.
        val withoutCase = HeadphoneStateReducer.reduce(
            withCase,
            StateUpdate.DeviceReported(
                DeviceReport.Batteries(
                    mapOf(
                        BatteryComponent.LEFT to BatteryState(100, charging = false),
                        BatteryComponent.RIGHT to BatteryState(100, charging = false),
                    ),
                ),
                ValueSource.NOTIFICATION,
                2,
            ),
        )

        assertEquals(setOf(BatteryComponent.LEFT, BatteryComponent.RIGHT), withoutCase.batteries.keys)
    }

    @Test
    fun `disconnect keeps the last values but marks them stale`() {
        val state = reduce(
            StateUpdate.DeviceReported(
                DeviceReport.NoiseControl(NoiseControlMode.NOISE_CANCELLATION_DEEP),
                ValueSource.QUERY_RESPONSE,
                1,
            ),
            StateUpdate.Disconnected(atMillis = 2),
        )

        assertEquals(NoiseControlMode.NOISE_CANCELLATION_DEEP, state.noiseControl.confirmed)
        assertTrue(state.noiseControl.stale)
    }

    @Test
    fun `displayed value prefers the outstanding intent`() {
        val idle = FeatureValue(confirmed = NoiseControlMode.OFF)
        assertEquals(NoiseControlMode.OFF, idle.displayed)

        val pending = idle.withPending(NoiseControlMode.TRANSPARENCY, atMillis = 1)
        assertEquals(NoiseControlMode.TRANSPARENCY, pending.displayed)
        assertEquals(NoiseControlMode.OFF, pending.confirmed)
    }
}
