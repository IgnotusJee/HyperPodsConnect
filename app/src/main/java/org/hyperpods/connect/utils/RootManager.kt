package org.hyperpods.connect.utils

import android.util.Log
import org.hyperpods.connect.config.DeviceArtworkSelector
import org.hyperpods.connect.config.PodImageResource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class OfficialNetworkArtworkCandidate(
    val selector: DeviceArtworkSelector,
    val resourceUrls: Map<PodImageResource, String>,
)

object RootManager {
    private const val TAG = "HyperPodsConnect-RootManager"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val officialImageUrlRegex = Regex(
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

    fun readOfficialNetworkImages(
        candidate: OfficialNetworkArtworkCandidate,
    ): Map<PodImageResource, ByteArray>? {
        val images = candidate.resourceUrls.mapNotNull { (resource, url) ->
            readOfficialImageUrl(url)?.let { resource to it }
        }.toMap()
        return images.takeIf { it.isNotEmpty() && it.size == candidate.resourceUrls.size }
    }

    private fun readOfficialImageUrl(url: String): ByteArray? {
        if (!url.matches(officialImageUrlRegex)) return null
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Official CDN returned HTTP ${connection.responseCode} url=$url")
                    return null
                }
                val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                if (contentType != "application/octet-stream" && contentType != "binary/octet-stream" && !contentType.startsWith("image/")) {
                    Log.w(TAG, "Official CDN returned unexpected content type=$contentType url=$url")
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
        }.onFailure { Log.w(TAG, "Official image download failed url=$url", it) }.getOrNull()
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
