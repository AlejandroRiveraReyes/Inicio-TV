package com.launchertv.home

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    val initialized: Boolean get() = sp.contains(KEY_FAVORITES)

    /** Paquetes de la pantalla de inicio, en orden. */
    var favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, "")!!.split(',').filter { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_FAVORITES, value.joinToString(",")).apply()

    var columns: Int
        get() = sp.getInt("columns", 5)
        set(value) = sp.edit().putInt("columns", value).apply()

    var showInputs: Boolean
        get() = sp.getBoolean("show_inputs", true)
        set(value) = sp.edit().putBoolean("show_inputs", value).apply()

    var ambientBackground: Boolean
        get() = sp.getBoolean("ambient", true)
        set(value) = sp.edit().putBoolean("ambient", value).apply()

    var wallpaper: String
        get() = sp.getString("wallpaper", Wallpapers.NONE)!!
        set(value) = sp.edit().putString("wallpaper", value).apply()

    companion object {
        private const val KEY_FAVORITES = "favorites"

        /** Selección inicial si están instaladas; si no hay ninguna, se usan las primeras apps. */
        val DEFAULT_FAVORITES = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "es.plus.yomvi",
            "es.rtve.rtveplay",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "org.xbmc.kodi",
            "com.android.vending",
        )
    }
}
