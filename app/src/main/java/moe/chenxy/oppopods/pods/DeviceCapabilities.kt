package moe.chenxy.oppopods.pods

private val ADAPTIVE_SUPPORTED_DEVICES = arrayOf(
    "OPPO Enco Free4",
)

private val SPATIAL_AUDIO_SUPPORTED_DEVICES = arrayOf(
    "OPPO Enco X3",
)

private val SPATIAL_SOUND_SWITCH_SUPPORTED_DEVICES = arrayOf(
    "OPPO Enco Free4",
    "OPPO Enco Air5",
)

private val LEGACY_ANC_DEVICES = arrayOf(
    "OPPO Enco Air2 Pro",
)

data class DeviceCapabilities(
    val adaptiveSupported: Boolean,
    val spatialAudioSupported: Boolean,
    val spatialSoundSwitchSupported: Boolean,
    val ancImplementation: AncImplementation,
)

/**
 * Wire/persistence values currently shared with ConfigManager.
 *
 * Keeping the deterministic capability matcher Android-free lets Phase 0 run
 * its regression tests without constructing preferences or Android context.
 */
object DeviceCapabilityOverride {
    const val AUTO = 0
    const val FORCE_ENABLED = 1
    const val FORCE_DISABLED = 2
}

fun detectDeviceCapabilities(
    deviceName: String,
    adaptiveOverride: Int = DeviceCapabilityOverride.AUTO,
    spatialAudioOverride: Int = DeviceCapabilityOverride.AUTO,
    spatialSoundSwitchOverride: Int = DeviceCapabilityOverride.AUTO,
    ancImplementationOverride: Int = DeviceCapabilityOverride.AUTO,
): DeviceCapabilities {
    return DeviceCapabilities(
        adaptiveSupported = resolveCapability(
            override = adaptiveOverride,
            autoDetected = isAdaptiveSupportedByName(deviceName),
        ),
        spatialAudioSupported = resolveCapability(
            override = spatialAudioOverride,
            autoDetected = isSpatialAudioSupportedByName(deviceName),
        ),
        spatialSoundSwitchSupported = resolveCapability(
            override = spatialSoundSwitchOverride,
            autoDetected = isSpatialSoundSwitchSupportedByName(deviceName),
        ),
        ancImplementation = resolveAncImplementation(
            override = ancImplementationOverride,
            autoDetected = isLegacyAncDeviceByName(deviceName)
        )
    )
}

fun isAdaptiveSupportedByName(deviceName: String): Boolean {
    return isDeviceInCapabilityList(deviceName, ADAPTIVE_SUPPORTED_DEVICES)
}

fun isSpatialAudioSupportedByName(deviceName: String): Boolean {
    return isDeviceInCapabilityList(deviceName, SPATIAL_AUDIO_SUPPORTED_DEVICES)
}

fun isSpatialSoundSwitchSupportedByName(deviceName: String): Boolean {
    return isDeviceInCapabilityList(deviceName, SPATIAL_SOUND_SWITCH_SUPPORTED_DEVICES)
}

fun isLegacyAncDeviceByName(deviceName: String): Boolean {
    return isDeviceInCapabilityList(deviceName, LEGACY_ANC_DEVICES)
}

private fun resolveCapability(override: Int, autoDetected: Boolean): Boolean {
    return when (override) {
        DeviceCapabilityOverride.FORCE_ENABLED -> true
        DeviceCapabilityOverride.FORCE_DISABLED -> false
        else -> autoDetected
    }
}

private fun resolveAncImplementation(override: Int, autoDetected: Boolean): AncImplementation {
    return when (override) {
        DeviceCapabilityOverride.FORCE_ENABLED -> AncImplementation.COMPATIBLE
        DeviceCapabilityOverride.FORCE_DISABLED -> AncImplementation.STANDARD
        else -> if (autoDetected) AncImplementation.COMPATIBLE else AncImplementation.STANDARD
    }
}

private fun normalizeDeviceName(deviceName: String): String {
    return deviceName.lowercase().filter { it.isLetterOrDigit() }
}

private fun isDeviceInCapabilityList(deviceName: String, supportedDevices: Array<String>): Boolean {
    val normalizedName = normalizeDeviceName(deviceName)
    if (normalizedName.isEmpty()) return false

    return supportedDevices.any { supportedDevice ->
        val normalizedSupportedDevice = normalizeDeviceName(supportedDevice)
        normalizedSupportedDevice.isNotEmpty() && normalizedSupportedDevice in normalizedName
    }
}
