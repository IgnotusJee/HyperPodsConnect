package moe.chenxy.oppopods

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotReceiver
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.profile.DeviceProfileRepository

class OppoPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        DeviceProfileRepository(
            getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE),
        ).migrateLegacyPodImages()
        HeadphoneSnapshotReceiver.register(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "LSPosed service bound api=${service.apiVersion} framework=${service.frameworkName}/${service.frameworkVersionCode}")
        xposedService = service
        notifyListeners(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            Log.d(TAG, "LSPosed service died")
            xposedService = null
            notifyListeners(null)
        }
    }

    private fun notifyListeners(service: XposedService?) {
        listeners.forEach { it(service) }
    }

    companion object {
        private const val TAG = "OppoPods-App"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}
