package moe.chenxy.headphones.protocol.sony.profile

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolInfo
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryType

/**
 * Phase 8 safety profile. Sony writes stay disabled even when a read is
 * verified; control commands need separate per-model dynamic evidence.
 */
object SonyProfile {
    fun initial(candidate: DeviceCandidate, serviceUuid: String): DeviceProfile = DeviceProfile(
        identity = candidate.identity.copy(vendorId = VendorId.SONY),
        vendorId = VendorId.SONY,
        model = candidate.displayName,
        firmware = null,
        topology = topology(candidate.displayName),
        transport = TransportKind.CLASSIC_SPP,
        protocol = ProtocolDescriptor(
            name = "Sony Tandem/MDR",
            commandTable = transportLabel(serviceUuid),
        ),
        features = readCapabilities(EvidenceLevel.ASSUMED),
        compatibilityLevel = CompatibilityLevel.DETECTED,
    )

    fun readOnly(
        initial: DeviceProfile,
        protocolInfo: SonyProtocolInfo,
        model: String,
        firmware: String,
    ): DeviceProfile = initial.copy(
        model = model,
        firmware = firmware,
        topology = topology(model),
        protocol = initial.protocol.copy(
            version = protocolInfo.version?.toString()
                ?: protocolInfo.generation.name.lowercase(),
            commandTable = buildString {
                append("table1")
                if (protocolInfo.table2Enabled) append("+table2")
            },
        ),
        features = readCapabilities(EvidenceLevel.VERIFIED),
        compatibilityLevel = CompatibilityLevel.READ_ONLY,
    )

    fun batteryTypes(model: String?): List<SonyBatteryType> = when (topology(model)) {
        DeviceTopology.EARBUDS_WITH_CASE ->
            listOf(SonyBatteryType.LEFT_RIGHT, SonyBatteryType.CRADLE)
        else -> listOf(SonyBatteryType.SINGLE)
    }

    fun topology(model: String?): DeviceTopology {
        val normalized = model.orEmpty().uppercase()
        return when {
            normalized.startsWith("WF-") || "LINKBUDS" in normalized ->
                DeviceTopology.EARBUDS_WITH_CASE
            normalized.startsWith("WI-") -> DeviceTopology.NECKBAND
            normalized.startsWith("WH-") -> DeviceTopology.HEADBAND
            else -> DeviceTopology.UNKNOWN
        }
    }

    private fun readCapabilities(evidence: EvidenceLevel) = setOf(
        FeatureId.BATTERY,
        FeatureId.FIRMWARE_VERSION,
    ).associateWith { feature ->
        FeatureCapability(
            featureId = feature,
            canRead = true,
            canWrite = false,
            evidence = evidence,
            availableOnTransports = setOf(TransportKind.CLASSIC_SPP),
            requiresReadback = false,
            source = "Sony official-app static reverse engineering; writes disabled in Phase 8",
        )
    }

    private fun transportLabel(serviceUuid: String): String =
        if (serviceUuid.equals(SONY_SPP_V2_UUID, ignoreCase = true)) "transport-v2" else "transport-v1"

    const val SONY_SPP_V1_UUID = "96CC203E-5068-46AD-B32D-E316F5E069BA"
    const val SONY_SPP_V2_UUID = "956C7B26-D49A-4BA8-B03F-B17D393CB6E2"
}
