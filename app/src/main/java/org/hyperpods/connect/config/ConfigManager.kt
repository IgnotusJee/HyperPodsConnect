package org.hyperpods.connect.config

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppConfig(
    val hyperOsPresentationTypeId: String = ConfigManager.DEFAULT_HYPEROS_PRESENTATION_TYPE_ID,
    val logLevel: Int = ConfigManager.LOG_LEVEL_BASIC,
    val islandMode: Int = ConfigManager.ISLAND_MODE_OFFICIAL,
    val islandShowTimings: Set<Int> = emptySet(),
    val connectionPopupEnabled: Boolean = true,
    val notificationClickAction: Int = ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
    val moreClickAction: Int = ConfigManager.MORE_CLICK_MODULE,
    val adaptiveCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialAudioCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val spatialSoundSwitchCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    val ancImplementationCapabilityOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
)

object ConfigManager {
    private const val TAG = "HyperPodsConnect-Config"
    const val PREFS_NAME = "hyperpods_connect_settings"
    const val PREF_KEY_CONFIG_JSON = "config_json"
    const val PREF_KEY_HYPEROS_PRESENTATION_TYPE_ID = "hyperos_presentation_type_id"
    const val PREF_KEY_LOG_LEVEL = "log_level"
    const val PREF_KEY_ISLAND_MODE = "island_mode"
    const val PREF_KEY_ISLAND_SHOW_TIMINGS = "island_show_timings"
    const val PREF_KEY_CONNECTION_POPUP_ENABLED = "connection_popup_enabled"
    const val PREF_KEY_NOTIFICATION_CLICK_ACTION = "notification_click_action"
    const val PREF_KEY_MORE_CLICK_ACTION = "more_click_action"
    const val PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE = "adaptive_capability_override"
    const val PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE = "spatial_audio_capability_override"
    const val PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE = "spatial_sound_switch_capability_override"
    const val PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE = "anc_implementation_capability_override"
    const val DEFAULT_HYPEROS_PRESENTATION_TYPE_ID = "01010607"
    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2
    const val ISLAND_MODE_NONE = 0
    const val ISLAND_MODE_OFFICIAL = 1
    const val ISLAND_MODE_MODULE = 2
    const val ISLAND_SHOW_TIMING_CONNECTED = 0
    const val ISLAND_SHOW_TIMING_WEARING = 1
    const val ISLAND_SHOW_TIMING_REMOVED = 2
    const val ISLAND_SHOW_TIMING_IN_CASE = 3
    const val NOTIFICATION_CLICK_MODULE_POPUP = 0
    const val NOTIFICATION_CLICK_SYSTEM_SETTINGS = 1
    const val MORE_CLICK_MODULE = 0
    const val MORE_CLICK_SYSTEM_SETTINGS = 1
    const val SPATIAL_AUDIO_OFF = 0
    const val SPATIAL_AUDIO_FIXED = 1
    const val SPATIAL_AUDIO_HEAD_TRACKING = 2
    const val CAPABILITY_OVERRIDE_AUTO = 0
    const val CAPABILITY_OVERRIDE_FORCE_ENABLED = 1
    const val CAPABILITY_OVERRIDE_FORCE_DISABLED = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: AppConfig = AppConfig()

    fun init(prefs: SharedPreferences) {
        val oldConfig = cachedConfig
        cachedConfig = readConfig(prefs, "init")
        logConfigChange("init", oldConfig, cachedConfig)
    }

    fun refreshFromPrefs(prefs: SharedPreferences): AppConfig {
        val oldConfig = cachedConfig
        return readConfig(prefs, "refreshFromPrefs").also {
            cachedConfig = it
            logConfigChange("refreshFromPrefs", oldConfig, it)
        }
    }

    fun current(): AppConfig = cachedConfig

    fun hyperOsPresentationTypeId(): String = current().hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId()

