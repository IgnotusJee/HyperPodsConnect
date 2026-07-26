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
    val equalizer: FeatureValue<EqualizerPreset> = FeatureValue.empty(),
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
        equalizer = equalizer.markStale(),
        lowLatency = lowLatency.markStale(),
        spatialAudio = spatialAudio.markStale(),
        spatialSoundSwitch = spatialSoundSwitch.markStale(),
        dualDeviceConnection = dualDeviceConnection.markStale(),
    )
}
