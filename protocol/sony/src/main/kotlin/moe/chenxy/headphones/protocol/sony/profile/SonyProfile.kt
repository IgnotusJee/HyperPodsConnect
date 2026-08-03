package moe.chenxy.headphones.protocol.sony.profile

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
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
    private const val V1_BATTERY_LEVEL = 0x11
    private const val V1_LEFT_RIGHT_BATTERY_LEVEL = 0x15
    private const val V1_CRADLE_BATTERY_LEVEL = 0x18
    private const val V2_BATTERY_LEVEL = 0x20
    private const val V2_LEFT_RIGHT_BATTERY_LEVEL = 0x21
    private const val V2_CRADLE_BATTERY_LEVEL = 0x22
    private const val V2_BATTERY_WITH_THRESHOLD = 0x28
    private const val V2_LR_BATTERY_WITH_THRESHOLD = 0x29
    private const val V2_CRADLE_BATTERY_WITH_THRESHOLD = 0x2A

    fun initial(
        candidate: DeviceCandidate,
        transportKind: TransportKind,
        transportLabel: String,
    ): DeviceProfile = DeviceProfile(
        identity = candidate.identity.copy(vendorId = VendorId.SONY),
        vendorId = VendorId.SONY,
        model = candidate.displayName,
        firmware = null,
        topology = DeviceTopology.UNKNOWN,
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
        batteryComponents: Set<BatteryComponent> = emptySet(),
        ambientLevelRange: IntRange? = null,
        transparencyVocalEnhancementSupported: Boolean = false,
    ): DeviceProfile {
        val noiseControlWritable = noiseControlReadVerified &&
            supportsCapabilityGatedNoiseControlWrites(
                initial.transport,
                protocolInfo,
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
                source = "Sony table1 NC/ASM capability and parameter read verified",
            )
        }
        if (noiseControlReadVerified && ambientLevelRange != null) {
            capabilities[FeatureId.AMBIENT_SOUND_LEVEL] = FeatureCapability(
                featureId = FeatureId.AMBIENT_SOUND_LEVEL,
                canRead = true,
                canWrite = noiseControlWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = ambientLevelRange.map(Int::toString).toSet(),
                source = "Sony table1 NC/ASM device-reported ambient range",
            )
        }
        if (noiseControlReadVerified && transparencyVocalEnhancementSupported) {
            capabilities[FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT] = FeatureCapability(
                featureId = FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
                canRead = true,
                canWrite = noiseControlWritable,
                evidence = EvidenceLevel.VERIFIED,
                availableOnTransports = setOf(initial.transport),
                requiresReadback = true,
                allowedValues = setOf("false", "true"),
                source = "Sony table1 NC/ASM NORMAL/VOICE modes verified",
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
            topology = topology(batteryComponents),
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
                CompatibilityLevel.CONTROLLED
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

    fun shouldQueryV1NoiseControl(protocolInfo: SonyProtocolInfo): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V1 && protocolInfo.table1Enabled

    fun shouldQueryV1Equalizer(protocolInfo: SonyProtocolInfo): Boolean =
        protocolInfo.generation == SonyProtocolGeneration.V1 && protocolInfo.table1Enabled

    fun supportsCapabilityGatedNoiseControlWrites(
        transportKind: TransportKind,
        protocolInfo: SonyProtocolInfo,
        supportInfo: SonySupportInfo,
    ): Boolean = protocolInfo.table1Enabled && when (protocolInfo.generation) {
        SonyProtocolGeneration.V1 -> transportKind == TransportKind.CLASSIC_SPP
        SonyProtocolGeneration.V2 ->
            SonyNoiseControlFeature.FUNCTION_ID in supportInfo.functions &&
                (transportKind == TransportKind.CLASSIC_SPP || transportKind == TransportKind.BLE_GATT)
    }

    fun supportsCapabilityGatedEqualizerWrites(
        transportKind: TransportKind,
        protocolInfo: SonyProtocolInfo,
    ): Boolean = protocolInfo.table1Enabled && when (protocolInfo.generation) {
        SonyProtocolGeneration.V1 -> transportKind == TransportKind.CLASSIC_SPP
        SonyProtocolGeneration.V2 ->
            transportKind == TransportKind.CLASSIC_SPP || transportKind == TransportKind.BLE_GATT
    }

    /**
     * Mirrors Sound Connect's capability-table selection without consulting a model name.
     * V1 advertises one-byte function codes; V2 advertises a function code plus version.
     */
    fun batteryTypes(
        protocolInfo: SonyProtocolInfo,
        supportInfo: SonySupportInfo,
    ): List<SonyBatteryType> {
        fun hasV2Function(vararg codes: Int): Boolean = supportInfo.functions.any { function ->
            (function ushr 8) in codes
        }

        return when (protocolInfo.generation) {
            SonyProtocolGeneration.V1 -> buildList {
                when {
                    V1_BATTERY_LEVEL in supportInfo.functions -> add(SonyBatteryType.SINGLE)
                    V1_LEFT_RIGHT_BATTERY_LEVEL in supportInfo.functions ->
                        add(SonyBatteryType.LEFT_RIGHT)
                }
                if (V1_CRADLE_BATTERY_LEVEL in supportInfo.functions) {
                    add(SonyBatteryType.CRADLE)
                }
            }
            SonyProtocolGeneration.V2 -> buildList {
                when {
                    hasV2Function(V2_LEFT_RIGHT_BATTERY_LEVEL, V2_LR_BATTERY_WITH_THRESHOLD) ->
                        add(SonyBatteryType.LEFT_RIGHT)
                    hasV2Function(V2_BATTERY_LEVEL, V2_BATTERY_WITH_THRESHOLD) ->
                        add(SonyBatteryType.SINGLE)
                }
                if (hasV2Function(V2_CRADLE_BATTERY_LEVEL, V2_CRADLE_BATTERY_WITH_THRESHOLD)) {
                    add(SonyBatteryType.CRADLE)
                }
            }
        }
    }

    fun topology(components: Set<BatteryComponent>): DeviceTopology = when {
        BatteryComponent.LEFT in components || BatteryComponent.RIGHT in components ->
            if (BatteryComponent.CASE in components) {
                DeviceTopology.EARBUDS_WITH_CASE
            } else {
                DeviceTopology.EARBUDS_NO_CASE
            }
        else -> DeviceTopology.UNKNOWN
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
