package moe.chenxy.oppopods.ipc

import android.content.Intent
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot

object HeadphoneIpcContract {
    const val VERSION = 2
    const val ACTION_HEADPHONE_COMMAND = "moe.chenxy.oppopods.action.HEADPHONE_COMMAND_V2"
    const val ACTION_HEADPHONE_EVENT = "moe.chenxy.oppopods.action.HEADPHONE_EVENT_V2"

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
    const val TYPE_SET_TRANSPARENCY_VOCAL_ENHANCEMENT =
        "set_transparency_vocal_enhancement"
    const val TYPE_SET_EQUALIZER = "set_equalizer"
    const val TYPE_SET_LOW_LATENCY = "set_low_latency"
    const val TYPE_SET_SPATIAL_AUDIO = "set_spatial_audio"
    const val TYPE_SET_SPATIAL_SOUND_SWITCH = "set_spatial_sound_switch"
    const val TYPE_SET_DUAL_DEVICE = "set_dual_device"
    const val TYPE_SNAPSHOT = "snapshot"

    val eventTargets = listOf(
        "moe.chenxy.oppopods",
        "com.milink.service",
        "com.xiaomi.bluetooth",
        "com.android.settings",
    )

    fun commandIntent(
        command: IpcCommandPayload,
        requestId: String = UUID.randomUUID().toString(),
        deviceId: String? = null,
        vendorId: String? = null,
        targetPackage: String = "com.android.bluetooth",
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
    }
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
    val deviceId: String,
    val generationId: Long,
    val vendorId: String?,
    val primaryAddress: String?,
    val deviceName: String?,
    val connection: String,
    val protocolReady: Boolean,
    val transport: String?,
    val firmware: String?,
    val compatibility: String?,
    val batteries: List<BatteryPayload>,
    val wearing: Map<String, String>,
    val features: Map<String, FeatureValuePayload>,
    val capabilities: List<CapabilityPayload>,
    val operation: OperationPayload?,
    val emittedAtMillis: Long,
) {
    companion object {
        fun from(snapshot: HeadphoneSnapshot): HeadphoneSnapshotPayload {
            val profile = snapshot.profile
            val state = snapshot.state
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
                deviceId = snapshot.deviceId.value,
                generationId = snapshot.generationId,
                vendorId = profile?.vendorId?.value,
                primaryAddress = profile?.identity?.primaryAddress,
                deviceName = profile?.model,
                connection = snapshot.connection::class.simpleName.orEmpty(),
                protocolReady = snapshot.connection is SessionState.Ready,
                transport = profile?.transport?.name,
                firmware = state.firmware ?: profile?.firmware,
                compatibility = profile?.compatibilityLevel?.name,
                batteries = state.batteries.map { (component, value) ->
                    BatteryPayload(component.name, value.level, value.charging)
                },
                wearing = state.wearing.mapKeys { it.key.name }.mapValues { it.value.name },
                features = mapOf(
                    FeatureId.NOISE_CONTROL.name to feature(
                        state.noiseControl.confirmed?.name,
                        state.noiseControl.pending?.name,
                        state.noiseControl.stale,
                        state.noiseControl.source,
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
                ),
                capabilities = profile?.features?.values?.map {
                    CapabilityPayload(
                        it.featureId.name,
                        it.canRead,
                        it.canWrite,
                        it.evidence.name,
                        it.requiresReadback,
                        it.allowedValues.toList(),
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
