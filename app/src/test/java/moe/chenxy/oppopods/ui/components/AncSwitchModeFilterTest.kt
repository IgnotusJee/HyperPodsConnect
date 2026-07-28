package moe.chenxy.oppopods.ui.components

import moe.chenxy.headphones.core.feature.NoiseControlMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AncSwitchModeFilterTest {
    @Test
    fun `Sony three-state profile hides unverified strength controls`() {
        val sonyModes = setOf(
            NoiseControlMode.OFF,
            NoiseControlMode.NOISE_CANCELLATION,
            NoiseControlMode.TRANSPARENCY,
        )

        assertEquals(
            emptyList<NoiseControlMode>(),
            supportedNoiseCancellationStrengthModes(sonyModes),
        )
    }

    @Test
    fun `strength controls preserve profile order and filter unsupported values`() {
        val availableModes = setOf(
            NoiseControlMode.NOISE_CANCELLATION_DEEP,
            NoiseControlMode.NOISE_CANCELLATION_SMART,
        )

        assertEquals(
            listOf(
                NoiseControlMode.NOISE_CANCELLATION_SMART,
                NoiseControlMode.NOISE_CANCELLATION_DEEP,
            ),
            supportedNoiseCancellationStrengthModes(availableModes),
        )
    }
}
