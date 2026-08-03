package moe.chenxy.oppopods.pods

import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityRegistry
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncEncoding

data class DeviceCapabilities(
    val adaptiveSupported: Boolean,
    val spatialAudioSupported: Boolean,
    val spatialSoundSwitchSupported: Boolean,
    val ancImplementation: AncImplementation,
)

/** Wire/persistence values shared with ConfigManager and the existing UI. */
object DeviceCapabilityOverride {
    const val AUTO = 0
    const val FORCE_ENABLED = 1
    const val FORCE_DISABLED = 2
}

/**
 * Legacy adapter for explicit user overrides. Automatic discovery belongs to
 * the live protocol session and never consumes a Bluetooth model name.
 */
fun detectDeviceCapabilities(
    adaptiveOverride: Int = DeviceCapabilityOverride.AUTO,
    spatialAudioOverride: Int = DeviceCapabilityOverride.AUTO,
    spatialSoundSwitchOverride: Int = DeviceCapabilityOverride.AUTO,
    ancImplementationOverride: Int = DeviceCapabilityOverride.AUTO,
): DeviceCapabilities {
    val profile = OppoCompatibilityRegistry.resolve(
        OppoCompatibilityOverrides(
            adaptiveSupported = adaptiveOverride.asBooleanOverride(),
            spatialAudioSupported = spatialAudioOverride.asBooleanOverride(),
            spatialSoundSwitchSupported = spatialSoundSwitchOverride.asBooleanOverride(),
            ancEncoding = when (ancImplementationOverride) {
                DeviceCapabilityOverride.FORCE_ENABLED -> OppoAncEncoding.COMPATIBLE
                DeviceCapabilityOverride.FORCE_DISABLED -> OppoAncEncoding.STANDARD
                else -> null
            },
        ),
    )
    return DeviceCapabilities(
        adaptiveSupported = profile.adaptiveSupported,
        spatialAudioSupported = profile.spatialAudioSupported,
        spatialSoundSwitchSupported = profile.spatialSoundSwitchSupported,
        ancImplementation = if (profile.ancEncoding == OppoAncEncoding.COMPATIBLE) {
            AncImplementation.COMPATIBLE
        } else {
            AncImplementation.STANDARD
        },
    )
}

private fun Int.asBooleanOverride(): Boolean? = when (this) {
    DeviceCapabilityOverride.FORCE_ENABLED -> true
    DeviceCapabilityOverride.FORCE_DISABLED -> false
    else -> null
}
