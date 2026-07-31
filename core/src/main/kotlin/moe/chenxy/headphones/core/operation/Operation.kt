package moe.chenxy.headphones.core.operation

import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode

/** Correlates a request with everything it later produces. */
@JvmInline
value class RequestId(val value: String)

/**
 * What callers may ask for.
 *
 * There is deliberately no raw-bytes command: an arbitrary-frame path would let
 * any caller bypass every capability and safety check in this module.
 */
sealed interface FeatureCommand {
    val featureId: FeatureId

    data object RefreshAll : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.BATTERY
    }

    data class Refresh(override val featureId: FeatureId) : FeatureCommand

    data class SetNoiseControl(val mode: NoiseControlMode) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.NOISE_CONTROL
    }

    data class SetAmbientSoundLevel(val level: Int) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.AMBIENT_SOUND_LEVEL
    }

    data class SetTransparencyVocalEnhancement(val enabled: Boolean) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT
    }

    data class SetEqualizerPreset(val preset: EqualizerPreset) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.EQUALIZER
    }

    data class SetLowLatency(val enabled: Boolean) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.LOW_LATENCY
    }

    data class SetSpatialAudio(val mode: SpatialAudioMode) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.SPATIAL_AUDIO
    }

    data class SetSpatialSoundSwitch(val enabled: Boolean) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.SPATIAL_SOUND_SWITCH
    }

    data class SetDualDeviceConnection(val enabled: Boolean) : FeatureCommand {
        override val featureId: FeatureId get() = FeatureId.DUAL_DEVICE_CONNECTION
    }
}

/**
 * How far a write has actually got.
 *
 * [DEVICE_ACCEPTED] and [STATE_CONFIRMED] are separate because on real hardware
 * they are separate events: a set response carries a status byte and no echo of
 * the new value, so acceptance is not proof the value changed. Only a readback
 * or a notification advances an operation past acceptance.
 */
enum class OperationPhase {
    QUEUED,
    SENT,
    TRANSPORT_ACKNOWLEDGED,
    DEVICE_ACCEPTED,
    STATE_CONFIRMED,
    READ_BACK_CONFIRMED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == STATE_CONFIRMED || this == READ_BACK_CONFIRMED ||
            this == FAILED || this == TIMED_OUT || this == CANCELLED

    val isSuccess: Boolean
        get() = this == STATE_CONFIRMED || this == READ_BACK_CONFIRMED
}

enum class FailureReason {
    NOT_SUPPORTED,
    NOT_WRITABLE,
    VALUE_OUT_OF_RANGE,
    NOT_CONNECTED,
    TRANSPORT_ERROR,
    DEVICE_REJECTED,
    TIMEOUT,
    CANCELLED,
    GENERATION_INVALIDATED,
}

data class OperationEvent(
    val requestId: RequestId,
    val command: FeatureCommand,
    val phase: OperationPhase,
    val atMillis: Long,
    val generationId: Long,
    val failure: FailureReason? = null,
    val detail: String? = null,
)

data class OperationResult(
    val requestId: RequestId,
    val phase: OperationPhase,
    val failure: FailureReason? = null,
    val detail: String? = null,
) {
    val succeeded: Boolean get() = phase.isSuccess

    companion object {
        fun failed(requestId: RequestId, reason: FailureReason, detail: String? = null) =
            OperationResult(requestId, OperationPhase.FAILED, reason, detail)
    }
}
