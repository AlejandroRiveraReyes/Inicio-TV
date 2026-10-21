package com.launchertv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Gestiona qué launcher usa la TV. Esta app nunca toca el launcher original:
 * solo activa o desactiva su propio rol de HOME (el alias del manifiesto).
 */
object LauncherSwitcher {

    data class Home(val component: ComponentName, val label: String, val enabled: Boolean) {
        val isStock: Boolean get() = component.packageName in KNOWN_STOCK
    }

    /** Launchers originales conocidos, por si están desactivados vía ADB y no aparecen en la consulta. */
    private val KNOWN_STOCK = listOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )

    private fun alias(ctx: Context) = ComponentName(ctx.packageName, "${ctx.packageName}.HomeAlias")

    fun isHomeRoleEnabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHomeRoleEnabled(ctx: Context, enabled: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            alias(ctx),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun isDefault(ctx: Context): Boolean {
        val ri = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    /** Otros launchers instalados (incluidos los desactivados), excluyendo pantallas de emergencia. */
    fun otherHomes(ctx: Context): List<Home> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val result = LinkedHashMap<String, Home>()
        for (ri in pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            if (ai.name.contains("FallbackHome") || ai.name.contains("Provision")) continue
            if (result.containsKey(ai.packageName)) continue
            result[ai.packageName] = Home(
                ComponentName(ai.packageName, ai.name),
                ri.loadLabel(pm).toString(),
                ai.enabled && ai.applicationInfo.enabled,
            )
        }
        for (pkg in KNOWN_STOCK) {
            if (result.containsKey(pkg)) continue
            val info = try {
                pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            result[pkg] = Home(ComponentName(pkg, ""), pm.getApplicationLabel(info).toString(), info.enabled)
        }
        // Primero el launcher de fábrica: otras apps (p. ej. Orange TV) también se declaran HOME.
        return result.values.sortedBy { if (it.isStock) 0 else 1 }
    }

    /**
     * El launcher al que volver: el de fábrica si está instalado (aunque esté desactivado),
     * y solo si no hay ninguno, cualquier otro launcher activo.
     */
    fun original(ctx: Context): Home? {
        val homes = otherHomes(ctx)
        return homes.firstOrNull { it.isStock } ?: homes.firstOrNull { it.enabled }
    }

    fun openHome(ctx: Context, home: Home): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (home.component.className.isEmpty()) intent.setPackage(home.component.packageName)
        else intent.component = home.component
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
