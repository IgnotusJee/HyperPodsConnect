package moe.chenxy.oppopods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.config.imageUri
import java.io.File

object PodImageLoader {
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBitmapWithCustomFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        customFallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(moduleContext.resources, fallbackResId)
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }

    /**
     * MIUI Settings only exposes a static ImageView for the large headset artwork. Keep explicit
     * user artwork ahead of the official detail render, then degrade through the topology assets.
     */
    fun loadSettingsHeroBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
            ?: return null
        return earphone.settingsHeroArtworkPaths().firstNotNullOfOrNull { path ->
            runCatching { decodeUri(context, path.toImageUri()) }.getOrNull()
        }
    }

    fun loadIslandLeftBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.LEFT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_left,
        )
    }

    fun loadIslandRightBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.RIGHT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_right,
        )
    }

    private fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }

    private fun String.toImageUri(): Uri = Uri.Builder()
        .scheme("content")
        .authority(PodImagePrefs.AUTHORITY)
        .appendPath(File(this).name)
        .build()
}
