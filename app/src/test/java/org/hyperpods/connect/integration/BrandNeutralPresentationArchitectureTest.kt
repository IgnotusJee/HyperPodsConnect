package org.hyperpods.connect.integration

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandNeutralPresentationArchitectureTest {
    private val sourceRoot: File = listOf(
        File("src/main/java/org/hyperpods/connect"),
        File("app/src/main/java/org/hyperpods/connect"),
    ).first(File::isDirectory)

    @Test
    fun `presentation and HyperOS boundaries cannot import vendor protocols`() {
        val scoped = buildList {
            addAll(File(sourceRoot, "integration").walkTopDown().filter { it.extension == "kt" })
            addAll(File(sourceRoot, "hook").walkTopDown().filter { it.extension == "kt" })
            addAll(File(sourceRoot, "ui").walkTopDown().filter { it.extension == "kt" })
            addAll(File(sourceRoot, "utils/miuiStrongToast").walkTopDown().filter { it.extension == "kt" })
            add(File(sourceRoot, "PopupActivity.kt"))
            add(File(sourceRoot, "pods/HeadphoneSessionCoordinator.kt"))
            add(File(sourceRoot, "utils/FocusIslandUtil.kt"))
        }

        scoped.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} imports OPPO protocol", source.contains("protocol.oppo"))
            assertFalse("${file.name} imports Sony protocol", source.contains("protocol.sony"))
            assertFalse(
                "${file.name} contains a brand/model decision",
                Regex("""(?i)(contains|startsWith|equals)\([^\n]*(oppo|sony|oneplus|linkbuds|wh-|wf-|enco)""")
                    .containsMatchIn(source),
            )
        }
    }

    @Test
    fun `vendor providers are registered only by the driver catalog`() {
        val providerReferences = sourceRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("OppoDriverProvider") || source.contains("SonyDriverProvider")
            }
            .toList()

        assertEqualsSingleCatalog(providerReferences)
    }

    @Test
    fun `vendor protocol types cannot escape the driver boundary`() {
        val driverRoot = File(sourceRoot, "runtime/bluetoothprocess/driver")
        sourceRoot.walkTopDown()
            .filter { it.extension == "kt" && !it.toPath().startsWith(driverRoot.toPath()) }
            .forEach { file ->
                val source = file.readText()
                assertFalse("${file.path} imports a vendor protocol", source.contains("protocol.oppo"))
                assertFalse("${file.path} imports a vendor protocol", source.contains("protocol.sony"))
                assertFalse("${file.path} branches on OPPO", source.contains("VendorId.OPPO"))
                assertFalse("${file.path} branches on Sony", source.contains("VendorId.SONY"))
            }
    }

    @Test
    fun `fresh app identity has no legacy package action provider or HeyTap entry`() {
        val mainRoot = requireNotNull(
            sourceRoot.parentFile?.parentFile?.parentFile?.parentFile,
        )
        val checked = sequenceOf(
            sourceRoot.walkTopDown().filter(File::isFile),
            File(mainRoot, "AndroidManifest.xml").walkTopDown().filter(File::isFile),
        ).flatten()
        val forbidden = listOf(
            "moe.chenxy.oppopods",
            "chen.action.oppopods",
            "OppoPods",
            "com.heytap.headset",
        )
        checked.forEach { file ->
            val source = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.path} retains $token", source.contains(token))
            }
        }
        assertTrue(File(mainRoot, "AndroidManifest.xml").readText().contains("org.hyperpods.connect.podimages"))
    }

    @Test
    fun `official island type is isolated from module island and drivers`() {
        val directReaders = sourceRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains("ConfigManager.hyperOsPresentationTypeId()") }
            .toList()
        assertTrue(
            "ROM compatibility type has direct readers outside its config boundary",
            directReaders.singleOrNull()?.name == "HyperOsOfficialIslandConfig.kt",
        )
        assertFalse(
            File(sourceRoot, "utils/FocusIslandUtil.kt").readText()
                .contains("HyperOsOfficialIslandConfig"),
        )
        assertFalse(
            File(sourceRoot, "runtime/bluetoothprocess/driver/DefaultDriverCatalog.kt").readText()
                .contains("hyperOsPresentationTypeId"),
        )
    }

    private fun assertEqualsSingleCatalog(files: List<File>) {
        assertTrue("driver providers must be referenced", files.isNotEmpty())
        assertTrue(
            "driver providers escaped the catalog: ${files.joinToString { it.path }}",
            files.all { it.name == "DefaultDriverCatalog.kt" },
        )
    }
}
