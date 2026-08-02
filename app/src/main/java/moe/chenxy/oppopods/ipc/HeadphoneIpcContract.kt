package moe.chenxy.oppopods.ipc

import android.content.Intent
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.canExposeState
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot

object HeadphoneIpcContract {
    const val VERSION = 3
    const val BLUETOOTH_HOST_PACKAGE = "com.android.bluetooth"
    const val MODULE_PACKAGE = "moe.chenxy.oppopods"
    const val MILINK_PACKAGE = "com.milink.service"
    const val XIAOMI_BLUETOOTH_PACKAGE = "com.xiaomi.bluetooth"
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val ACTION_HEADPHONE_COMMAND = "moe.chenxy.oppopods.action.HEADPHONE_COMMAND_V3"
    const val ACTION_HEADPHONE_EVENT = "moe.chenxy.oppopods.action.HEADPHONE_EVENT_V3"

    const val EXTRA_CONTRACT_VERSION = "contract_version"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_VENDOR_ID = "vendor_id"
    const val EXTRA_TYPE = "type"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
    const val EXTRA_TIMESTAMP = "timestamp"

    const val TYPE_REQUEST_SNAPSHOT = "request_snapshot"
    const val TYPE_REFRESH_ALL = "refresh_all"
    const val TYPE_REFRESH_FEATURE = "refresh_feature"
    const val TYPE_SET_NOISE_CONTROL = "set_noise_control"
    const val TYPE_SET_AMBIENT_SOUND_LEVEL = "set_ambient_sound_level"
    const val TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT =
        "set_transparency_vocal_enhancement"
    const val TYPE_SET_EQUALIZER = "set_equalizer"
    const val TYPE_SET_LOW_LATENCY = "set_low_latency"
    const val TYPE_SET_SPATIAL_AUDIO = "set_spatial_audio"
    const val TYPE_SET_SPATIAL_SOUND_SWITCH = "set_spatial_sound_switch"
    const val TYPE_SET_DUAL_DEVICE = "set_dual_device"
    const val TYPE_SNAPSHOT = "snapshot"

    val eventTargets = listOf(
        MODULE_PACKAGE,
        MILINK_PACKAGE,
        XIAOMI_BLUETOOTH_PACKAGE,
        SETTINGS_PACKAGE,
    )

    val trustedClientPackages: Set<String> = eventTargets.toSet()

    fun commandIntent(
        command: IpcCommandPayload,
        requestId: String = UUID.randomUUID().toString(),
        deviceId: String? = null,
        vendorId: String? = null,
        targetPackage: String = BLUETOOTH_HOST_PACKAGE,
        timestamp: Long = System.currentTimeMillis(),
    ): Intent = Intent(ACTION_HEADPHONE_COMMAND).apply {
        setPackage(targetPackage)
        putExtra(EXTRA_CONTRACT_VERSION, VERSION)
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_DEVICE_ID, deviceId)
        putExtra(EXTRA_VENDOR_ID, vendorId)
        putExtra(EXTRA_TYPE, command.type)
        putExtra(EXTRA_PAYLOAD_JSON, HeadphoneIpcCodec.encodeCommand(command))
        putExtra(EXTRA_TIMESTAMP, timestamp)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }

    fun eventIntent(
        snapshot: HeadphoneSnapshotPayload,
        requestId: String?,
        targetPackage: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Intent = Intent(ACTION_HEADPHONE_EVENT).apply {
        setPackage(targetPackage)
        putExtra(EXTRA_CONTRACT_VERSION, VERSION)
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_DEVICE_ID, snapshot.deviceId)
        putExtra(EXTRA_VENDOR_ID, snapshot.vendorId)
        putExtra(EXTRA_TYPE, TYPE_SNAPSHOT)
        putExtra(EXTRA_PAYLOAD_JSON, HeadphoneIpcCodec.encodeSnapshot(snapshot))
        putExtra(EXTRA_TIMESTAMP, timestamp)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }

    fun decodeCommand(intent: Intent): IpcCommandEnvelope? {
        if (
            intent.action != ACTION_HEADPHONE_COMMAND ||
            intent.getIntExtra(EXTRA_CONTRACT_VERSION, -1) != VERSION
        ) return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank)
            ?: return null
        val payload = intent.getStringExtra(EXTRA_PAYLOAD_JSON)
            ?.let(HeadphoneIpcCodec::decodeCommand)
            ?: return null
        if (intent.getStringExtra(EXTRA_TYPE) != payload.type) return null
        return IpcCommandEnvelope(
            requestId = requestId,
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID),
            vendorId = intent.getStringExtra(EXTRA_VENDOR_ID),
            timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0),
            payload = payload,
        )
    }

    fun decodeSnapshot(intent: Intent): HeadphoneSnapshotPayload? {
        if (
            intent.action != ACTION_HEADPHONE_EVENT ||
            intent.getIntExtra(EXTRA_CONTRACT_VERSION, -1) != VERSION ||
            intent.getStringExtra(EXTRA_TYPE) != TYPE_SNAPSHOT
        ) return null
        return intent.getStringExtra(EXTRA_PAYLOAD_JSON)
            ?.let(HeadphoneIpcCodec::decodeSnapshot)
            ?.takeIf {
                it.contractVersion == VERSION &&
                    it.hostInstanceId.isNotBlank()
            }
    }
}

