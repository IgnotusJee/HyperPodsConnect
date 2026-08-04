package moe.chenxy.oppopods.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.oppopods.config.DeviceArtworkSelector
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.ipc.HeadphoneIpcContract
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SonyOfficialNetworkCandidate(
    val selector: DeviceArtworkSelector,
    val resourceUrls: Map<PodImageResource, String>,
)

object RootManager {
    private const val TAG = "OppoPods-RootManager"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val sonyImageUrlRegex = Regex(
        "^https://hpc-image\\.data-gateway\\.seeds\\.services/[0-9a-fA-F-]{36}\\.(png|jpe?g|webp)$",
    )

    fun restartPackages(context: Context, packages: Collection<String>): Boolean {
        val targets = validRestartTargets(packages)
        if (targets.isEmpty()) return false
        val command = buildRestartCommand(targets) ?: return false

        val restartedWithRoot = if (File(SU_PATH).canExecute()) {
            runCatching {
                val process = ProcessBuilder(SU_PATH, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    Log.e(TAG, "scope restart failed exit=$exitCode output=$output")
                } else {
                    Log.i(TAG, "scope restart completed")
                }
                exitCode == 0
            }.onFailure {
                Log.e(TAG, "scope restart failed to execute", it)
            }.getOrDefault(false)
        } else {
            false
        }
        if (restartedWithRoot) return true

        Log.w(TAG, "root restart unavailable; falling back to hooked-process restart")
        return runCatching {
            targets.forEach { target ->
                context.sendIdentitySharedBroadcast(
                    Intent(HeadphoneIpcContract.ACTION_RESTART_SCOPE).apply {
                        setPackage(target)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    },
                )
            }
            true
        }.onFailure {
            Log.e(TAG, "hooked-process restart broadcast failed", it)
        }.getOrDefault(false)
    }

    /**
     * HyperOS keeps both Bluetooth packages alive as persistent processes, where
     * `am force-stop` is accepted but does not terminate the process. Kill those
     * process names directly; MiLink remains a regular package-level restart so
     * all of its suffixed processes are stopped together.
     */
    internal fun buildRestartCommand(packages: Collection<String>): String? {
        val targets = validRestartTargets(packages)
        if (targets.isEmpty()) return null
        val commands = targets.map { target ->
            when (target) {
                "com.android.bluetooth", "com.xiaomi.bluetooth" ->
                    "if pidof $target >/dev/null 2>&1; then killall $target; fi"
                else -> "am force-stop $target"
            }
        }
        return commands.joinToString(
            separator = "; ",
            prefix = "status=0; ",
            postfix = "; exit \$status",
        ) { command -> "($command) || status=1" }
    }

    private fun validRestartTargets(packages: Collection<String>): List<String> =
        packages.distinct().filter { it.matches(packageNameRegex) }

    fun hasRootAccess(): Boolean {
        return runRootText("echo yes")?.trim() == "yes"
    }

    /** Resolves Sony's public CDN using the color code reported by the headset. */
    internal fun resolveSonyOfficialNetworkCandidate(
        deviceName: String,
        colorId: String?,
    ): SonyOfficialNetworkCandidate? = SonyOfficialArtworkCatalog.resolve(deviceName, colorId)

    /** Keeps the model-only fallback for callers without an address/color source. */
    internal fun resolveSonyOfficialNetworkCandidate(deviceName: String): SonyOfficialNetworkCandidate? =
        SonyOfficialArtworkCatalog.resolve(deviceName)

    fun readSonyOfficialNetworkImages(
        candidate: SonyOfficialNetworkCandidate,
    ): Map<PodImageResource, ByteArray>? {
        val images = candidate.resourceUrls.mapNotNull { (resource, url) ->
            readOfficialSonyUrl(url)?.let { resource to it }
        }.toMap()
        return images.takeIf { it.isNotEmpty() && it.size == candidate.resourceUrls.size }
    }

    private fun readOfficialSonyUrl(url: String): ByteArray? {
        if (!url.matches(sonyImageUrlRegex)) return null
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Sony CDN returned HTTP ${connection.responseCode} url=$url")
                    return null
                }
                val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                if (contentType != "application/octet-stream" && contentType != "binary/octet-stream" && !contentType.startsWith("image/")) {
                    Log.w(TAG, "Sony CDN returned unexpected content type=$contentType url=$url")
                    return null
                }
                connection.inputStream.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) return null
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray().takeIf(ByteArray::isNotEmpty)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Sony official image download failed url=$url", it) }.getOrNull()
    }

    private fun runRootText(command: String): String? {
        if (!File(SU_PATH).canExecute()) return null
        return runCatching {
            val process = ProcessBuilder(SU_PATH, "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        }.onFailure { Log.e(TAG, "root text failed command=$command", it) }.getOrNull()
    }

    private const val SU_PATH = "/system/bin/su"

}
