package moe.chenxy.headphones.protocol.sony.profile

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import moe.chenxy.headphones.core.profile.CompatibilityMatrix
import moe.chenxy.headphones.core.profile.CompatibilityMatrixEntry
import moe.chenxy.headphones.core.transport.GattMtuFailurePolicy
import moe.chenxy.headphones.core.transport.GattPreparationStep
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolInfo
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
import moe.chenxy.headphones.protocol.sony.feature.SonySupportInfo
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryType
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyEqualizerFeature
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyNoiseControlFeature

object SonyProfile {
    val compatibilityMatrix = CompatibilityMatrix(
        listOf(
            CompatibilityMatrixEntry(
                vendorId = VendorId.SONY,
                model = "WH-1000XM4",
                firmware = "2.5.1",
                transport = TransportKind.CLASSIC_SPP,
                level = CompatibilityLevel.STABLE,
                evidence = "Phase 13 SPP reversible controls and 20-cycle stability gate",
            ),
            CompatibilityMatrixEntry(
                vendorId = VendorId.SONY,
                model = "LinkBuds S",
                firmware = "4.2.1",
                transport = TransportKind.BLE_GATT,
                level = CompatibilityLevel.STABLE,
                evidence = "Phase 9-10 two-phone read and reversible-control validation",
            ),
        ),
    )

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

    fun verified(
        initial: DeviceProfile,
        protocolInfo: SonyProtocolInfo,
        model: String,
        firmware: String,
        supportInfo: SonySupportInfo,
        noiseControlReadVerified: Boolean,
        equalizerReadVerified: Boolean,
        equalizerPresetIds: Set<String> = SonyEqualizerFeature.allowedPresetIds,
        equalizerCurveSpec: EqualizerCurveSpec? = null,
        equalizerValueLabels: Map<String, String> = SonyEqualizerFeature.valueLabels,
    ): DeviceProfile {
        val noiseControlWritable = noiseControlReadVerified &&
            isNoiseControlWriteWhitelisted(
                initial.transport,
                protocolInfo,
                model,
                firmware,
                supportInfo,
            )
        val equalizerWritable = equalizerReadVerified &&
            supportsCapabilityGatedEqualizerWrites(
                initial.transport,
                protocolInfo,
            )
        val capabilities = readCapabilities(EvidenceLevel.VERIFIED, initial.transport).toMutableMap()
        if (noiseControlReadVerified) {
            capabilities[FeatureId.NOISE_CONTROL] = FeatureCapability(
                featureId = FeatureId.NOISE_CONTROL,
                canRead = true,
                canWrite = noiseControlWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = setOf("OFF", "NOISE_CANCELLATION", "TRANSPARENCY"),
                source = if (noiseControlWritable) {
                    if (protocolInfo.generation == SonyProtocolGeneration.V1) {
                        "WH-1000XM4 2.5.1 / SPP / table1 capability-gated evidence"
                    } else {
                        "LinkBuds S 4.2.1 / GATT / table1 official-app dynamic evidence"
                    }
                } else {
                    "Sony NC/ASM parameter 0x17 read verified; writes not whitelisted"
                },
            )
            capabilities[FeatureId.AMBIENT_SOUND_LEVEL] = FeatureCapability(
                featureId = FeatureId.AMBIENT_SOUND_LEVEL,
                canRead = true,
                canWrite = noiseControlWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = (
                    SonyNoiseControlFeature.AMBIENT_LEVEL_MIN..
                        SonyNoiseControlFeature.AMBIENT_LEVEL_MAX
                    ).map(Int::toString).toSet(),
                source = if (noiseControlWritable) {
                    if (protocolInfo.generation == SonyProtocolGeneration.V1) {
                        "WH-1000XM4 2.5.1 / SPP / table1 ambient-level device evidence"
                    } else {
                        "LinkBuds S 4.2.1 / GATT / table1 ambient-level dynamic evidence"
                    }
                } else {
                    "Sony NC/ASM ambient level read verified; writes not whitelisted"
                },
            )
            capabilities[FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT] = FeatureCapability(
                featureId = FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
                canRead = true,
                canWrite = noiseControlWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = setOf("false", "true"),
                source = if (noiseControlWritable) {
                    if (protocolInfo.generation == SonyProtocolGeneration.V1) {
                        "WH-1000XM4 2.5.1 / SPP / table1 ambient NORMAL-VOICE device evidence"
                    } else {
                        "LinkBuds S 4.2.1 / GATT / table1 ambient NORMAL-VOICE dynamic evidence"
                    }
                } else {
                    "Sony NC/ASM ambient sub-mode read verified; writes not whitelisted"
                },
            )
        }
        if (equalizerReadVerified) {
            capabilities[FeatureId.EQUALIZER] = FeatureCapability(
                featureId = FeatureId.EQUALIZER,
                canRead = true,
                canWrite = equalizerWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = equalizerPresetIds,
                valueLabels = equalizerValueLabels.filterKeys(equalizerPresetIds::contains),
                equalizerCurveSpec = equalizerCurveSpec,
                source = if (equalizerWritable) {
                    "Sony table1 EQ capability and parameter read verified; official custom-EQ codec"
                } else {
                    "Sony EQEBB parameter read verified; transport/protocol does not support guarded writes"
                },
            )
        }
        return initial.copy(
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
            features = capabilities,
            compatibilityLevel = if (noiseControlWritable || equalizerWritable) {
                compatibilityMatrix.resolve(
                    VendorId.SONY,
                    model,
                    firmware,
                    initial.transport,
                )?.level ?: CompatibilityLevel.CONTROLLED
            } else {
                CompatibilityLevel.READ_ONLY
            },
        )
    }

