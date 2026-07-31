package moe.chenxy.headphones.core.feature

import moe.chenxy.headphones.core.operation.FeatureCommand

/** Something that happened to the device state, from the device or from us. */
sealed interface StateUpdate {
    val atMillis: Long

    /** A write left this process. Records intent only. */
    data class LocalPending(
        val command: FeatureCommand,
        override val atMillis: Long,
    ) : StateUpdate

    /**
     * The device acknowledged a write with a status byte.
     *
     * Carries no value on purpose: every observed set response reports only
     * success or failure, so this update may never move a confirmed value.
     */
    data class WriteAcknowledged(
        val featureId: FeatureId,
        val accepted: Boolean,
        override val atMillis: Long,
    ) : StateUpdate

    /** The device reported an actual value. The only thing that confirms state. */
    data class DeviceReported(
        val report: DeviceReport,
        val source: ValueSource,
        override val atMillis: Long,
    ) : StateUpdate

    /** A pending write will never be confirmed. */
    data class PendingAbandoned(
        val featureId: FeatureId,
        override val atMillis: Long,
    ) : StateUpdate

    data class Disconnected(override val atMillis: Long) : StateUpdate
}

/** A value the device reported, already mapped out of vendor encoding. */
sealed interface DeviceReport {
    data class Batteries(val values: Map<BatteryComponent, BatteryState>) : DeviceReport
    data class Wearing(val values: Map<WearComponent, WearState>) : DeviceReport
    data class NoiseControl(val mode: NoiseControlMode) : DeviceReport
    data class AmbientSoundLevel(val level: Int) : DeviceReport
    data class TransparencyVocalEnhancement(val enabled: Boolean) : DeviceReport
    data class Equalizer(val preset: EqualizerPreset) : DeviceReport
    data class LowLatency(val enabled: Boolean) : DeviceReport
    data class SpatialAudio(val mode: SpatialAudioMode) : DeviceReport
    data class SpatialSoundSwitch(val enabled: Boolean) : DeviceReport
    data class DualDeviceConnection(val enabled: Boolean) : DeviceReport
    data class Firmware(val version: String) : DeviceReport
}

/**
 * Folds updates into [HeadphoneState].
 *
 * The rule the whole type exists to enforce: a confirmed value changes only on
 * [StateUpdate.DeviceReported]. An acknowledgement, however successful, is not
 * evidence — real set responses carry a status byte and no echo of the value,
 * so treating acceptance as confirmation is how a UI ends up showing a mode the
 * headset never entered.
 */
object HeadphoneStateReducer {

    fun reduce(state: HeadphoneState, update: StateUpdate): HeadphoneState = when (update) {
        is StateUpdate.LocalPending -> applyPending(state, update)
        // Intentionally a no-op for state: acceptance is an operation-level fact,
        // surfaced through OperationPhase.DEVICE_ACCEPTED, not a value change.
        is StateUpdate.WriteAcknowledged -> state
        is StateUpdate.DeviceReported -> applyReport(state, update)
        is StateUpdate.PendingAbandoned -> rollback(state, update.featureId)
        is StateUpdate.Disconnected -> state.markAllStale()
    }

    fun reduceAll(state: HeadphoneState, updates: List<StateUpdate>): HeadphoneState =
        updates.fold(state, ::reduce)