@Serializable
enum class IpcConnectionState {
    @SerialName("IDLE")
    IDLE,
    @SerialName("DETECTING")
    DETECTING,
    @SerialName("TRANSPORT_CONNECTING")
    TRANSPORT_CONNECTING,
    @SerialName("PROTOCOL_HANDSHAKING")
    PROTOCOL_HANDSHAKING,
    @SerialName("LOADING_CAPABILITIES")
    LOADING_CAPABILITIES,
    @SerialName("SYNCHRONIZING_STATE")
    SYNCHRONIZING_STATE,
    @SerialName("READY")
    READY,
    @SerialName("RECONNECTING")
    RECONNECTING,
    @SerialName("DISCONNECTING")
    DISCONNECTING,
    @SerialName("FAILED")
    FAILED,
}

@Serializable
data class IpcCommandPayload(
    val type: String,
    val value: String? = null,
) {
    fun toFeatureCommand(): FeatureCommand? = when (type) {
        HeadphoneIpcContract.TYPE_REFRESH_ALL -> FeatureCommand.RefreshAll
        HeadphoneIpcContract.TYPE_REFRESH_FEATURE ->
            value?.let { runCatching { FeatureId.valueOf(it) }.getOrNull() }
                ?.let(FeatureCommand::Refresh)
        HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL ->
            value?.let { runCatching { NoiseControlMode.valueOf(it) }.getOrNull() }
                ?.let(FeatureCommand::SetNoiseControl)
        HeadphoneIpcContract.TYPE_SET_AMBIENT_SOUND_LEVEL ->
            value?.toIntOrNull()?.let(FeatureCommand::SetAmbientSoundLevel)
        HeadphoneIpcContract.TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT ->
            value?.toBooleanStrictOrNull()
                ?.let(FeatureCommand::SetTransparencyVocalEnhancement)
        HeadphoneIpcContract.TYPE_SET_EQUALIZER ->
            value?.takeIf(String::isNotBlank)
                ?.let(::EqualizerPreset)
                ?.let(FeatureCommand::SetEqualizerPreset)
        HeadphoneIpcContract.TYPE_SET_LOW_LATENCY ->
            value?.toBooleanStrictOrNull()?.let(FeatureCommand::SetLowLatency)
        HeadphoneIpcContract.TYPE_SET_SPATIAL_AUDIO ->
            value?.let { runCatching { SpatialAudioMode.valueOf(it) }.getOrNull() }
                ?.let(FeatureCommand::SetSpatialAudio)
        HeadphoneIpcContract.TYPE_SET_SPATIAL_SOUND_SWITCH ->
            value?.toBooleanStrictOrNull()?.let(FeatureCommand::SetSpatialSoundSwitch)
        HeadphoneIpcContract.TYPE_SET_DUAL_DEVICE ->
            value?.toBooleanStrictOrNull()?.let(FeatureCommand::SetDualDeviceConnection)
        else -> null
    }

    companion object {
        fun from(command: FeatureCommand): IpcCommandPayload = when (command) {
            FeatureCommand.RefreshAll ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REFRESH_ALL)
            is FeatureCommand.Refresh ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REFRESH_FEATURE, command.featureId.name)
            is FeatureCommand.SetNoiseControl ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL, command.mode.name)
            is FeatureCommand.SetAmbientSoundLevel ->
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_AMBIENT_SOUND_LEVEL,
                    command.level.toString(),
                )
            is FeatureCommand.SetTransparencyVocalEnhancement ->
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT,
                    command.enabled.toString(),
                )
            is FeatureCommand.SetEqualizerPreset ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_EQUALIZER, command.preset.id)
            is FeatureCommand.SetLowLatency ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_LOW_LATENCY, command.enabled.toString())
            is FeatureCommand.SetSpatialAudio ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_SPATIAL_AUDIO, command.mode.name)
            is FeatureCommand.SetSpatialSoundSwitch ->
                IpcCommandPayload(
                    HeadphoneIpcContract.TYPE_SET_SPATIAL_SOUND_SWITCH,
                    command.enabled.toString(),
                )
            is FeatureCommand.SetDualDeviceConnection ->
                IpcCommandPayload(HeadphoneIpcContract.TYPE_SET_DUAL_DEVICE, command.enabled.toString())
        }
    }
}