    fun shouldQueryNoiseControl(
        protocolInfo: SonyProtocolInfo,
        supportInfo: SonySupportInfo,
    ): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V2 &&
            protocolInfo.table1Enabled &&
            SonyNoiseControlFeature.FUNCTION_ID in supportInfo.functions

    fun shouldQueryEqualizer(protocolInfo: SonyProtocolInfo): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V2 && protocolInfo.table1Enabled

    fun shouldProbeV1Controls(
        protocolInfo: SonyProtocolInfo,
        model: String,
        firmware: String,
    ): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V1 &&
            protocolInfo.table1Enabled &&
            model == "WH-1000XM4" &&
            firmware == "2.5.1"

    fun shouldQueryV1Equalizer(protocolInfo: SonyProtocolInfo): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V1 && protocolInfo.table1Enabled

    fun isNoiseControlWriteWhitelisted(
        transportKind: TransportKind,
        protocolInfo: SonyProtocolInfo,
        model: String,
        firmware: String,
        supportInfo: SonySupportInfo,
    ): Boolean = when {
        transportKind == TransportKind.BLE_GATT &&
            model == "LinkBuds S" &&
            firmware == "4.2.1" -> shouldQueryNoiseControl(protocolInfo, supportInfo)
        transportKind == TransportKind.CLASSIC_SPP &&
            model == "WH-1000XM4" &&
            firmware == "2.5.1" -> shouldProbeV1Controls(protocolInfo, model, firmware)
        else -> false
    }

    fun supportsCapabilityGatedEqualizerWrites(
        transportKind: TransportKind,
        protocolInfo: SonyProtocolInfo,
    ): Boolean = protocolInfo.table1Enabled && when (protocolInfo.generation) {
        SonyProtocolGeneration.V1 -> transportKind == TransportKind.CLASSIC_SPP
        SonyProtocolGeneration.V2 ->
            transportKind == TransportKind.CLASSIC_SPP || transportKind == TransportKind.BLE_GATT
    }

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
            source = "Sony official-app static reverse engineering; read verified on device",
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
