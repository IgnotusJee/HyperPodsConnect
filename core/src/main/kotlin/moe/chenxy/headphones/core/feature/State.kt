package moe.chenxy.headphones.core.feature

enum class BatteryComponent { LEFT, RIGHT, CASE, SINGLE }

enum class WearComponent { LEFT, RIGHT }

enum class WearState { WEARING, REMOVED, IN_CASE, UNKNOWN }

data class BatteryState(val level: Int, val charging: Boolean) {
    init {
        require(level in 0..100) { "battery level out of range: $level" }
    }
}

enum class NoiseControlMode {
    OFF,
    NOISE_CANCELLATION,
    NOISE_CANCELLATION_LIGHT,
    NOISE_CANCELLATION_MEDIUM,
    NOISE_CANCELLATION_DEEP,
    NOISE_CANCELLATION_SMART,
    TRANSPARENCY,
    ADAPTIVE,
}

/** A preset as the domain sees it: an opaque id plus something to show a user. */
data class EqualizerPreset(val id: String, val displayName: String? = null)

enum class EqualizerBandKind { STANDARD, CLEAR_BASS }

/** One presentation-safe band exposed by a device capability. */
data class EqualizerBandSpec(
    val id: String,
    val displayName: String,
    val minGain: Int,
    val maxGain: Int,
    val step: Int = 1,
    val centerFrequencyHz: Int? = null,
    val kind: EqualizerBandKind = EqualizerBandKind.STANDARD,
) {
    init {
        require(id.isNotBlank()) { "equalizer band id must not be blank" }
        require(displayName.isNotBlank()) { "equalizer band label must not be blank" }
        require(minGain <= maxGain) { "equalizer band range is inverted" }
        require(step > 0) { "equalizer band step must be positive" }
    }
}

/** Device-specific curve layout without exposing vendor wire values. */
data class EqualizerCurveSpec(
    val bands: List<EqualizerBandSpec>,
    val writableSlotIds: Set<String>,
) {
    init {
        require(bands.isNotEmpty()) { "equalizer curve must contain bands" }
        require(bands.map { it.id }.distinct().size == bands.size) {
            "equalizer band ids must be unique"
        }
        require(writableSlotIds.none(String::isBlank)) {
            "equalizer writable slot id must not be blank"
        }
    }

    fun accepts(curve: EqualizerCurve): Boolean =
        curve.slotId in writableSlotIds &&
            curve.gains.size == bands.size &&
            curve.gains.zip(bands).all { (gain, band) ->
                gain in band.minGain..band.maxGain &&
                    (gain - band.minGain) % band.step == 0
            }
}

/** A complete curve for one explicit custom slot, expressed in signed gain steps. */
data class EqualizerCurve(
    val slotId: String,
    val gains: List<Int>,
) {
    init {
        require(slotId.isNotBlank()) { "equalizer curve slot id must not be blank" }
        require(gains.isNotEmpty()) { "equalizer curve must contain gains" }
    }
}

enum class SpatialAudioMode { OFF, FIXED, HEAD_TRACKING }

/**
 * Where a value came from. Anything but [LOCAL_PENDING] means the device said it.
 */
enum class ValueSource {
    /** Set locally, not yet acknowledged by the device. Never authoritative. */
    LOCAL_PENDING,

    /** Answer to a query we sent. */
    QUERY_RESPONSE,

    /** Unsolicited notification from the device. */
    NOTIFICATION,

    /** Read back after a write, which is the only proof a write took effect. */
    READ_BACK,
}

/**
 * One observable value, keeping intent and fact apart.
 *
 * The separation is forced by the hardware: set responses carry a status byte
 * and nothing else, so "the device accepted the command" and "the value is now
 * X" are different claims. [confirmed] only ever moves on evidence from the
 * device; [pending] holds what the user asked for until that arrives.
 */
data class FeatureValue<T>(
    val confirmed: T? = null,
    val pending: T? = null,
    val updatedAtMillis: Long? = null,
    val source: ValueSource? = null,
    val stale: Boolean = false,
) {
    val hasPendingChange: Boolean get() = pending != null && pending != confirmed

    /** What a UI should show: the intent if one is outstanding, else the fact. */
    val displayed: T? get() = pending ?: confirmed

    fun withPending(value: T, atMillis: Long): FeatureValue<T> =
        copy(pending = value, updatedAtMillis = atMillis, source = ValueSource.LOCAL_PENDING)

    /**
     * Records device-reported truth. Clears [pending] only when the device
     * reports the value that was requested; a different value means the write
     * did not take, and the pending intent stays visible rather than being
     * silently overwritten.
     */
    fun withConfirmed(value: T, source: ValueSource, atMillis: Long): FeatureValue<T> =
        FeatureValue(
            confirmed = value,
            pending = if (pending == null || pending == value) null else pending,
            updatedAtMillis = atMillis,
            source = source,
            stale = false,
        )

    /** Drops an intent that will never be confirmed, e.g. after a timeout. */
    fun rollbackPending(): FeatureValue<T> = copy(pending = null)

    fun markStale(): FeatureValue<T> = copy(stale = true)

    companion object {
        fun <T> empty(): FeatureValue<T> = FeatureValue()
    }
}

/**
 * Everything currently known about a connected headset.
 *
 * Batteries and wear are maps rather than fixed left/right/case fields so a
 * headband, a neckband, or a set that reports only two components can be
 * expressed without inventing entries. A real Air5s does exactly that: it
 * reports two components while in use and three once the case is involved.
 */
data class HeadphoneState(
    val batteries: Map<BatteryComponent, BatteryState> = emptyMap(),
    val wearing: Map<WearComponent, WearState> = emptyMap(),
    val noiseControl: FeatureValue<NoiseControlMode> = FeatureValue.empty(),
    /** Effective NC strength currently selected by an automatic/smart noise-control mode. */
    val noiseControlActiveMode: NoiseControlMode? = null,
    val ambientSoundLevel: FeatureValue<Int> = FeatureValue.empty(),
    val transparencyVocalEnhancement: FeatureValue<Boolean> = FeatureValue.empty(),
    val equalizer: FeatureValue<EqualizerPreset> = FeatureValue.empty(),
    val equalizerCurve: FeatureValue<EqualizerCurve> = FeatureValue.empty(),
    val lowLatency: FeatureValue<Boolean> = FeatureValue.empty(),
    val spatialAudio: FeatureValue<SpatialAudioMode> = FeatureValue.empty(),
    val spatialSoundSwitch: FeatureValue<Boolean> = FeatureValue.empty(),
    val dualDeviceConnection: FeatureValue<Boolean> = FeatureValue.empty(),
    val firmware: String? = null,
    val vendorStates: Map<String, String> = emptyMap(),
) {
    /** Marks every value stale on disconnect without discarding what we knew. */
    fun markAllStale(): HeadphoneState = copy(
        noiseControl = noiseControl.markStale(),
        ambientSoundLevel = ambientSoundLevel.markStale(),
        transparencyVocalEnhancement = transparencyVocalEnhancement.markStale(),
        equalizer = equalizer.markStale(),
        equalizerCurve = equalizerCurve.markStale(),
        lowLatency = lowLatency.markStale(),
        spatialAudio = spatialAudio.markStale(),
        spatialSoundSwitch = spatialSoundSwitch.markStale(),
        dualDeviceConnection = dualDeviceConnection.markStale(),
    )
}
