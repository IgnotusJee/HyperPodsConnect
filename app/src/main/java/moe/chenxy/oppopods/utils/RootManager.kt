package moe.chenxy.oppopods.utils

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.chenxy.oppopods.config.PodImageResource
import java.io.ByteArrayOutputStream

data class MelodyImageCandidate(
    val label: String,
    val productId: String,
    val colorId: String,
    val model: String,
    val imageDir: String,
    val resourcePaths: Map<PodImageResource, String>,
    /** Small preview used only by the import picker; DETAIL is preferred over BOX. */
    val boxBytes: ByteArray,
)

object RootManager {
    private const val TAG = "OppoPods-MelodyImport"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val productIdRegex = Regex("^[A-Za-z0-9]{3,16}$")
    private val colorIdRegex = Regex("^[0-9]{1,4}$")
    private val controlDirNameRegex = Regex("^control_([A-Za-z0-9]{3,16})_([0-9]{1,4})$")
    private val melodyArtworkPathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)/com\\.heytap\\.headset/files/melody-model-download/(control|fetch14)_[A-Za-z0-9]+_[0-9]+/res/(image|video|raw)/[A-Za-z0-9._-]+\\.(png|jpe?g|webp)$",
        RegexOption.IGNORE_CASE,
    )
    private val melodyControlConfigPathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)/com\\.heytap\\.headset/files/melody-model-download/control_[A-Za-z0-9]+_[0-9]+/config\\.json$"
    )
    private val relativeArtworkPathRegex = Regex(
        "^res/(image|video|raw)/[A-Za-z0-9._-]+\\.(png|jpe?g|webp)$",
        RegexOption.IGNORE_CASE,
    )
    private val melodyDirPathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)/com\\.heytap\\.headset/files/melody-model-download$"
    )
    private val melodyModelDirs = listOf(
        "/data_mirror/data_ce/null/0/com.heytap.headset/files/melody-model-download",
        "/data_mirror/data_de/null/0/com.heytap.headset/files/melody-model-download",
        "/data/data/com.heytap.headset/files/melody-model-download",
        "/data/user/0/com.heytap.headset/files/melody-model-download",
        "/data/user_de/0/com.heytap.headset/files/melody-model-download",
    )
    private val melodyDatabasePaths = listOf(
        "/data_mirror/data_ce/null/0/com.heytap.headset/databases/melody-model.db",
        "/data_mirror/data_de/null/0/com.heytap.headset/databases/melody-model.db",
        "/data/data/com.heytap.headset/databases/melody-model.db",
        "/data/user/0/com.heytap.headset/databases/melody-model.db",
        "/data/user_de/0/com.heytap.headset/databases/melody-model.db",
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

    fun scanMelodyImageCandidates(): List<MelodyImageCandidate> {
        val modelDir = melodyModelDirs.firstOrNull { dir ->
            dir.matches(melodyDirPathRegex) && runRootText("test -d ${dir.shellQuote()} && echo yes")?.trim() == "yes"
        } ?: return emptyList()

        val command = "for d in ${modelDir.shellQuote()}/control_*; do test -f \"\$d/config.json\" && echo \"\$d/config.json\"; done 2>/dev/null"
        val configPaths = runRootText(command)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.matches(melodyControlConfigPathRegex) }
            ?.distinct()
            ?.toList()
            .orEmpty()

        return configPaths.mapNotNull { configPath ->
            val controlDir = configPath.removeSuffix("/config.json")
            val label = controlDir.substringAfterLast('/')
            val match = controlDirNameRegex.matchEntire(label) ?: return@mapNotNull null
            createMelodyCandidate(
                modelDir = modelDir,
                productId = match.groupValues[1],
                colorId = match.groupValues[2],
                model = label,
            )
        }
    }

    /** Resolve only an exact, unambiguous official-app equipment row. */
    fun resolveMelodyImageCandidate(deviceName: String, deviceAddress: String? = null): MelodyImageCandidate? {
        val expectedModel = deviceName.trim().takeIf { it.isNotEmpty() } ?: run {
            Log.w(TAG, "official artwork resolution skipped: empty device name")
            return null
        }
        val modelDir = melodyModelDirs.firstOrNull { dir ->
            dir.matches(melodyDirPathRegex) && runRootText("test -d ${dir.shellQuote()} && echo yes")?.trim() == "yes"
        } ?: run {
            Log.w(TAG, "official artwork resolution failed: HeyMelody model directory not found")
            return null
        }
        val database = melodyDatabasePaths.firstOrNull { path ->
            runRootText("test -f ${path.shellQuote()} && echo yes")?.trim() == "yes"
        } ?: run {
            Log.w(TAG, "official artwork resolution failed: HeyMelody equipment database not found")
            return null
        }
        val normalizedAddress = deviceAddress.orEmpty().filter(Char::isLetterOrDigit).uppercase()
        val addressClause = normalizedAddress.takeIf { it.length == 12 }?.let {
            "UPPER(REPLACE(macAddress, ':', '')) = ${it.sqlQuote()}"
        }
        val whereClause = addressClause ?: "name = ${expectedModel.sqlQuote()}"
        val sql = "SELECT productId || '|' || colorId || '|' || name FROM melody_equipment " +
            "WHERE $whereClause ORDER BY productId, colorId;"
        var queryOutput = runRootText("sqlite3 ${database.shellQuote()} ${sql.shellQuote()}") ?: run {
            Log.w(TAG, "official artwork resolution failed: equipment query failed")
            return null
        }
        var records = queryOutput
            .lineSequence()
            .mapNotNull(::parseMelodyEquipmentRow)
            .distinct()
            .toList()
        if (records.isEmpty() && addressClause != null) {
            val fallbackSql = "SELECT productId || '|' || colorId || '|' || name FROM melody_equipment " +
                "WHERE name = ${expectedModel.sqlQuote()} ORDER BY productId, colorId;"
            queryOutput = runRootText("sqlite3 ${database.shellQuote()} ${fallbackSql.shellQuote()}").orEmpty()
            records = queryOutput.lineSequence()
                .mapNotNull(::parseMelodyEquipmentRow)
                .filter { it.model == expectedModel }
                .distinct()
                .toList()
        }
        if (records.size != 1) {
            Log.w(TAG, "official artwork resolution failed: expected one exact equipment row, found ${records.size}")
            return null
        }
        val record = records.single()
        return createMelodyCandidate(modelDir, record.productId, record.colorId, record.model)
    }

    fun readMelodyImages(candidate: MelodyImageCandidate): Map<PodImageResource, ByteArray>? {
        val images = candidate.resourcePaths.mapNotNull { (resource, path) ->
            readMelodyImage(path)?.let { resource to it }
        }.toMap()
        // Roles are optional by topology, but every role declared by the official configs must be
        // readable before replacing the previous atomic cache generation.
        return images.takeIf { it.isNotEmpty() && it.size == candidate.resourcePaths.size }
    }

    fun readMelodyImage(path: String): ByteArray? {
        if (!path.matches(melodyArtworkPathRegex)) {
            return null
        }
        return runCatching {
            val process = ProcessBuilder("su", "-c", "cat ${path.shellQuote()}")
                .redirectErrorStream(false)
                .start()
            val bytes = ByteArrayOutputStream().use { output ->
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            process.destroyForcibly()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray()
            }
            val exitCode = process.waitFor()
            if (exitCode == 0 && bytes.isNotEmpty()) bytes else null
        }.onFailure { Log.e(TAG, "read failed path=$path", it) }.getOrNull()
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

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

    private fun String.sqlQuote(): String = "'" + replace("'", "''") + "'"

    internal fun parseMelodyEquipmentRow(row: String): MelodyEquipmentRecord? {
        val parts = row.trim().split('|', limit = 3)
        if (parts.size != 3) return null
        val productId = parts[0].takeIf { it.matches(productIdRegex) } ?: return null
        val colorId = parts[1].takeIf { it.matches(colorIdRegex) } ?: return null
        val model = parts[2].trim().takeIf { it.isNotEmpty() } ?: return null
        return MelodyEquipmentRecord(productId, colorId, model)
    }

    internal fun parseMelodyArtworkConfig(config: String): Map<PodImageResource, String> = runCatching {
        val root = Json.parseToJsonElement(config).jsonObject
        buildMap {
            CONTROL_RESOURCE_KEYS.forEach { (resource, key) ->
                root[key]?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.matches(relativeArtworkPathRegex) }
                    ?.let { put(resource, it) }
            }
        }
    }.getOrDefault(emptyMap())

    internal fun parseMelodyDetailConfig(config: String): Map<PodImageResource, String> = runCatching {
        val root = Json.parseToJsonElement(config).jsonObject
        root["modelWebp"]?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(relativeArtworkPathRegex) }
            ?.let { mapOf(PodImageResource.HERO_ANIMATION to it) }
            .orEmpty()
    }.getOrDefault(emptyMap())

    private fun createMelodyCandidate(
        modelDir: String,
        productId: String,
        colorId: String,
        model: String,
    ): MelodyImageCandidate? {
        val label = "control_${productId}_${colorId}"
        val controlDir = "$modelDir/$label"
        val controlConfigPath = "$controlDir/config.json"
        if (!controlConfigPath.matches(melodyControlConfigPathRegex)) return null
        val controlConfig = runRootText("cat ${controlConfigPath.shellQuote()}") ?: return null
        val resourcePaths = parseMelodyArtworkConfig(controlConfig)
            .mapValues { (_, relativePath) -> "$controlDir/$relativePath" }
            .toMutableMap()

        val detailDir = "$modelDir/fetch14_${productId}_${colorId}"
        val detailConfig = runRootText("cat ${("$detailDir/config.json").shellQuote()} 2>/dev/null")
        if (detailConfig != null) {
            parseMelodyDetailConfig(detailConfig).forEach { (resource, relativePath) ->
                resourcePaths[resource] = "$detailDir/$relativePath"
            }
        }
        resourcePaths.entries.removeAll { (_, path) -> !path.matches(melodyArtworkPathRegex) }
        if (resourcePaths.isEmpty()) return null

        val previewBytes = listOf(
            PodImageResource.DETAIL,
            PodImageResource.BOX,
            PodImageResource.LEFT,
            PodImageResource.RIGHT,
        ).firstNotNullOfOrNull { resource -> resourcePaths[resource]?.let(::readMelodyImage) }
            ?: return null
        return MelodyImageCandidate(
            label = label,
            productId = productId,
            colorId = colorId,
            model = model,
            imageDir = "$controlDir/res/image",
            resourcePaths = resourcePaths.toMap(),
            boxBytes = previewBytes,
        )
    }

    private val CONTROL_RESOURCE_KEYS = linkedMapOf(
        PodImageResource.DETAIL to "detailImageRes",
        PodImageResource.BOX to "boxImageRes",
        PodImageResource.LEFT to "leftImageRes",
        PodImageResource.RIGHT to "rightImageRes",
        PodImageResource.CAPSULE_ANIMATION to "capsuleVideoRes",
    )
}

internal data class MelodyEquipmentRecord(
    val productId: String,
    val colorId: String,
    val model: String,
)
