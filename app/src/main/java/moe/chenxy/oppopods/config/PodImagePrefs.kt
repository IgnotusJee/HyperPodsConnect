package moe.chenxy.oppopods.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.libxposed.service.XposedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

@Serializable
enum class PodImageResource(
    val fileSuffix: String,
    val userConfigurable: Boolean = false,
) {
    BOX("box", userConfigurable = true),
    LEFT("left", userConfigurable = true),
    RIGHT("right", userConfigurable = true),
    DETAIL("detail"),
    HERO_ANIMATION("hero_animation"),
    CAPSULE_ANIMATION("capsule_animation"),
    ;

    companion object {
        val userConfigurableEntries: List<PodImageResource> = entries.filter { it.userConfigurable }
    }
}

@Serializable
data class EarphonePref(
    val address: String,
    val name: String,
    val boxImagePath: String? = null,
    val leftImagePath: String? = null,
    val rightImagePath: String? = null,
    val officialBoxImagePath: String? = null,
    val officialLeftImagePath: String? = null,
    val officialRightImagePath: String? = null,
    val officialDetailImagePath: String? = null,
    val officialHeroAnimationPath: String? = null,
    val officialCapsuleAnimationPath: String? = null,
    val officialArtwork: DeviceArtworkDescriptor? = null,
    val lastConnectedAt: Long = System.currentTimeMillis(),
) {
    fun userImagePath(resource: PodImageResource): String? = when (resource) {
        PodImageResource.BOX -> boxImagePath
        PodImageResource.LEFT -> leftImagePath
        PodImageResource.RIGHT -> rightImagePath
        PodImageResource.DETAIL,
        PodImageResource.HERO_ANIMATION,
        PodImageResource.CAPSULE_ANIMATION -> null
    }

    fun officialImagePath(resource: PodImageResource): String? = when (resource) {
        PodImageResource.BOX -> officialBoxImagePath
        PodImageResource.LEFT -> officialLeftImagePath
        PodImageResource.RIGHT -> officialRightImagePath
        PodImageResource.DETAIL -> officialDetailImagePath
        PodImageResource.HERO_ANIMATION -> officialHeroAnimationPath
        PodImageResource.CAPSULE_ANIMATION -> officialCapsuleAnimationPath
    }

    /** Explicit user artwork always wins over a verified official cache entry. */
    fun imagePath(resource: PodImageResource): String? =
        userImagePath(resource) ?: officialImagePath(resource)

    /** User-selected artwork wins; downloaded official resources follow their semantic priority. */
    fun heroArtworkPath(): String? =
        boxImagePath
            ?: leftImagePath
            ?: rightImagePath
            ?: officialHeroAnimationPath
            ?: officialDetailImagePath
            ?: officialBoxImagePath
            ?: officialLeftImagePath
            ?: officialRightImagePath

    /** MIUI's Settings hero is a static ImageView, so animated WebP is intentionally excluded. */
    fun settingsHeroArtworkPaths(): List<String> = buildList {
        listOf(boxImagePath, leftImagePath, rightImagePath).forEach { it?.let(::add) }
        listOf(
            officialDetailImagePath,
            officialBoxImagePath,
            officialLeftImagePath,
            officialRightImagePath,
        ).forEach { it?.let(::add) }
    }.distinct()
}

