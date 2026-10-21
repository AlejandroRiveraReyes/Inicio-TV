package com.launchertv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

data class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val isTvApp: Boolean,
)

/** Arte ya rasterizado: los bitmaps se pueden compartir entre vistas sin problemas de bounds. */
class AppArt(
    val banner: Bitmap?,
    val icon: Bitmap,
    val color: Int,
    val ambient: Bitmap,
) {
    val bytes: Int get() = (banner?.byteCount ?: 0) + icon.byteCount + ambient.byteCount
}

/**
 * Carga la lista de apps y su arte fuera del hilo principal, con caché en memoria
 * para que volver a Inicio sea instantáneo.
 */
object AppRepository {
    private val listExecutor = Executors.newSingleThreadExecutor()
    private val artExecutor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var apps: List<AppEntry>? = null

    private val artCache = object : LruCache<String, AppArt>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: AppArt) = value.bytes
    }

    fun invalidate() {
        apps = null
        artCache.evictAll()
    }

    fun apps(context: Context, callback: (List<AppEntry>) -> Unit) {
        apps?.let { callback(it); return }
        val ctx = context.applicationContext
        listExecutor.execute {
            val list = queryApps(ctx)
            apps = list
            main.post { callback(list) }
        }
    }

    private fun queryApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun collect(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || byPkg.containsKey(ai.packageName)) continue
                byPkg[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    component = ComponentName(ai.packageName, ai.name),
                    label = ri.loadLabel(pm).toString().trim(),
                    isTvApp = tv,
                )
            }
        }
        // Primero las apps de TV (tienen banner); después las de móvil instaladas a mano.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return byPkg.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Devuelve el arte; si está en caché, el callback se ejecuta de inmediato. */
    fun art(context: Context, app: AppEntry, callback: (AppArt) -> Unit) {
        artCache.get(app.pkg)?.let { callback(it); return }
        val ctx = context.applicationContext
        artExecutor.execute {
            val art = try {
                loadArt(ctx, app)
            } catch (e: Exception) {
                null
            } ?: return@execute
            artCache.put(app.pkg, art)
            main.post { callback(art) }
        }
    }

    private fun loadArt(ctx: Context, app: AppEntry): AppArt {
        val pm = ctx.packageManager
        val bannerDrawable: Drawable? = try {
            pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.pkg)
        } catch (e: Exception) {
            null
        }
        val iconDrawable: Drawable = try {
            pm.getActivityIcon(app.component)
        } catch (e: Exception) {
            pm.defaultActivityIcon
        }
        val banner = bannerDrawable?.let { toBitmap(it, 640, 360) }
        val icon = toBitmap(iconDrawable, 192, 192)
        val ambientSource = banner ?: icon
        val ambient = Bitmap.createScaledBitmap(
            ambientSource,
            if (banner != null) 16 else 8,
            if (banner != null) 9 else 8,
            true,
        )
        return AppArt(banner, icon, tileColor(icon), ambient)
    }

    private fun toBitmap(d: Drawable, maxW: Int, maxH: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null &&
            d.bitmap.width <= maxW && d.bitmap.height <= maxH
        ) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxW
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxH
        val scale = minOf(1f, minOf(maxW.toFloat() / w, maxH.toFloat() / h))
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bw, bh)
        d.draw(Canvas(bmp))
        return bmp
    }

    /** Color medio del icono, oscurecido, para el fondo de las apps sin banner. */
    private fun tileColor(icon: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(icon, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = small.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0) return Color.rgb(0x2A, 0x2A, 0x31)
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[2] = (hsv[2] * 0.6f).coerceIn(0.16f, 0.42f)
        return Color.HSVToColor(hsv)
    }
}
