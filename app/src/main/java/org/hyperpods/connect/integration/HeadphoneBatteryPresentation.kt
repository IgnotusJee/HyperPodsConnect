package org.hyperpods.connect.integration
import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class BatterySlotPresentation (
    var battery: Int = 0,
    var isCharging: Boolean = false,
    var isConnected: Boolean = false,
    var rawStatus: Int = 0
) : Parcelable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class HeadphoneBatteryPresentation(
    var left: BatterySlotPresentation? = null,
    var right: BatterySlotPresentation? = null,
    var case: BatterySlotPresentation? = null,
    var single: BatterySlotPresentation? = null,
    var deviceName: String? = null,
    var topology: String? = null,
    var canCycleNoiseControl: Boolean = false,
) : Parcelable