    fun logLevel(): Int = current().logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG)

    fun islandMode(): Int = current().islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE)

    fun islandShowTimings(): Set<Int> = current().islandShowTimings.normalizedIslandShowTimings()

    fun connectionPopupEnabled(): Boolean = current().connectionPopupEnabled

    fun notificationClickAction(): Int = current().notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_SYSTEM_SETTINGS)

    fun moreClickAction(): Int = current().moreClickAction.coerceIn(MORE_CLICK_MODULE, MORE_CLICK_SYSTEM_SETTINGS)

    fun adaptiveCapabilityOverride(): Int = current().adaptiveCapabilityOverride.normalizedCapabilityOverride()

    fun spatialAudioCapabilityOverride(): Int = current().spatialAudioCapabilityOverride.normalizedCapabilityOverride()

    fun spatialSoundSwitchCapabilityOverride(): Int = current().spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride()

    fun ancImplementationCapabilityOverride(): Int = current().ancImplementationCapabilityOverride.normalizedCapabilityOverride()

    fun updateHyperOsPresentationTypeId(prefs: SharedPreferences, hyperOsPresentationTypeId: String) {
        val config = current().copy(hyperOsPresentationTypeId = hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId())
        save(prefs, config)
    }

    fun updateHyperOsPresentationTypeId(prefs: SharedPreferences, service: XposedService?, hyperOsPresentationTypeId: String) {
        val config = current().copy(hyperOsPresentationTypeId = hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId())
        save(prefs, service, config)
    }

    fun updateLogLevel(prefs: SharedPreferences, service: XposedService?, logLevel: Int) {
        val config = current().copy(logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG))
        save(prefs, service, config)
    }

    fun updateIslandMode(prefs: SharedPreferences, service: XposedService?, islandMode: Int) {
        val config = current().copy(islandMode = islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE))
        save(prefs, service, config)
    }

    fun updateIslandShowTimings(prefs: SharedPreferences, service: XposedService?, timings: Set<Int>) {
        val config = current().copy(islandShowTimings = timings.normalizedIslandShowTimings())
        save(prefs, service, config)
    }

    fun updateConnectionPopupEnabled(prefs: SharedPreferences, service: XposedService?, enabled: Boolean) {
        save(prefs, service, current().copy(connectionPopupEnabled = enabled))
    }

    fun updateNotificationClickAction(prefs: SharedPreferences, service: XposedService?, action: Int) {
        val config = current().copy(notificationClickAction = action.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_SYSTEM_SETTINGS))
        save(prefs, service, config)
    }

    fun updateMoreClickAction(prefs: SharedPreferences, service: XposedService?, action: Int) {
        val config = current().copy(moreClickAction = action.coerceIn(MORE_CLICK_MODULE, MORE_CLICK_SYSTEM_SETTINGS))
        save(prefs, service, config)
    }

    fun updateAdaptiveCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(adaptiveCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateSpatialAudioCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(spatialAudioCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateSpatialSoundSwitchCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(spatialSoundSwitchCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun updateAncImplementationCapabilityOverride(prefs: SharedPreferences, service: XposedService?, override: Int) {
        val config = current().copy(ancImplementationCapabilityOverride = override.normalizedCapabilityOverride())
        save(prefs, service, config)
    }

    fun save(prefs: SharedPreferences, config: AppConfig) {
        val oldConfig = cachedConfig
        val normalized = config.copy(hyperOsPresentationTypeId = config.hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId())
        cachedConfig = normalized
        writePrefs(prefs, normalized)
        logConfigChange("save", oldConfig, normalized)
    }

    fun save(prefs: SharedPreferences, service: XposedService?, config: AppConfig) {
        val oldConfig = cachedConfig
        val normalized = config.copy(hyperOsPresentationTypeId = config.hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId())
        cachedConfig = normalized
        writePrefs(prefs, normalized)
        service?.getRemotePreferences(PREFS_NAME)?.let { remotePrefs ->
            writePrefs(remotePrefs, normalized)
            Log.d(TAG, "save remote prefs class=${remotePrefs.javaClass.name} hyperOsPresentationTypeId=${normalized.hyperOsPresentationTypeId}")
        } ?: Log.w(TAG, "save remote prefs skipped: LSPosed service is null")
        logConfigChange("save", oldConfig, normalized)
    }

    private fun writePrefs(prefs: SharedPreferences, config: AppConfig) {
        prefs.edit()
            .putString(PREF_KEY_CONFIG_JSON, json.encodeToString(AppConfig.serializer(), config))
            .putString(PREF_KEY_HYPEROS_PRESENTATION_TYPE_ID, config.hyperOsPresentationTypeId)
            .putInt(PREF_KEY_LOG_LEVEL, config.logLevel)
            .putInt(PREF_KEY_ISLAND_MODE, config.islandMode)
            .putStringSet(PREF_KEY_ISLAND_SHOW_TIMINGS, config.islandShowTimings.map { it.toString() }.toSet())
            .putBoolean(PREF_KEY_CONNECTION_POPUP_ENABLED, config.connectionPopupEnabled)
            .putInt(PREF_KEY_NOTIFICATION_CLICK_ACTION, config.notificationClickAction)
            .putInt(PREF_KEY_MORE_CLICK_ACTION, config.moreClickAction)
            .putInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, config.adaptiveCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, config.spatialAudioCapabilityOverride)
            .putInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, config.spatialSoundSwitchCapabilityOverride)
            .putInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, config.ancImplementationCapabilityOverride)
            .commit()
    }

    private fun readConfig(prefs: SharedPreferences, source: String): AppConfig {
        val directHyperOsPresentationTypeId = prefs.getString(PREF_KEY_HYPEROS_PRESENTATION_TYPE_ID, null)
        val directLogLevel = prefs.getInt(PREF_KEY_LOG_LEVEL, Int.MIN_VALUE)
        val directIslandMode = prefs.getInt(PREF_KEY_ISLAND_MODE, Int.MIN_VALUE)
        val directIslandShowTimings = prefs.getStringSet(PREF_KEY_ISLAND_SHOW_TIMINGS, null)?.mapNotNull { it.toIntOrNull() }?.toSet()
        val directConnectionPopupEnabled = if (prefs.contains(PREF_KEY_CONNECTION_POPUP_ENABLED)) {
            prefs.getBoolean(PREF_KEY_CONNECTION_POPUP_ENABLED, true)
        } else null
        val directNotificationClickAction = prefs.getInt(PREF_KEY_NOTIFICATION_CLICK_ACTION, Int.MIN_VALUE)
        val directMoreClickAction = prefs.getInt(PREF_KEY_MORE_CLICK_ACTION, Int.MIN_VALUE)
        val directAdaptiveCapabilityOverride = prefs.getInt(PREF_KEY_ADAPTIVE_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialAudioCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_AUDIO_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directSpatialSoundSwitchCapabilityOverride = prefs.getInt(PREF_KEY_SPATIAL_SOUND_SWITCH_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val directAncImplementationCapabilityOverride = prefs.getInt(PREF_KEY_ANC_IMPLEMENTATION_CAPABILITY_OVERRIDE, Int.MIN_VALUE)
        val raw = prefs.getString(PREF_KEY_CONFIG_JSON, null)
        logPrefsSnapshot(source, prefs, directHyperOsPresentationTypeId, raw)
        val config = raw?.let {
            runCatching { json.decodeFromString(AppConfig.serializer(), it) }.getOrNull()
        } ?: AppConfig()
        if (!directHyperOsPresentationTypeId.isNullOrBlank()) {
            return config.copy(
                hyperOsPresentationTypeId = directHyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId(),
                logLevel = directLogLevel.takeIf { it != Int.MIN_VALUE } ?: config.logLevel,
                islandMode = directIslandMode.takeIf { it != Int.MIN_VALUE } ?: config.islandMode,
                islandShowTimings = directIslandShowTimings ?: config.islandShowTimings,
                connectionPopupEnabled = directConnectionPopupEnabled ?: config.connectionPopupEnabled,
                notificationClickAction = directNotificationClickAction.takeIf { it != Int.MIN_VALUE } ?: config.notificationClickAction,
                moreClickAction = directMoreClickAction.takeIf { it != Int.MIN_VALUE } ?: config.moreClickAction,
                adaptiveCapabilityOverride = directAdaptiveCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.adaptiveCapabilityOverride,
                spatialAudioCapabilityOverride = directSpatialAudioCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialAudioCapabilityOverride,
                spatialSoundSwitchCapabilityOverride = directSpatialSoundSwitchCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialSoundSwitchCapabilityOverride,
                ancImplementationCapabilityOverride = directAncImplementationCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.ancImplementationCapabilityOverride,
            ).normalized()
        }
        return config.copy(
            hyperOsPresentationTypeId = config.hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId(),
            logLevel = directLogLevel.takeIf { it != Int.MIN_VALUE } ?: config.logLevel,
            islandMode = directIslandMode.takeIf { it != Int.MIN_VALUE } ?: config.islandMode,
            islandShowTimings = directIslandShowTimings ?: config.islandShowTimings,
            connectionPopupEnabled = directConnectionPopupEnabled ?: config.connectionPopupEnabled,
            notificationClickAction = directNotificationClickAction.takeIf { it != Int.MIN_VALUE } ?: config.notificationClickAction,
            moreClickAction = directMoreClickAction.takeIf { it != Int.MIN_VALUE } ?: config.moreClickAction,
            adaptiveCapabilityOverride = directAdaptiveCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.adaptiveCapabilityOverride,
            spatialAudioCapabilityOverride = directSpatialAudioCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialAudioCapabilityOverride,
            spatialSoundSwitchCapabilityOverride = directSpatialSoundSwitchCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.spatialSoundSwitchCapabilityOverride,
            ancImplementationCapabilityOverride = directAncImplementationCapabilityOverride.takeIf { it != Int.MIN_VALUE } ?: config.ancImplementationCapabilityOverride,
        ).normalized()
    }

    private fun AppConfig.normalized(): AppConfig = copy(
        hyperOsPresentationTypeId = hyperOsPresentationTypeId.normalizedHyperOsPresentationTypeId(),
        logLevel = logLevel.coerceIn(LOG_LEVEL_OFF, LOG_LEVEL_DEBUG),
        islandMode = islandMode.coerceIn(ISLAND_MODE_NONE, ISLAND_MODE_MODULE),
        islandShowTimings = islandShowTimings.normalizedIslandShowTimings(),
        notificationClickAction = notificationClickAction.coerceIn(NOTIFICATION_CLICK_MODULE_POPUP, NOTIFICATION_CLICK_SYSTEM_SETTINGS),
        moreClickAction = moreClickAction.coerceIn(MORE_CLICK_MODULE, MORE_CLICK_SYSTEM_SETTINGS),
        adaptiveCapabilityOverride = adaptiveCapabilityOverride.normalizedCapabilityOverride(),
        spatialAudioCapabilityOverride = spatialAudioCapabilityOverride.normalizedCapabilityOverride(),
        spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride.normalizedCapabilityOverride(),
        ancImplementationCapabilityOverride = ancImplementationCapabilityOverride.normalizedCapabilityOverride(),
    )

    private fun String.normalizedHyperOsPresentationTypeId(): String = trim().takeIf { it.isNotEmpty() } ?: DEFAULT_HYPEROS_PRESENTATION_TYPE_ID

    private fun Int.normalizedCapabilityOverride(): Int = coerceIn(CAPABILITY_OVERRIDE_AUTO, CAPABILITY_OVERRIDE_FORCE_DISABLED)

    private fun Set<Int>.normalizedIslandShowTimings(): Set<Int> = filterTo(mutableSetOf()) {
        it in ISLAND_SHOW_TIMING_CONNECTED..ISLAND_SHOW_TIMING_IN_CASE
    }

    private fun logConfigChange(source: String, oldConfig: AppConfig, newConfig: AppConfig) {
        val changes = changedFields(oldConfig, newConfig)
        if (changes.isEmpty()) {
            Log.d(TAG, "$source config unchanged: $newConfig")
        } else {
            Log.d(TAG, "$source config changed: ${changes.joinToString()}")
        }
    }

    private fun logPrefsSnapshot(source: String, prefs: SharedPreferences, directHyperOsPresentationTypeId: String?, rawConfig: String?) {
        val all = runCatching { prefs.all }.getOrElse { error -> mapOf("<getAllError>" to error.message) }
        Log.d(
            TAG,
            "$source prefs snapshot class=${prefs.javaClass.name} keys=${all.keys.sorted()} " +
                "$PREF_KEY_HYPEROS_PRESENTATION_TYPE_ID=$directHyperOsPresentationTypeId $PREF_KEY_CONFIG_JSON=$rawConfig all=$all"
        )
    }

    private fun changedFields(oldConfig: AppConfig, newConfig: AppConfig): List<String> {
        return buildList {
            if (oldConfig.hyperOsPresentationTypeId != newConfig.hyperOsPresentationTypeId) {
                add("hyperOsPresentationTypeId=${oldConfig.hyperOsPresentationTypeId}->${newConfig.hyperOsPresentationTypeId}")
            }
            if (oldConfig.logLevel != newConfig.logLevel) {
                add("logLevel=${oldConfig.logLevel}->${newConfig.logLevel}")
            }
            if (oldConfig.islandMode != newConfig.islandMode) {
                add("islandMode=${oldConfig.islandMode}->${newConfig.islandMode}")
            }
            if (oldConfig.islandShowTimings != newConfig.islandShowTimings) {
                add("islandShowTimings=${oldConfig.islandShowTimings}->${newConfig.islandShowTimings}")
            }
            if (oldConfig.connectionPopupEnabled != newConfig.connectionPopupEnabled) {
                add("connectionPopupEnabled=${oldConfig.connectionPopupEnabled}->${newConfig.connectionPopupEnabled}")
            }
            if (oldConfig.notificationClickAction != newConfig.notificationClickAction) {
                add("notificationClickAction=${oldConfig.notificationClickAction}->${newConfig.notificationClickAction}")
            }
            if (oldConfig.moreClickAction != newConfig.moreClickAction) {
                add("moreClickAction=${oldConfig.moreClickAction}->${newConfig.moreClickAction}")
            }
            if (oldConfig.adaptiveCapabilityOverride != newConfig.adaptiveCapabilityOverride) {
                add("adaptiveCapabilityOverride=${oldConfig.adaptiveCapabilityOverride}->${newConfig.adaptiveCapabilityOverride}")
            }
            if (oldConfig.spatialAudioCapabilityOverride != newConfig.spatialAudioCapabilityOverride) {
                add("spatialAudioCapabilityOverride=${oldConfig.spatialAudioCapabilityOverride}->${newConfig.spatialAudioCapabilityOverride}")
            }
            if (oldConfig.spatialSoundSwitchCapabilityOverride != newConfig.spatialSoundSwitchCapabilityOverride) {
                add("spatialSoundSwitchCapabilityOverride=${oldConfig.spatialSoundSwitchCapabilityOverride}->${newConfig.spatialSoundSwitchCapabilityOverride}")
            }
            if (oldConfig.ancImplementationCapabilityOverride != newConfig.ancImplementationCapabilityOverride) {
                add("ancImplementationCapabilityOverride=${oldConfig.ancImplementationCapabilityOverride}->${newConfig.ancImplementationCapabilityOverride}")
            }
        }
    }
}