object PodImagePrefs {
    const val AUTHORITY = "moe.chenxy.oppopods.podimages"
    private const val PREF_KEY_EARPHONES = "earphone_prefs_json"
    private const val IMAGE_DIR = "pod_images"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(prefs: SharedPreferences): List<EarphonePref> {
        val raw = prefs.getString(PREF_KEY_EARPHONES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(EarphonePref.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun find(prefs: SharedPreferences, address: String): EarphonePref? {
        if (address.isBlank()) return null
        return load(prefs).firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    fun findOrLatest(prefs: SharedPreferences, address: String): EarphonePref? {
        return find(prefs, address) ?: load(prefs).maxByOrNull { it.lastConnectedAt }
    }

    fun imageDir(context: Context): File = File(context.filesDir, IMAGE_DIR).apply { mkdirs() }

    fun upsertConnected(
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        val updated = (existing ?: EarphonePref(address = address, name = name)).copy(
            name = name.ifBlank { existing?.name.orEmpty() },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    fun saveImages(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
        selectedImages: Map<PodImageResource, Uri?>,
        clearedImages: Set<PodImageResource> = emptySet(),
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        var updated = existing ?: EarphonePref(address = address, name = name)
        val validatedSelections = selectedImages.mapNotNull { (resource, uri) ->
            uri?.let { readAndValidateImage(context, it)?.let { payload -> resource to payload } }
        }.toMap()
        if (validatedSelections.size != selectedImages.values.count { it != null }) return current
        clearedImages.forEach { resource ->
            updated = updated.withUserImagePath(resource, null)
        }
        val copiedPaths = copyValidatedImages(context, address, "user", validatedSelections) ?: return current
        copiedPaths.forEach { (resource, path) ->
            updated = updated.withUserImagePath(resource, path)
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    fun saveImageBytes(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
        images: Map<PodImageResource, ByteArray>,
    ): List<EarphonePref> {
        if (address.isBlank()) return load(prefs)
        val current = load(prefs)
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        var updated = existing ?: EarphonePref(address = address, name = name)
        val validated = validateImages(images) ?: return current
        val copiedPaths = copyValidatedImages(context, address, "user", validated) ?: return current
        copiedPaths.forEach { (resource, path) ->
            updated = updated.withUserImagePath(resource, path)
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    fun saveOfficialImages(
        context: Context,
        prefs: SharedPreferences,
        service: XposedService?,
        address: String,
        name: String,
        selector: DeviceArtworkSelector,
        images: Map<PodImageResource, ByteArray>,
        source: DeviceArtworkSource = DeviceArtworkSource.OFFICIAL_CDN,
    ): List<EarphonePref> {
        if (address.isBlank() || images.isEmpty()) return load(prefs)
        val current = load(prefs)
        val existing = current.firstOrNull { it.address.equals(address, ignoreCase = true) }
        val validated = validateImages(images) ?: return current
        val descriptor = DeviceArtworkDescriptor(
            selector = selector,
            source = source,
            assets = validated.map { (resource, image) -> image.toAsset(resource) }
                .sortedBy { it.resource.ordinal },
            resolvedAtMillis = System.currentTimeMillis(),
        )
        if (
            existing?.officialArtwork?.copy(resolvedAtMillis = descriptor.resolvedAtMillis) == descriptor &&
            descriptor.assets.all { asset -> existing.officialImagePath(asset.resource)?.let(::File)?.isFile == true }
        ) return current

        val copiedPaths = copyValidatedImages(context, address, "official", validated) ?: return current
        var updated = (existing ?: EarphonePref(address = address, name = name)).withoutOfficialImages()
        copiedPaths.forEach { (resource, path) ->
            updated = updated.withOfficialImagePath(resource, path)
        }
        updated = updated.copy(
            name = name.ifBlank { updated.name },
            officialArtwork = descriptor,
            lastConnectedAt = System.currentTimeMillis(),
        )
        val normalized = listOf(updated) + current.filterNot { it.address.equals(address, ignoreCase = true) }
        service?.getRemotePreferences(ConfigManager.PREFS_NAME)?.let { save(it, normalized) }
        return save(prefs, normalized)
    }

    private fun save(prefs: SharedPreferences, earphones: List<EarphonePref>): List<EarphonePref> {
        val normalized = earphones.distinctBy { it.address.uppercase() }
        prefs.edit()
            .putString(PREF_KEY_EARPHONES, json.encodeToString(ListSerializer(EarphonePref.serializer()), normalized))
            .apply()
        return normalized
    }

    private fun readAndValidateImage(
        context: Context,
        uri: Uri,
    ): ValidatedImagePayload? = runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            input?.readBounded(DeviceArtworkValidator.MAX_BYTE_COUNT)
                ?.let(::validateForCache)
        }
    }.getOrNull()

    private fun validateImages(
        images: Map<PodImageResource, ByteArray>,
    ): Map<PodImageResource, ValidatedImagePayload>? {
        val result = images.mapNotNull { (resource, bytes) ->
            validateForCache(bytes)?.let { resource to it }
        }.toMap()
        return result.takeIf { it.size == images.size }
    }

    private fun validateForCache(bytes: ByteArray): ValidatedImagePayload? {
        val payload = DeviceArtworkValidator.validate(bytes) ?: return null
        val decodable = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth != payload.width || bounds.outHeight != payload.height) return@runCatching false
            var sampleSize = 1
            while (payload.width / sampleSize > 512 || payload.height / sampleSize > 512) {
                sampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return@runCatching false
            bitmap.recycle()
            true
        }.getOrDefault(false)
        return payload.takeIf { decodable }
    }

    /**
     * Content-addressed targets make publication atomic: preferences continue to reference the
     * previous complete set until every new file has been validated, synced and renamed.
     */
    private fun copyValidatedImages(
        context: Context,
        address: String,
        namespace: String,
        images: Map<PodImageResource, ValidatedImagePayload>,
    ): Map<PodImageResource, String>? {
        if (images.isEmpty()) return emptyMap()
        val dir = imageDir(context)
        val token = UUID.randomUUID().toString()
        val stagedFiles = mutableListOf<File>()
        val publishedFiles = mutableListOf<File>()
        return runCatching {
            val targets = images.mapValues { (resource, image) ->
                File(
                    dir,
                    "${address.cacheDeviceKey()}_${namespace}_${resource.fileSuffix}_${image.sha256.take(16)}.img",
                )
            }
            images.forEach { (resource, image) ->
                val target = targets.getValue(resource)
                if (target.isFile) return@forEach
                val staged = File(dir, ".${target.name}.$token.tmp")
                stagedFiles += staged
                FileOutputStream(staged).use { output ->
                    output.write(image.bytes)
                    output.fd.sync()
                }
                check(validateForCache(staged.readBytes())?.sha256 == image.sha256)
                check(staged.renameTo(target)) { "Unable to publish ${target.name}" }
                stagedFiles -= staged
                publishedFiles += target
            }
            targets.mapValues { it.value.absolutePath }
        }.getOrElse {
            stagedFiles.forEach(File::delete)
            publishedFiles.forEach(File::delete)
            null
        }
    }

    private fun EarphonePref.withUserImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(boxImagePath = path)
        PodImageResource.LEFT -> copy(leftImagePath = path)
        PodImageResource.RIGHT -> copy(rightImagePath = path)
        PodImageResource.DETAIL,
        PodImageResource.HERO_ANIMATION,
        PodImageResource.CAPSULE_ANIMATION -> this
    }

    private fun EarphonePref.withOfficialImagePath(resource: PodImageResource, path: String?): EarphonePref = when (resource) {
        PodImageResource.BOX -> copy(officialBoxImagePath = path)
        PodImageResource.LEFT -> copy(officialLeftImagePath = path)
        PodImageResource.RIGHT -> copy(officialRightImagePath = path)
        PodImageResource.DETAIL -> copy(officialDetailImagePath = path)
        PodImageResource.HERO_ANIMATION -> copy(officialHeroAnimationPath = path)
        PodImageResource.CAPSULE_ANIMATION -> copy(officialCapsuleAnimationPath = path)
    }

    private fun EarphonePref.withoutOfficialImages(): EarphonePref = copy(
        officialBoxImagePath = null,
        officialLeftImagePath = null,
        officialRightImagePath = null,
        officialDetailImagePath = null,
        officialHeroAnimationPath = null,
        officialCapsuleAnimationPath = null,
    )

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun String.cacheDeviceKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(uppercase().encodeToByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
}

fun EarphonePref.imageUri(resource: PodImageResource): Uri? {
    val path = imagePath(resource) ?: return null
    val fileName = File(path).name.takeIf { it.isNotBlank() } ?: return null
    return Uri.Builder()
        .scheme("content")
        .authority(PodImagePrefs.AUTHORITY)
        .appendPath(fileName)
        .build()
}
