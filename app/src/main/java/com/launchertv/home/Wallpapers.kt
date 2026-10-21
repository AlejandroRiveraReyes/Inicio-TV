package com.launchertv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Fondos de pantalla incluidos en la app (generados con tools/wallpapers.py). */
object Wallpapers {
    const val NONE = "none"

    data class Wallpaper(val key: String, val name: String, val res: Int)

    val ALL = listOf(
        Wallpaper(NONE, "Ninguno", 0),
        Wallpaper("aurora", "Aurora", R.drawable.wp_aurora),
        Wallpaper("horizonte", "Horizonte", R.drawable.wp_horizonte),
        Wallpaper("nebulosa", "Nebulosa", R.drawable.wp_nebulosa),
        Wallpaper("malla", "Malla", R.drawable.wp_malla),
        Wallpaper("ondas", "Ondas", R.drawable.wp_ondas),
        Wallpaper("orbitas", "Órbitas", R.drawable.wp_orbitas),
    )

    fun find(key: String) = ALL.firstOrNull { it.key == key } ?: ALL[0]

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Miniaturas (1/4 de resolución) y, como mucho, dos fondos completos en memoria.
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Carga el fondo en segundo plano. sampleSize 1 = resolución completa, 4 = miniatura. */
    fun load(ctx: Context, wallpaper: Wallpaper, sampleSize: Int, callback: (Bitmap?) -> Unit) {
        if (wallpaper.res == 0) { callback(null); return }
        val key = "${wallpaper.key}@$sampleSize"
        cache.get(key)?.let { callback(it); return }
        val res = ctx.applicationContext.resources
        executor.execute {
            val bmp = try {
                BitmapFactory.decodeResource(res, wallpaper.res, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inScaled = false
                })
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { callback(bmp) }
        }
    }
}
