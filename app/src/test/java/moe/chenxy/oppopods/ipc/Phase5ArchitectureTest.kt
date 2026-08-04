package moe.chenxy.oppopods.ipc

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
            "src/main/java/moe/chenxy/oppopods/runtime/bluetoothprocess/" +
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
        val contract = source("src/main/java/moe/chenxy/oppopods/ipc/HeadphoneIpcContract.kt")
        val security = source("src/main/java/moe/chenxy/oppopods/ipc/IpcSenderPolicy.kt")
        val runtime = source(
            "src/main/java/moe/chenxy/oppopods/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val receiver = source("src/main/java/moe/chenxy/oppopods/ipc/HeadphoneSnapshotReceiver.kt")
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
        val app = source("src/main/java/moe/chenxy/oppopods/OppoPodsApp.kt")
        val upstream = source(
            "src/main/java/moe/chenxy/oppopods/hook/BluetoothUpstreamHeadsetHook.kt",
        )
        val milink = source(
            "src/main/java/moe/chenxy/oppopods/hook/milink/MiLinkServiceHook.kt",
        )
        val settings = source(
            "src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt",
        )
        listOf(app, upstream, milink, settings).forEach {
            assumeTrue(it != null)
            assertTrue(it!!.contains("HeadphoneSnapshotReceiver.register"))
        }
    }

    @Test
    fun `settings snapshot consumer is loaded and included in module scope`() {
        val entry = source("src/main/java/moe/chenxy/oppopods/hook/HookEntry.kt")
        val scope = source("src/main/resources/META-INF/xposed/scope.list")
        assumeTrue(entry != null)
        assumeTrue(scope != null)

        assertTrue(
            entry!!.contains(
                "\"com.android.settings\" -> loadHook(SettingsHeadsetHook",
            ),
        )
        assertTrue(scope!!.lineSequence().any { it.trim() == "com.android.settings" })
    }

    @Test
    fun `snapshot receiver does not republish legacy per-feature broadcasts`() {
        val receiver = source(
            "src/main/java/moe/chenxy/oppopods/ipc/HeadphoneSnapshotReceiver.kt",
        )
        assumeTrue(receiver != null)

        assertFalse(receiver!!.contains("publishLegacy"))
        assertFalse(receiver.contains("legacyIntents"))
        assertFalse(receiver.contains("LegacyPodsAction"))
    }

    @Test
    fun `system integration adapter delegates session authority to runtime host`() {
        val adapter = source(
            "src/main/java/moe/chenxy/oppopods/pods/OppoSystemIntegrationAdapter.kt",
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
            "src/main/java/moe/chenxy/oppopods/hook/HeadsetStateDispatcher.kt",
        )
        val adapter = source(
            "src/main/java/moe/chenxy/oppopods/pods/OppoSystemIntegrationAdapter.kt",
        )
        assumeTrue(dispatcher != null)
        assumeTrue(adapter != null)

        assertTrue(dispatcher!!.contains("connectPodAutomatically(context, device, prefs)"))
        assertFalse(dispatcher.contains("if (!isOppoPod(device)) return@post"))
        assertTrue(adapter!!.contains("BluetoothProcessRuntimeHost.canAutoConnect"))
    }

    @Test
    fun `notification projection consumes every supported vendor snapshot after ready`() {
        val runtime = source(
            "src/main/java/moe/chenxy/oppopods/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val adapter = source(
            "src/main/java/moe/chenxy/oppopods/pods/OppoSystemIntegrationAdapter.kt",
        )
        assumeTrue(runtime != null)
        assumeTrue(adapter != null)

        assertTrue(runtime!!.contains("OppoSystemIntegrationAdapter.onEngineSnapshot(value)"))
        assertFalse(
            Regex(
                """if\s*\(value\.profile\?\.vendorId\s*==\s*VendorId\.OPPO\)\s*\{\s*""" +
                    """OppoSystemIntegrationAdapter\.onEngineSnapshot""",
            ).containsMatchIn(runtime),
        )
        assertTrue(adapter!!.contains("notificationReady = snapshot.connection is SessionState.Ready"))
        assertTrue(adapter.contains("canWrite(FeatureId.NOISE_CONTROL)"))
    }

    @Test
    fun `official island bridge uses exact Ready snapshot and Xiaomi native path`() {
        val upstream = source(
            "src/main/java/moe/chenxy/oppopods/hook/BluetoothUpstreamHeadsetHook.kt",
        )
        val projection = source(
            "src/main/java/moe/chenxy/oppopods/integration/OfficialHeadsetIslandProjection.kt",
        )
        assumeTrue(upstream != null)
        assumeTrue(projection != null)

        assertTrue(upstream!!.contains("showOfficialHeadsetIsland(state, \"snapshot\")"))
        assertTrue(upstream.contains("ensureOfficialConnectManager(notification, device, payload.requestFlag)"))
        assertTrue(upstream.contains("\"addConnectManager\", device, requestFlag, 0"))
        assertTrue(upstream.contains("\"checkIfSetStopShowDialog\""))
        assertTrue(upstream.contains("official island Fast Connect wait bypassed"))
        assertTrue(upstream.contains("state.supportsAddress(pendingOfficialIslandAddress)"))
        assertTrue(upstream.contains("fakeDeviceId(),"))
        assertTrue(upstream.contains("\"showConnectedToast\""))
        assertTrue(upstream.contains("current.supportsAddress(state.address)"))
        assertTrue(projection!!.contains("!connected || deviceId == null || address.isNullOrBlank()"))
        assertFalse(projection.contains("VendorId.OPPO"))
        assertFalse(projection.contains("VendorId.SONY"))
    }

    @Test
    fun `default runtime logs do not format identity bearing addresses`() {
        val runtime = source(
            "src/main/java/moe/chenxy/oppopods/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        val dispatcher = source(
            "src/main/java/moe/chenxy/oppopods/hook/HeadsetStateDispatcher.kt",
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