data class IpcCommandEnvelope(
    val requestId: String,
    val deviceId: String?,
    val vendorId: String?,
    val timestamp: Long,
    val payload: IpcCommandPayload,
)

@Serializable
data class HeadphoneSnapshotPayload(
    val contractVersion: Int = HeadphoneIpcContract.VERSION,
    val hostInstanceId: String,
    val deviceId: String,
    val generationId: Long,
    val vendorId: String?,
    val primaryAddress: String?,
    val memberAddresses: Set<String> = emptySet(),
    val groupId: String? = null,
    val deviceName: String?,
    val connection: IpcConnectionState,
    val protocolReady: Boolean,
    val transport: String?,
    val topology: String? = null,
    val firmware: String?,
    val compatibility: String?,
    val protocolName: String? = null,
    val protocolVersion: String? = null,
    val commandTable: String? = null,
    val batteries: List<BatteryPayload>,
    val wearing: Map<String, String>,
    val noiseControlActiveMode: String? = null,
    val features: Map<String, FeatureValuePayload>,
    val capabilities: List<CapabilityPayload>,
    val operation: OperationPayload?,
    val emittedAtMillis: Long,
) {
    companion object {
        fun from(snapshot: HeadphoneSnapshot, hostInstanceId: String): HeadphoneSnapshotPayload {
            require(hostInstanceId.isNotBlank()) { "hostInstanceId must not be blank" }
            val profile = snapshot.profile
            val state = snapshot.state
            val mayExposeState = profile?.compatibilityLevel?.canExposeState == true
            fun feature(
                confirmed: Any?,
                pending: Any?,
                stale: Boolean,
                source: Any?,
            ) = FeatureValuePayload(
                confirmed = confirmed?.toString(),
                pending = pending?.toString(),
                stale = stale,
                source = source?.toString(),
            )
            return HeadphoneSnapshotPayload(
                hostInstanceId = hostInstanceId,
                deviceId = snapshot.deviceId.value,
                generationId = snapshot.generationId,
                vendorId = profile?.vendorId?.value,
                primaryAddress = profile?.identity?.primaryAddress,
                memberAddresses = profile?.identity?.memberAddresses.orEmpty(),
                groupId = profile?.identity?.groupId,
                deviceName = profile?.model?.takeIf { mayExposeState },
                connection = snapshot.connection.toIpcConnectionState(),
                protocolReady = snapshot.connection is SessionState.Ready,
                transport = profile?.transport?.name,
                topology = profile?.topology?.name?.takeIf { mayExposeState },
                firmware = (state.firmware ?: profile?.firmware).takeIf { mayExposeState },
                compatibility = profile?.compatibilityLevel?.name,
                protocolName = profile?.protocol?.name,
                protocolVersion = profile?.protocol?.version,
                commandTable = profile?.protocol?.commandTable,
                batteries = state.batteries.takeIf { mayExposeState }.orEmpty().map { (component, value) ->
                    BatteryPayload(component.name, value.level, value.charging)
                },
                wearing = state.wearing.takeIf { mayExposeState }.orEmpty()
                    .mapKeys { it.key.name }.mapValues { it.value.name },
                noiseControlActiveMode = state.noiseControlActiveMode?.name
                    ?.takeIf { mayExposeState },
                features = if (mayExposeState) mapOf(
                    FeatureId.NOISE_CONTROL.name to feature(
                        state.noiseControl.confirmed?.name,
                        state.noiseControl.pending?.name,
                        state.noiseControl.stale,
                        state.noiseControl.source,
                    ),
                    FeatureId.AMBIENT_SOUND_LEVEL.name to feature(
                        state.ambientSoundLevel.confirmed,
                        state.ambientSoundLevel.pending,
                        state.ambientSoundLevel.stale,
                        state.ambientSoundLevel.source,
                    ),
                    FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT.name to feature(
                        state.transparencyVocalEnhancement.confirmed,
                        state.transparencyVocalEnhancement.pending,
                        state.transparencyVocalEnhancement.stale,
                        state.transparencyVocalEnhancement.source,
                    ),
                    FeatureId.EQUALIZER.name to feature(
                        state.equalizer.confirmed?.id,
                        state.equalizer.pending?.id,
                        state.equalizer.stale,
                        state.equalizer.source,
                    ),
                    FeatureId.LOW_LATENCY.name to feature(
                        state.lowLatency.confirmed,
                        state.lowLatency.pending,
                        state.lowLatency.stale,
                        state.lowLatency.source,
                    ),
                    FeatureId.SPATIAL_AUDIO.name to feature(
                        state.spatialAudio.confirmed?.name,
                        state.spatialAudio.pending?.name,
                        state.spatialAudio.stale,
                        state.spatialAudio.source,
                    ),
                    FeatureId.SPATIAL_SOUND_SWITCH.name to feature(
                        state.spatialSoundSwitch.confirmed,
                        state.spatialSoundSwitch.pending,
                        state.spatialSoundSwitch.stale,
                        state.spatialSoundSwitch.source,
                    ),
                    FeatureId.DUAL_DEVICE_CONNECTION.name to feature(
                        state.dualDeviceConnection.confirmed,
                        state.dualDeviceConnection.pending,
                        state.dualDeviceConnection.stale,
                        state.dualDeviceConnection.source,
                    ),
                ) else emptyMap(),
                capabilities = profile?.features?.values?.takeIf { mayExposeState }?.map {
                    CapabilityPayload(
                        it.featureId.name,
                        it.canRead,
                        it.canWrite,
                        it.evidence.name,
                        it.requiresReadback,
                        it.allowedValues.toList(),
                        it.valueLabels,
                        it.availableOnTransports.map { transport -> transport.name }.toSet(),
                        it.source,
                    )
                }.orEmpty(),
                operation = snapshot.lastOperation?.let {
                    OperationPayload(
                        requestId = it.requestId.value,
                        featureId = it.command.featureId.name,
                        phase = it.phase.name,
                        failure = it.failure?.name,
                        detail = it.detail,
                        atMillis = it.atMillis,
                    )
                },
                emittedAtMillis = snapshot.emittedAtMillis,
            )
        }
    }
}

