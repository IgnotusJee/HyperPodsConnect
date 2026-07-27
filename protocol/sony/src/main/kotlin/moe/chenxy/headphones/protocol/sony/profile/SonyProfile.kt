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
import moe.chenxy.headphones.core.transport.GattMtuFailurePolicy
import moe.chenxy.headphones.core.transport.GattPreparationStep
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolInfo
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryType

/**
 * Phase 8 safety profile. Sony writes stay disabled even when a read is
 * verified; control commands need separate per-model dynamic evidence.
 */
object SonyProfile {
    fun initial(
        candidate: DeviceCandidate,
        transportKind: TransportKind,
        transportLabel: String,
    ): DeviceProfile = DeviceProfile(
        identity = candidate.identity.copy(vendorId = VendorId.SONY),
        vendorId = VendorId.SONY,
        model = candidate.displayName,
        firmware = null,
        topology = topology(candidate.displayName),
        transport = transportKind,
        protocol = ProtocolDescriptor(
            name = "Sony Tandem/MDR",
            commandTable = transportLabel,
        ),
        features = readCapabilities(EvidenceLevel.ASSUMED, transportKind),
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
        features = readCapabilities(EvidenceLevel.VERIFIED, initial.transport),
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

    fun gattSpec(): TransportSpec.Gatt = TransportSpec.Gatt(
        serviceUuid = SONY_GATT_TANDEM_V2_HPC_SERVICE_UUID,
        txCharacteristicUuid = SONY_GATT_TANDEM_TO_ACCESSORY_UUID,
        rxCharacteristicUuid = SONY_GATT_TANDEM_FROM_ACCESSORY_UUID,
        cccdUuid = GATT_CCCD_UUID,
        preparationSteps = listOf(
            GattPreparationStep.Subscribe(
                characteristicUuid = SONY_GATT_DETERMINE_MTU_UUID,
                cccdUuid = GATT_CCCD_UUID,
            ),
            GattPreparationStep.ReadWritableLength(
                characteristicUuid = SONY_GATT_WRITABLE_VALUE_LENGTH_UUID,
            ),
        ),
        requestMtu = 517,
        writeMode = GattWriteMode.WITHOUT_RESPONSE,
        mtuFailurePolicy = GattMtuFailurePolicy.CONTINUE_WITH_DEFAULT,
    )

    private fun readCapabilities(
        evidence: EvidenceLevel,
        transportKind: TransportKind,
    ) = setOf(
        FeatureId.BATTERY,
        FeatureId.FIRMWARE_VERSION,
    ).associateWith { feature ->
        FeatureCapability(
            featureId = feature,
            canRead = true,
            canWrite = false,
            evidence = evidence,
            availableOnTransports = setOf(transportKind),
            requiresReadback = false,
            source = "Sony official-app static reverse engineering; writes disabled in Phase 9",
        )
    }

    fun sppTransportLabel(serviceUuid: String): String =
        if (serviceUuid.equals(SONY_SPP_V2_UUID, ignoreCase = true)) "transport-v2" else "transport-v1"

    const val SONY_SPP_V1_UUID = "96CC203E-5068-46AD-B32D-E316F5E069BA"
    const val SONY_SPP_V2_UUID = "956C7B26-D49A-4BA8-B03F-B17D393CB6E2"
    const val SONY_GATT_TANDEM_V2_HPC_SERVICE_UUID =
        "5B833E20-6BC7-4802-8E9A-723CECA4BD8F"
    const val SONY_GATT_TANDEM_TO_ACCESSORY_UUID =
        "5B833C60-6BC7-4802-8E9A-723CECA4BD8F"
    const val SONY_GATT_TANDEM_FROM_ACCESSORY_UUID =
        "5B833C61-6BC7-4802-8E9A-723CECA4BD8F"
    const val SONY_GATT_WRITABLE_VALUE_LENGTH_UUID =
        "5B833C91-6BC7-4802-8E9A-723CECA4BD8F"
    const val SONY_GATT_DETERMINE_MTU_UUID =
        "5B833C93-6BC7-4802-8E9A-723CECA4BD8F"
    const val GATT_CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"
}
