package org.hyperpods.connect.hook

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.hook.milink.MiLinkServiceHook

class HookEntry : XposedModule() {
    private val TAG = "HyperPodsConnect-HookEntry"
    private val configListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        HyperOsProcessRouter.targets(param.packageName, processName).forEach { target ->
            when (target) {
                HyperOsHookTarget.ANDROID_BLUETOOTH -> {
                    loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                    loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
                }
                HyperOsHookTarget.XIAOMI_BLUETOOTH -> {
                    loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                    loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
                }
                HyperOsHookTarget.SETTINGS ->
                    loadHook(SettingsHeadsetHook, param.defaultClassLoader, param.packageName)
                HyperOsHookTarget.MILINK_CORE, HyperOsHookTarget.MILINK_UI ->
                    loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            }
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        hook.processName = processName
        hook.prefs = getRemotePreferences("hyperpods_connect_settings")
        Log.d(TAG, "loadHook package=$packageName process=$processName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)
        val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == ConfigManager.PREF_KEY_CONFIG_JSON) {
                ConfigManager.refreshFromPrefs(sharedPreferences)
            }
        }
        configListeners.add(configListener)
        hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
        hook.onHook()
    }
}
