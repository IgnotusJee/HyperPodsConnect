package moe.chenxy.headphones.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the dependency rule that makes this module worth having.
 *
 * The Gradle setup already prevents Android types from resolving here, but a
 * plain Kotlin module can still acquire a Compose or Xposed dependency by
 * someone editing the build file. Checking the sources keeps the intent visible
 * where it is easy to read, and fails with a message that says why.
 */
class ArchitectureTest {

    private val forbiddenPrefixes = listOf(
        "android.",
        "androidx.",
        "com.android.",
        "de.robv.android.xposed",
        "io.github.libxposed",
        "top.yukonga.miuix",
        "moe.chenxy.oppopods",
    )

    private fun sourceRoot(): File? =
        listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).firstOrNull { it.isDirectory }

    @Test
    fun `core has no android xposed or app imports`() {
        val root = sourceRoot()
        assumeTrue("core sources not reachable from the test working directory", root != null)

        val offenders = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.startsWith("import ") }
                    .filter { line -> forbiddenPrefixes.any { line.removePrefix("import ").startsWith(it) } }
                    .map { "${file.name}: ${it.trim()}" }
            }
            .toList()

        assertTrue(
            "core must stay free of platform and app dependencies:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `core sources exist so the previous check is not vacuous`() {
        val root = sourceRoot()
        assumeTrue("core sources not reachable from the test working directory", root != null)

        val fileCount = root!!.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("expected core sources to scan, found none", fileCount > 0)
    }
}
