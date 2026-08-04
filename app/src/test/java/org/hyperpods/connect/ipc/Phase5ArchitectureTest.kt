package org.hyperpods.connect.ipc

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Phase5ArchitectureTest {
    private fun source(relative: String): String? {
        val paths = listOf(File(relative), File("app/$relative"))
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `bluetooth runtime owns one session manager and versioned receiver`() {
        val source = source(
            "src/main/java/org/hyperpods/connect/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        assumeTrue(source != null)

        assertTrue(source!!.contains("HeadphoneSessionManager(registry, scope)"))
        assertTrue(source.contains("ACTION_HEADPHONE_COMMAND"))
        assertTrue(source.contains("HeadphoneSnapshotPayload.from"))
        assertFalse(source.contains("BluetoothSocket"))
    }

    @Test
    fun `V3 IPC uses stable connection values and authenticated receivers`() {
        val contract = source("src/main/java/org/hyperpods/connect/ipc/HeadphoneIpcContract.kt")
        val security = source("src/main/java/org/hyperpods/connect/ipc/IpcSenderPolicy.kt")
        val runtime = source(
            "src/main/java/org/hyperpods/connect/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val receiver = source("src/main/java/org/hyperpods/connect/ipc/HeadphoneSnapshotReceiver.kt")
        assumeTrue(contract != null)
        assumeTrue(security != null)
        assumeTrue(runtime != null)
        assumeTrue(receiver != null)

        assertTrue(contract!!.contains("HEADPHONE_COMMAND_V3"))
        assertTrue(contract.contains("enum class IpcConnectionState"))
        assertFalse(contract.contains("::class.simpleName"))
        assertTrue(security!!.contains("setShareIdentityEnabled(true)"))
        assertTrue(runtime!!.contains("isSentFrom(IpcSenderPolicy.commandSenders)"))
        assertTrue(receiver!!.contains("isSentFrom(IpcSenderPolicy.bluetoothOnly)"))
    }

    @Test
    fun `snapshot receiver is installed in app milink xiaomi and settings consumers`() {
        val app = source("src/main/java/org/hyperpods/connect/HyperPodsConnectApp.kt")
        val upstream = source(
            "src/main/java/org/hyperpods/connect/hook/BluetoothUpstreamHeadsetHook.kt",
        )
        val milink = source(
            "src/main/java/org/hyperpods/connect/hook/milink/MiLinkServiceHook.kt",
        )
        val settings = source(
            "src/main/java/org/hyperpods/connect/hook/SettingsHeadsetHook.kt",
        )
        listOf(app, upstream, milink, settings).forEach {
            assumeTrue(it != null)
            assertTrue(it!!.contains("HeadphoneSnapshotReceiver.register"))
        }
    }

    @Test
    fun `settings snapshot consumer is loaded and included in module scope`() {
        val entry = source("src/main/java/org/hyperpods/connect/hook/HookEntry.kt")
        val scope = source("src/main/resources/META-INF/xposed/scope.list")
        assumeTrue(entry != null)
        assumeTrue(scope != null)

        assertTrue(
            entry!!.contains("HyperOsHookTarget.SETTINGS ->"),
        )
        assertTrue(scope!!.lineSequence().any { it.trim() == "com.android.settings" })
    }

    @Test
    fun `snapshot receiver does not republish legacy per-feature broadcasts`() {
        val receiver = source(
            "src/main/java/org/hyperpods/connect/ipc/HeadphoneSnapshotReceiver.kt",
        )
        assumeTrue(receiver != null)

        assertFalse(receiver!!.contains("publishLegacy"))
        assertFalse(receiver.contains("legacyIntents"))
        assertFalse(receiver.contains("HeadphoneActionContract"))
    }

    @Test
    fun `system integration adapter delegates session authority to runtime host`() {
        val adapter = source(
            "src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt",
        )
        assumeTrue(adapter != null)

        assertTrue(adapter!!.contains("BluetoothProcessRuntimeHost.connect"))
        assertTrue(adapter.contains("BluetoothProcessRuntimeHost.execute"))
        assertFalse(adapter.contains("private var session:"))
        assertFalse(adapter.contains("OppoDriverProvider("))
        assertFalse(adapter.contains("AndroidSppTransportFactory("))
    }

    @Test
    fun `A2DP automatic path sends every vendor through the evidence gate`() {
        val dispatcher = source(
            "src/main/java/org/hyperpods/connect/hook/HeadsetStateDispatcher.kt",
        )
        val adapter = source(
            "src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt",
        )
        assumeTrue(dispatcher != null)
        assumeTrue(adapter != null)

        assertTrue(dispatcher!!.contains("connectPodAutomatically(context, device, prefs)"))
        assertFalse(dispatcher.contains("if (!isCurrentSupportedHeadset(device)) return@post"))
        assertTrue(adapter!!.contains("BluetoothProcessRuntimeHost.canAutoConnect"))
    }

    @Test
    fun `notification projection consumes every supported vendor snapshot after ready`() {
        val runtime = source(
            "src/main/java/org/hyperpods/connect/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val adapter = source(
            "src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt",
        )
        val presentationController = source(
            "src/main/java/org/hyperpods/connect/integration/HeadphonePresentationController.kt",
        )
        assumeTrue(runtime != null)
        assumeTrue(adapter != null)
        assumeTrue(presentationController != null)

        assertTrue(runtime!!.contains("HeadphoneSessionCoordinator.onEngineSnapshot(value)"))
        assertFalse(
            Regex(
                """if\s*\(value\.profile\?\.vendorId\s*==\s*VendorId\.OPPO\)\s*\{\s*""" +
                    """HeadphoneSessionCoordinator\.onEngineSnapshot""",
            ).containsMatchIn(runtime),
        )
        assertTrue(adapter!!.contains("notificationReady = snapshot.connection is SessionState.Ready"))
        assertTrue(adapter.contains("presentationController.onSnapshot("))
        assertTrue(adapter.contains("clearConnectedNotification(connection)"))
        assertTrue(adapter.contains("canWrite(FeatureId.NOISE_CONTROL)"))
        assertTrue(presentationController!!.contains("effects.onNotificationInvalidated(snapshot.connection)"))
    }

    @Test
    fun `notification cancellation also clears the transient focus island`() {
        val hook = source("src/main/java/org/hyperpods/connect/hook/MiBluetoothToastHook.kt")
        val island = source("src/main/java/org/hyperpods/connect/utils/FocusIslandUtil.kt")
        assumeTrue(hook != null)
        assumeTrue(island != null)

        assertTrue(hook!!.contains("FocusIslandUtil.cancelBatteryIsland(context)"))
        assertTrue(island!!.contains("fun cancelBatteryIsland(context: Context)"))
        assertTrue(island.contains("nm.cancel(NOTIFICATION_ID)"))
    }

    @Test
    fun `LE Audio disconnects use the device notification cleanup path`() {
        val dispatcher = source(
            "src/main/java/org/hyperpods/connect/hook/HeadsetStateDispatcher.kt",
        )
        val adapter = source(
            "src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt",
        )
        assumeTrue(dispatcher != null)
        assumeTrue(adapter != null)

        assertTrue(
            dispatcher!!.contains("BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED"),
        )
        assertTrue(
            dispatcher.contains("HeadphoneSessionCoordinator.onBluetoothProfileDisconnected(context, device)"),
        )
        assertTrue(adapter!!.contains("fun onBluetoothProfileDisconnected("))
        assertTrue(adapter.contains("cancelPodsNotificationByMiuiBt(context, device)"))
    }

    @Test
    fun `LE Audio connected edge starts the trusted profile session immediately`() {
        val dispatcher = source(
            "src/main/java/org/hyperpods/connect/hook/HeadsetStateDispatcher.kt",
        )
        val adapter = source(
            "src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt",
        )
        val runtime = source(
            "src/main/java/org/hyperpods/connect/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        assumeTrue(dispatcher != null)
        assumeTrue(adapter != null)
        assumeTrue(runtime != null)

        assertTrue(dispatcher!!.contains("state == BluetoothProfile.STATE_CONNECTED"))
        assertTrue(
            dispatcher.contains("scheduleProfileConnect(context, device)"),
        )
        assertTrue(dispatcher.contains("LE_AUDIO_GROUP_SETTLE_MS = 250L"))
        assertTrue(dispatcher.contains("profileHandler.postDelayed(connect, LE_AUDIO_GROUP_SETTLE_MS)"))
        assertTrue(dispatcher.contains("leAudioGroupId(device)"))
        assertTrue(dispatcher.contains("it.name == \"getGroupId\""))
        assertTrue(adapter!!.contains("isCurrentProfileDevice(device, profileGroupId)"))
        assertTrue(adapter.contains("ignore duplicate connected profile member"))
        assertTrue(adapter!!.contains("if (!profileConnected) delay(500)"))
        assertTrue(runtime!!.contains("DriverRegistry.canConnectFromProfile(it, candidate)"))
    }

    @Test
    fun `official island bridge uses exact Ready snapshot and Xiaomi native path`() {
        val upstream = source(
            "src/main/java/org/hyperpods/connect/hook/BluetoothUpstreamHeadsetHook.kt",
        )
        val projection = source(
            "src/main/java/org/hyperpods/connect/integration/OfficialHeadsetIslandProjection.kt",
        )
        assumeTrue(upstream != null)
        assumeTrue(projection != null)

        assertTrue(upstream!!.contains("showOfficialHeadsetIsland(state, \"snapshot\")"))
        assertTrue(upstream.contains("\"addConnectManager\""))
        assertTrue(upstream.contains("\"showNewConnectedToast\""))
        assertTrue(upstream.contains("\"handleShowConnectedToast\""))
        assertTrue(upstream.contains("\"checkIfSetStopShowDialog\""))
        assertTrue(upstream.contains("official island Fast Connect wait bypassed"))
        assertTrue(upstream.contains("state.supportsAddress(pendingOfficialIslandAddress)"))
        assertTrue(upstream.contains("HyperOsOfficialIslandConfig.presentationTypeId,"))
        assertTrue(upstream.contains("current.supportsAddress(state.address)"))
        assertTrue(projection!!.contains("!connected || deviceId == null || address.isNullOrBlank()"))
        assertFalse(projection.contains("VendorId.OPPO"))
        assertFalse(projection.contains("VendorId.SONY"))
    }

    @Test
    fun `MiLink headset activity routes only the exact snapshot device with ROM fallback`() {
        val milink = source(
            "src/main/java/org/hyperpods/connect/hook/milink/MiLinkServiceHook.kt",
        )
        val popup = source("src/main/java/org/hyperpods/connect/PopupActivity.kt")
        assumeTrue(milink != null)
        assumeTrue(popup != null)

        assertTrue(milink!!.contains("hookSwitchToHeadsetActivity()"))
        assertTrue(milink.contains("hookHeadsetInfoConstruction()"))
        assertTrue(milink.contains("setObjectField(instance, \"deviceId\""))
        assertTrue(milink.contains("setObjectField(instance, \"powers\""))
        assertTrue(milink.contains("setObjectField(instance, \"audioEffectState\""))
        assertTrue(milink.contains("if (!isExactSnapshotDevice(device)) return@hookBefore"))
        assertTrue(milink.contains("state.deviceId == null || !state.supportsAddress(address)"))
        assertTrue(milink.contains("if (openModuleHeadsetActivity(device))"))
        assertTrue(milink.contains("result = null"))
        assertTrue(milink.contains("getObjectField(owner, \"mContext\")"))
        assertTrue(milink.contains("putExtra(EXTRA_FORCE_MODULE_POPUP, true)"))
        assertTrue(popup!!.contains("!forceModulePopup && appConfig.notificationClickAction"))
    }

    @Test
    fun `connected popup consumes an exact ready snapshot and never falls back to stale UI`() {
        val adapter = source("src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt")
        val presentationController = source(
            "src/main/java/org/hyperpods/connect/integration/HeadphonePresentationController.kt",
        )
        val coordinator = source("src/main/java/org/hyperpods/connect/pods/ConnectedPopupCoordinator.kt")
        val snapshotGate = source(
            "src/main/java/org/hyperpods/connect/pods/ConnectionPopupSnapshotGate.kt",
        )
        val popup = source("src/main/java/org/hyperpods/connect/PopupActivity.kt")
        assumeTrue(adapter != null)
        assumeTrue(presentationController != null)
        assumeTrue(coordinator != null)
        assumeTrue(snapshotGate != null)
        assumeTrue(popup != null)

        assertTrue(adapter!!.contains("maybeShowConnectedPopup(snapshot, enteringReady)"))
        assertTrue(adapter.contains("presentationController.onSnapshot("))
        assertTrue(presentationController!!.contains("connectionPresentationGate.claim("))
        assertTrue(presentationController.contains("enteringReady && allowConnectedPresentation"))
        assertTrue(adapter.contains("snapshot.state.batteries.values.map(BatteryState::level)"))
        assertTrue(adapter.contains("isConnectedPopupEnvironmentEligible(context)"))
        assertTrue(coordinator!!.contains("handledKey = key"))
        assertTrue(coordinator.contains("batteryLevels.none { it in 1..100 }"))
        assertTrue(snapshotGate!!.contains("state.generationId == expectedGeneration"))
        assertTrue(snapshotGate.contains("state.emittedAtMillis >= expectedEmittedAtMillis"))
        assertTrue(snapshotGate.contains("state.deviceId == expectedDeviceId"))
        assertTrue(snapshotGate.contains("state.supportsAddress(expectedAddress)"))
        assertTrue(snapshotGate.contains("ConnectionPopupSnapshotDecision.DISMISS"))
        assertTrue(popup!!.contains("connectionPopupSnapshotDecision("))
        assertTrue(popup.contains("if (connectionEdgePopup)"))
        assertTrue(popup.contains("onUnavailable()"))
    }

    @Test
    fun `default runtime logs do not format identity bearing addresses`() {
        val runtime = source(
            "src/main/java/org/hyperpods/connect/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val dispatcher = source(
            "src/main/java/org/hyperpods/connect/hook/HeadsetStateDispatcher.kt",
        )
        assumeTrue(runtime != null)
        assumeTrue(dispatcher != null)

        assertTrue(runtime!!.contains("value.connection::class.simpleName"))
        assertFalse(runtime.contains("connection=\${value.connection}"))
        dispatcher!!.lineSequence()
            .filter { it.contains("Log.") }
            .forEach { line -> assertFalse(line.contains("device.address")) }
    }
}