private fun SessionState.toIpcConnectionState(): IpcConnectionState = when (this) {
    is SessionState.Idle -> IpcConnectionState.IDLE
    is SessionState.Detecting -> IpcConnectionState.DETECTING
    is SessionState.TransportConnecting -> IpcConnectionState.TRANSPORT_CONNECTING
    is SessionState.ProtocolHandshaking -> IpcConnectionState.PROTOCOL_HANDSHAKING
    is SessionState.LoadingCapabilities -> IpcConnectionState.LOADING_CAPABILITIES
    is SessionState.SynchronizingState -> IpcConnectionState.SYNCHRONIZING_STATE
    is SessionState.Ready -> IpcConnectionState.READY
    is SessionState.Reconnecting -> IpcConnectionState.RECONNECTING
    is SessionState.Disconnecting -> IpcConnectionState.DISCONNECTING
    is SessionState.Failed -> IpcConnectionState.FAILED
}

@Serializable
data class BatteryPayload(
    val component: String,
    val level: Int,
    val charging: Boolean,
)

@Serializable
data class FeatureValuePayload(
    val confirmed: String?,
    val pending: String?,
    val stale: Boolean,
    val source: String?,
)

@Serializable
data class CapabilityPayload(
    val featureId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val evidence: String,
    val requiresReadback: Boolean,
    val allowedValues: List<String>,
    val valueLabels: Map<String, String> = emptyMap(),
    val availableOnTransports: Set<String> = emptySet(),
    val source: String? = null,
)

@Serializable
data class OperationPayload(
    val requestId: String,
    val featureId: String,
    val phase: String,
    val failure: String?,
    val detail: String?,
    val atMillis: Long,
)

object HeadphoneIpcCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeCommand(payload: IpcCommandPayload): String =
        json.encodeToString(IpcCommandPayload.serializer(), payload)

    fun decodeCommand(value: String): IpcCommandPayload? =
        runCatching { json.decodeFromString(IpcCommandPayload.serializer(), value) }.getOrNull()

    fun encodeSnapshot(payload: HeadphoneSnapshotPayload): String =
        json.encodeToString(HeadphoneSnapshotPayload.serializer(), payload)

    fun decodeSnapshot(value: String): HeadphoneSnapshotPayload? =
        runCatching { json.decodeFromString(HeadphoneSnapshotPayload.serializer(), value) }.getOrNull()
}
