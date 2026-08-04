package org.hyperpods.connect.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import org.hyperpods.connect.integration.NotificationBatteryComponent
import org.hyperpods.connect.integration.NotificationBatterySlot
import org.hyperpods.connect.integration.toNotificationProjection
import org.hyperpods.connect.hook.Log
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation

@SuppressLint("WrongConstant", "NotificationPermission")
object FocusIslandUtil {
    private const val TAG = "HyperPodsConnect-FocusIsland"
    private const val CHANNEL_ID = "hyperpods_connect_focus_island"
    private const val CHANNEL_NAME = "Headphone Battery"
    private const val NOTIFICATION_ID = 10086
    private const val DISMISS_DELAY_MS = 4000L

    fun showBatteryIsland(
        context: Context,
        prefs: SharedPreferences,
        batteryParams: HeadphoneBatteryPresentation,
        address: String,
    ): Boolean {
        try {
            val projection = batteryParams.toNotificationProjection("Headphones")
            val slots = projection.islandSlots
            if (slots.isEmpty()) return false
            val primary = slots[0]
            val secondary = slots.getOrNull(1)
            val primaryBitmap = loadSlotBitmap(context, prefs, address, primary) ?: return false
            val secondaryBitmap = secondary?.let {
                loadSlotBitmap(context, prefs, address, it)
            }
            if (secondary != null && secondaryBitmap == null) return false

            // Embed bitmap data so SystemUI never needs direct access to module resources.
            val primaryIcon = Icon.createWithBitmap(primaryBitmap)
            val secondaryIcon = secondaryBitmap?.let(Icon::createWithBitmap)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setAllowBubbles(true)
                }
            )

            val contentText = projection.aodTitle

            val extras = FocusNotification.buildV3 {
                val picPrimary = createPicture("key_pic_primary", primaryIcon)
                val picSecondary = secondaryIcon?.let {
                    createPicture("key_pic_secondary", it)
                }

                enableFloat = true
                ticker = projection.title
                tickerPic = picPrimary

                isShowNotification = false
                island {
                    islandProperty = 1
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picPrimary
                            }
                            textInfo {
                                title = primary.level.toString()
                                content = "%"
                            }
                        }
                        if (secondary != null && picSecondary != null) {
                            imageTextInfoRight {
                                type = 2
                                picInfo {
                                    type = 1
                                    pic = picSecondary
                                }
                                textInfo {
                                    title = secondary.level.toString()
                                    content = "%"
                                }
                            }
                        }
                    }
                    shareData {
                        title = projection.title
                        content = contentText
                        shareContent = contentText
                    }
                }
            }

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(projection.title)
                .setContentText(contentText)
                .setTicker(projection.title)
                .addExtras(extras)
                .build()

            nm.notify(NOTIFICATION_ID, notification)

            Handler(Looper.getMainLooper()).postDelayed({
                try { nm.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
            }, DISMISS_DELAY_MS)

            Log.d(TAG, "Focus Island shown: title=${projection.title} batteries=$contentText")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Focus Island", e)
            return false
        }
    }

    fun cancelBatteryIsland(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Focus Island cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel Focus Island", e)
        }
    }

    private fun loadSlotBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        slot: NotificationBatterySlot,
    ) = when (slot.component) {
        NotificationBatteryComponent.LEFT ->
            PodImageLoader.loadIslandLeftBitmap(context, prefs, address)
        NotificationBatteryComponent.RIGHT ->
            PodImageLoader.loadIslandRightBitmap(context, prefs, address)
        NotificationBatteryComponent.SINGLE,
        NotificationBatteryComponent.CASE ->
            PodImageLoader.loadIslandSingleBitmap(context, prefs, address)
    }
}
