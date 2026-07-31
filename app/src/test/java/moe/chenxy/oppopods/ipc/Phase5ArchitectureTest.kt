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
    fun `snapshot receiver does not republish legacy per-feature broadcasts`() {
        val receiver = source(
            "src/main/java/moe/chenxy/oppopods/ipc/HeadphoneSnapshotReceiver.kt",
        )
        assumeTrue(receiver != null)

        assertFalse(receiver!!.contains("publishLegacy"))
        assertFalse(receiver.contains("legacyIntents"))
        assertFalse(receiver.contains("OppoPodsAction"))
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
