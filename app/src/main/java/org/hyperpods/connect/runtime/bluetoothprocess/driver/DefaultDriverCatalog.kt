package org.hyperpods.connect.runtime.bluetoothprocess.driver

import android.content.Context
import moe.chenxy.headphones.engine.DriverRegistry
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoLowLatencyStrategy
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncEncoding
import moe.chenxy.headphones.protocol.oppo.session.OppoDriverProvider
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import moe.chenxy.headphones.protocol.sony.profile.SonyProfile
import moe.chenxy.headphones.protocol.sony.session.SonyDriverProvider
import moe.chenxy.headphones.core.device.VendorId
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.config.CapabilityOverride
import org.hyperpods.connect.pods.GameModeImplementation
import org.hyperpods.connect.utils.OfficialNetworkArtworkCandidate
import moe.chenxy.headphones.transport.android.AndroidGattDeviceResolver

/**
 * The only Android-app composition root that knows which vendor drivers ship in this build.
 * Presentation and session orchestration consume only [DriverRegistry].
 */
object DefaultDriverCatalog {
    fun register(context: Context, registry: DriverRegistry) {
        registry.register(OppoDriverProvider(oppoOverrides(context)))
        registry.register(SonyDriverProvider())
    }

    /** Vendor hints are confined to the composition root; presentation never sees these rules. */
    fun inferVendor(name: String?, advertisedUuids: Set<String>): VendorId? {
        if (advertisedUuids.any {
                it.equals(SonyProfile.SONY_SPP_V1_UUID, ignoreCase = true) ||
                    it.equals(SonyProfile.SONY_SPP_V2_UUID, ignoreCase = true)
            }
        ) return VendorId.SONY
        if (advertisedUuids.any { it.equals(OppoSession.OPPO_SPP_UUID, ignoreCase = true) }) {
            return VendorId.OPPO
        }
        val displayName = name.orEmpty()
        return when {
            displayName.startsWith("WH-", ignoreCase = true) ||
                displayName.startsWith("WF-", ignoreCase = true) ||
                displayName.startsWith("WI-", ignoreCase = true) ||
                displayName.contains("LinkBuds", ignoreCase = true) -> VendorId.SONY
            displayName.contains("oppo", ignoreCase = true) ||
                displayName.contains("oneplus", ignoreCase = true) -> VendorId.OPPO
            else -> null
        }
    }

    fun resolveOfficialArtwork(
        vendorId: String?,
        deviceName: String,
        colorId: String?,
    ): OfficialNetworkArtworkCandidate? = when {
        vendorId.equals(VendorId.SONY.value, ignoreCase = true) ->
            SonyOfficialArtworkCatalog.resolve(deviceName, colorId)
        else -> null
    }

    fun gattDeviceResolver(context: Context): AndroidGattDeviceResolver =
        SonyAutoPlayGattDeviceResolver(context)

    private fun oppoOverrides(context: Context): OppoCompatibilityOverrides {
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val gameModeImplementation = GameModeImplementation.fromPreference(
            prefs.getString(GameModeImplementation.PREF_KEY, null),
        )
        return OppoCompatibilityOverrides(
            adaptiveSupported = ConfigManager.adaptiveCapabilityOverride().asBooleanOverride(),
            spatialAudioSupported = ConfigManager.spatialAudioCapabilityOverride().asBooleanOverride(),
            spatialSoundSwitchSupported =
                ConfigManager.spatialSoundSwitchCapabilityOverride().asBooleanOverride(),
            ancEncoding = when (ConfigManager.ancImplementationCapabilityOverride()) {
                CapabilityOverride.FORCE_ENABLED -> OppoAncEncoding.COMPATIBLE
                CapabilityOverride.FORCE_DISABLED -> OppoAncEncoding.STANDARD
                else -> null
            },
            lowLatencyStrategy = if (gameModeImplementation == GameModeImplementation.COMPATIBLE) {
                OppoLowLatencyStrategy.MAIN_AND_LOW_LATENCY_SWITCH
            } else {
                OppoLowLatencyStrategy.MAIN_SWITCH
            },
        )
    }

    private fun Int.asBooleanOverride(): Boolean? = when (this) {
        CapabilityOverride.FORCE_ENABLED -> true
        CapabilityOverride.FORCE_DISABLED -> false
        else -> null
    }
}
