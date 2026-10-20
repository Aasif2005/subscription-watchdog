package com.aasif.watchdog.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.aasif.watchdog.core.merchant.MerchantNormalizer
import com.aasif.watchdog.data.BrandIconFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Whether the user allowed logos of unrecognised services to be looked up online (Profile → Appearance). */
val LocalOnlineLogos = compositionLocalOf { false }

/**
 * A subscription's logo, or null for the letter tile. In order: the installed app's own icon, the Play icon bundled for
 * a catalog service, an installed app whose name is exactly the subscription's, and — only when [online] — a verified
 * Play Store icon fetched once and kept on the phone.
 */
@Composable
fun rememberBrandIcon(name: String, brandId: String?, appPackage: String?, online: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val key = brandId ?: "name:${MerchantNormalizer.squash(name)}"
    val icon by produceState(BrandIcons.cached(key), key, online) {
        if (value == null) value = withContext(Dispatchers.IO) { BrandIcons.load(context, key, name, brandId, appPackage, online) }
    }
    return icon
}

object BrandIcons {
    private const val PX = 144
    private const val RETRY_MISS_MILLIS = 7L * 24 * 60 * 60 * 1000
    private val memory = LruCache<String, ImageBitmap>(64)
    private val network = Mutex()

    @Volatile private var launcherLabels: Map<String, String>? = null

    fun cached(key: String): ImageBitmap? = memory.get(key)

    suspend fun load(context: Context, key: String, name: String, brandId: String?, appPackage: String?, online: Boolean): ImageBitmap? {
        val bitmap = appPackage?.let { installedIcon(context, it) }
            ?: brandId?.let { bundledIcon(context, it) }
            ?: installedByName(context, name)?.let { installedIcon(context, it) }
            ?: if (online && brandId == null) onlineIcon(context, name) else null
        return bitmap?.asImageBitmap()?.also { memory.put(key, it) }
    }

    /** Deletes every logo found online and the record of lookups that found nothing; used when the switch goes off. */
    fun clearOnline(context: Context) {
        onlineDir(context).deleteRecursively()
        misses(context).edit { clear() }
        memory.snapshot().keys.filter { it.startsWith("name:") }.forEach { memory.remove(it) }
    }

    private fun installedIcon(context: Context, pkg: String): Bitmap? =
        runCatching { context.packageManager.getApplicationIcon(pkg).toTile() }.getOrNull()

    @SuppressLint("DiscouragedApi") // brand_<id> names come from the catalog, so they can't be R fields known here.
    private fun bundledIcon(context: Context, id: String): Bitmap? {
        val res = context.resources.getIdentifier("brand_$id", "drawable", context.packageName)
        return if (res == 0) null else BitmapFactory.decodeResource(context.resources, res)
    }

    /** A launchable app whose label is the subscription's name, exactly once squashed; short names would match too much. */
    private fun installedByName(context: Context, name: String): String? {
        val wanted = MerchantNormalizer.squash(name)
        if (wanted.length < 4) return null
        val labels = launcherLabels ?: context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { MerchantNormalizer.squash(it.loadLabel(context.packageManager).toString()) to it.activityInfo.packageName }
            .also { launcherLabels = it }
        return labels[wanted]
    }

    private suspend fun onlineIcon(context: Context, name: String): Bitmap? {
        val squashed = MerchantNormalizer.squash(name)
        if (squashed.length < 4) return null
        val file = File(onlineDir(context), "$squashed.webp")
        if (file.exists()) return BitmapFactory.decodeFile(file.path)
        val misses = misses(context)
        if (System.currentTimeMillis() - misses.getLong(squashed, 0) < RETRY_MISS_MILLIS) return null
        // One lookup at a time: a dashboard of unknown services shouldn't fire a burst of searches.
        return network.withLock {
            if (file.exists()) return@withLock BitmapFactory.decodeFile(file.path)
            val bytes = BrandIconFetcher.fetch(name)
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            } else {
                misses.edit { putLong(squashed, System.currentTimeMillis()) }
            }
            bitmap
        }
    }

    private fun onlineDir(context: Context) = File(context.filesDir, "brand-icons")
    private fun misses(context: Context) = context.getSharedPreferences("brand_icon_misses", Context.MODE_PRIVATE)

    /**
     * Square, edge to edge, the way Play shows icons: an adaptive icon's layers are 108 units with the visible 72 in the
     * middle, so they are drawn half as large again and centred, and the tile's own corners do the masking.
     */
    private fun Drawable.toTile(): Bitmap {
        val bitmap = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (this is AdaptiveIconDrawable) {
            val inset = PX / 4
            listOfNotNull(background, foreground).forEach { it.setBounds(-inset, -inset, PX + inset, PX + inset); it.draw(canvas) }
        } else {
            setBounds(0, 0, PX, PX)
            draw(canvas)
        }
        return bitmap
    }
}
