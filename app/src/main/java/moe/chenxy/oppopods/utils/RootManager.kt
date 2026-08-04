package moe.chenxy.oppopods.utils

import android.util.Log
import moe.chenxy.oppopods.config.DeviceArtworkSelector
import moe.chenxy.oppopods.config.PodImageResource
import java.io.ByteArrayOutputStream
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

    fun restartPackages(packages: Collection<String>): Boolean {
        val targets = packages.distinct().filter { it.matches(packageNameRegex) }
        if (targets.isEmpty()) return false

        return runCatching {
            val command = targets.joinToString("; ") { "am force-stop $it" }
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

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
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        }.onFailure { Log.e(TAG, "root text failed command=$command", it) }.getOrNull()
    }

}