    private fun applyPending(state: HeadphoneState, update: StateUpdate.LocalPending) =
        when (val command = update.command) {
            is FeatureCommand.SetNoiseControl ->
                state.copy(noiseControl = state.noiseControl.withPending(command.mode, update.atMillis))

            is FeatureCommand.SetAmbientSoundLevel ->
                state.copy(
                    ambientSoundLevel =
                        state.ambientSoundLevel.withPending(command.level, update.atMillis),
                )

            is FeatureCommand.SetTransparencyVocalEnhancement ->
                state.copy(
                    transparencyVocalEnhancement =
                        state.transparencyVocalEnhancement.withPending(command.enabled, update.atMillis),
                )

            is FeatureCommand.SetEqualizerPreset ->
                state.copy(equalizer = state.equalizer.withPending(command.preset, update.atMillis))

            is FeatureCommand.SetLowLatency ->
                state.copy(lowLatency = state.lowLatency.withPending(command.enabled, update.atMillis))

            is FeatureCommand.SetSpatialAudio ->
                state.copy(spatialAudio = state.spatialAudio.withPending(command.mode, update.atMillis))

            is FeatureCommand.SetSpatialSoundSwitch ->
                state.copy(spatialSoundSwitch = state.spatialSoundSwitch.withPending(command.enabled, update.atMillis))

            is FeatureCommand.SetDualDeviceConnection ->
                state.copy(dualDeviceConnection = state.dualDeviceConnection.withPending(command.enabled, update.atMillis))

            is FeatureCommand.Refresh, FeatureCommand.RefreshAll -> state
        }

    private fun applyReport(state: HeadphoneState, update: StateUpdate.DeviceReported): HeadphoneState {
        val at = update.atMillis
        val source = update.source
        return when (val report = update.report) {
            // Replaced wholesale rather than merged: the component set is itself
            // information. An Air5s reports two components in use and three once
            // the case is involved, and merging would keep a stale case entry.
            is DeviceReport.Batteries -> state.copy(batteries = report.values)
            is DeviceReport.Wearing -> state.copy(wearing = report.values)

            is DeviceReport.NoiseControl ->
                state.copy(noiseControl = state.noiseControl.withConfirmed(report.mode, source, at))

            is DeviceReport.AmbientSoundLevel ->
                state.copy(
                    ambientSoundLevel =
                        state.ambientSoundLevel.withConfirmed(report.level, source, at),
                )

            is DeviceReport.TransparencyVocalEnhancement ->
                state.copy(
                    transparencyVocalEnhancement =
                        state.transparencyVocalEnhancement.withConfirmed(report.enabled, source, at),
                )

            is DeviceReport.Equalizer ->
                state.copy(equalizer = state.equalizer.withConfirmed(report.preset, source, at))

            is DeviceReport.LowLatency ->
                state.copy(lowLatency = state.lowLatency.withConfirmed(report.enabled, source, at))

            is DeviceReport.SpatialAudio ->
                state.copy(spatialAudio = state.spatialAudio.withConfirmed(report.mode, source, at))

            is DeviceReport.SpatialSoundSwitch ->
                state.copy(spatialSoundSwitch = state.spatialSoundSwitch.withConfirmed(report.enabled, source, at))

            is DeviceReport.DualDeviceConnection ->
                state.copy(dualDeviceConnection = state.dualDeviceConnection.withConfirmed(report.enabled, source, at))

            is DeviceReport.Firmware -> state.copy(firmware = report.version)
        }
    }

    private fun rollback(state: HeadphoneState, featureId: FeatureId): HeadphoneState = when (featureId) {
        FeatureId.NOISE_CONTROL -> state.copy(noiseControl = state.noiseControl.rollbackPending())
        FeatureId.AMBIENT_SOUND_LEVEL ->
            state.copy(ambientSoundLevel = state.ambientSoundLevel.rollbackPending())
        FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT ->
            state.copy(
                transparencyVocalEnhancement = state.transparencyVocalEnhancement.rollbackPending(),
            )
        FeatureId.EQUALIZER -> state.copy(equalizer = state.equalizer.rollbackPending())
        FeatureId.LOW_LATENCY -> state.copy(lowLatency = state.lowLatency.rollbackPending())
        FeatureId.SPATIAL_AUDIO -> state.copy(spatialAudio = state.spatialAudio.rollbackPending())
        FeatureId.SPATIAL_SOUND_SWITCH -> state.copy(spatialSoundSwitch = state.spatialSoundSwitch.rollbackPending())
        FeatureId.DUAL_DEVICE_CONNECTION -> state.copy(dualDeviceConnection = state.dualDeviceConnection.rollbackPending())
        else -> state
    }
}
