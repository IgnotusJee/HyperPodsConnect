package org.hyperpods.connect.ui.pages

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.hyperpods.connect.R
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.pods.GameModeImplementation
import org.hyperpods.connect.profile.DeviceProfileRepository
import org.hyperpods.connect.ui.AppLocale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    desktopIconHidden: MutableState<Boolean> = mutableStateOf(false),
    onDesktopIconHiddenChange: (Boolean) -> Unit = {},
    logLevel: MutableState<Int> = mutableStateOf(ConfigManager.LOG_LEVEL_BASIC),
    onLogLevelChange: (Int) -> Unit = {},
    islandMode: MutableState<Int> = mutableStateOf(ConfigManager.ISLAND_MODE_OFFICIAL),
    onIslandModeChange: (Int) -> Unit = {},
    islandShowTimings: MutableState<Set<Int>> = mutableStateOf(emptySet()),
    onIslandShowTimingsChange: (Set<Int>) -> Unit = {},
    connectionPopupEnabled: MutableState<Boolean> = mutableStateOf(true),
    onConnectionPopupEnabledChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
    autoGameMode: MutableState<Boolean> = mutableStateOf(false),
    onAutoGameModeChange: (Boolean) -> Unit = {},
    gameModeImplementation: MutableState<GameModeImplementation> = mutableStateOf(GameModeImplementation.STANDARD),
    onGameModeImplementationChange: (GameModeImplementation) -> Unit = {},
    notificationClickAction: MutableState<Int> = mutableStateOf(ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP),
    onNotificationClickActionChange: (Int) -> Unit = {},
    moreClickAction: MutableState<Int> = mutableStateOf(ConfigManager.MORE_CLICK_MODULE),
    onMoreClickActionChange: (Int) -> Unit = {},
    adaptiveCapabilityOverride: MutableState<Int> = mutableStateOf(ConfigManager.CAPABILITY_OVERRIDE_AUTO),
    spatialAudioCapabilityOverride: MutableState<Int> = mutableStateOf(ConfigManager.CAPABILITY_OVERRIDE_AUTO),
    spatialSoundSwitchCapabilityOverride: MutableState<Int> = mutableStateOf(ConfigManager.CAPABILITY_OVERRIDE_AUTO),
    onOpenDeviceCapabilities: () -> Unit = {},
    hyperOsPresentationTypeId: MutableState<String> = mutableStateOf(ConfigManager.DEFAULT_HYPEROS_PRESENTATION_TYPE_ID),
    onHyperOsPresentationTypeIdChange: (String) -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val profileRepository = remember(context) {
        DeviceProfileRepository(
            context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    val exportProfiles = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openOutputStream(uri, "wt").use { output ->
                requireNotNull(output) { "Unable to open export destination" }
                output.writer(Charsets.UTF_8).use {
                    it.write(profileRepository.exportArchive())
                }
            }
        }
        Toast.makeText(
            context,
            if (result.isSuccess) R.string.profile_exported else R.string.profile_archive_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }
    val importProfiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val value = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open compatibility archive" }
                input.reader(Charsets.UTF_8).use { it.readText() }
            }
            profileRepository.importArchive(value).archive.profiles.size
        }
        Toast.makeText(
            context,
            result.fold(
                onSuccess = { resources.getString(R.string.profile_imported, it) },
                onFailure = { resources.getString(R.string.profile_archive_failed) },
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }
    val languageOptions = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_chinese),
        stringResource(R.string.language_english),
    )
    val logLevelValues = listOf(ConfigManager.LOG_LEVEL_OFF, ConfigManager.LOG_LEVEL_BASIC, ConfigManager.LOG_LEVEL_DEBUG)
    val logLevelOptions = listOf(
        stringResource(R.string.log_level_off),
        stringResource(R.string.log_level_basic),
        stringResource(R.string.log_level_debug),
    )
    val islandModeValues = listOf(ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_OFFICIAL, ConfigManager.ISLAND_MODE_MODULE)
    val islandModeOptions = listOf(
        stringResource(R.string.island_mode_none),
        stringResource(R.string.island_mode_official),
        stringResource(R.string.island_mode_module),
    )
    val islandShowTimingOptions = listOf(
        ConfigManager.ISLAND_SHOW_TIMING_CONNECTED to stringResource(R.string.island_show_timing_connected),
        ConfigManager.ISLAND_SHOW_TIMING_WEARING to stringResource(R.string.island_show_timing_wearing),
        ConfigManager.ISLAND_SHOW_TIMING_REMOVED to stringResource(R.string.island_show_timing_removed),
        ConfigManager.ISLAND_SHOW_TIMING_IN_CASE to stringResource(R.string.island_show_timing_in_case),
    )
    val islandShowTimingEntries = remember(islandShowTimings.value, islandShowTimingOptions) {
        listOf(
            DropdownEntry(
                items = islandShowTimingOptions.map { (value, text) ->
                    DropdownItem(
                        text = text,
                        selected = value in islandShowTimings.value,
                        onClick = {
                            val selected = islandShowTimings.value
                            onIslandShowTimingsChange(
                                if (value in selected) selected - value else selected + value
                            )
                        },
                    )
                }
            )
        )
    }
    val notificationClickActionValues = listOf(
        ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
        ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS,
    )
    val notificationClickActionOptions = listOf(
        stringResource(R.string.notification_click_module_popup),
        stringResource(R.string.click_action_system_settings),
    )
    val moreClickActionValues = listOf(
        ConfigManager.MORE_CLICK_MODULE,
        ConfigManager.MORE_CLICK_SYSTEM_SETTINGS,
    )
    val moreClickActionOptions = listOf(
        stringResource(R.string.click_action_module),
        stringResource(R.string.click_action_system_settings),
    )
    val gameModeImplementationOptions = listOf(
        stringResource(R.string.game_mode_implementation_standard),
        stringResource(R.string.game_mode_implementation_compatible),
    )
    val adaptiveCapabilityText = capabilityOverrideLabel(adaptiveCapabilityOverride.value)
    val spatialAudioCapabilityText = capabilityOverrideLabel(spatialAudioCapabilityOverride.value)
    val spatialSoundCapabilityText = capabilityOverrideLabel(spatialSoundSwitchCapabilityOverride.value)
    val deviceCapabilitySummary = listOf(
        stringResource(R.string.adaptive_mode) to adaptiveCapabilityText,
        stringResource(R.string.spatial_audio) to spatialAudioCapabilityText,
        stringResource(R.string.spatial_sound) to spatialSoundCapabilityText,
    ).joinToString(" / ") { (label, value) ->
        "$label: $value"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            Card {
                BasicComponent(
                    title = stringResource(R.string.theme_title),
                    summary = stringResource(R.string.theme_color_summary),
                    onClick = onOpenTheme,
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.export_compatibility_profiles),
                    summary = stringResource(R.string.export_compatibility_profiles_summary),
                    onClick = {
                        exportProfiles.launch("hyperpods-compatibility-profiles.json")
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.import_compatibility_profiles),
                    summary = stringResource(R.string.import_compatibility_profiles_summary),
                    onClick = { importProfiles.launch(arrayOf("application/json", "text/plain")) },
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_summary),
                    items = languageOptions,
                    selectedIndex = appLanguage.value.coerceIn(languageOptions.indices),
                    onSelectedIndexChange = { onAppLanguageChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_level),
                    summary = stringResource(R.string.log_level_summary),
                    items = logLevelOptions,
                    selectedIndex = logLevelValues.indexOf(logLevel.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onLogLevelChange(logLevelValues[it]) }
                )
                SwitchPreference(
                    title = stringResource(R.string.hide_desktop_icon),
                    summary = stringResource(R.string.hide_desktop_icon_summary),
                    checked = desktopIconHidden.value,
                    onCheckedChange = { onDesktopIconHiddenChange(it) }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.device_capabilities),
                    summary = deviceCapabilitySummary,
                    onClick = onOpenDeviceCapabilities,
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.island_mode),
                    summary = stringResource(R.string.island_mode_summary),
                    items = islandModeOptions,
                    selectedIndex = islandModeValues.indexOf(islandMode.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onIslandModeChange(islandModeValues[it]) }
                )
                if (islandMode.value == ConfigManager.ISLAND_MODE_MODULE) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.island_show_timing),
                        summary = stringResource(R.string.island_show_timing_summary),
                        entries = islandShowTimingEntries,
                        collapseOnSelection = false,
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.connection_popup),
                    summary = stringResource(R.string.connection_popup_summary),
                    checked = connectionPopupEnabled.value,
                    onCheckedChange = onConnectionPopupEnabledChange,
                )
                SwitchPreference(
                    title = stringResource(R.string.auto_game_mode),
                    checked = autoGameMode.value,
                    onCheckedChange = { onAutoGameModeChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.game_mode_implementation),
                    items = gameModeImplementationOptions,
                    selectedIndex = GameModeImplementation.selectedIndexOf(gameModeImplementation.value),
                    onSelectedIndexChange = {
                        onGameModeImplementationChange(GameModeImplementation.fromSelectedIndex(it))
                    }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.notification_click_action),
                    summary = stringResource(R.string.notification_click_action_summary),
                    items = notificationClickActionOptions,
                    selectedIndex = notificationClickActionValues.indexOf(notificationClickAction.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onNotificationClickActionChange(notificationClickActionValues[it]) }
                )
                if (notificationClickAction.value == ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.more_click_action),
                        summary = stringResource(R.string.more_click_action_summary),
                        items = moreClickActionOptions,
                        selectedIndex = moreClickActionValues.indexOf(moreClickAction.value).coerceAtLeast(0),
                        onSelectedIndexChange = { onMoreClickActionChange(moreClickActionValues[it]) }
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.hyperos_presentation_type_id),
                    summary = stringResource(R.string.hyperos_presentation_type_id_summary)
                )
                TextField(
                    value = hyperOsPresentationTypeId.value,
                    onValueChange = { onHyperOsPresentationTypeIdChange(it.trim()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about),
                    summary = stringResource(R.string.app_name),
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun capabilityOverrideLabel(value: Int): String = when (value) {
    ConfigManager.CAPABILITY_OVERRIDE_FORCE_ENABLED -> stringResource(R.string.capability_force_enabled)
    ConfigManager.CAPABILITY_OVERRIDE_FORCE_DISABLED -> stringResource(R.string.capability_force_disabled)
    else -> stringResource(R.string.capability_auto)
}
