package com.launchertv.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object Launch {

    fun app(ctx: Context, app: AppEntry) {
        val pm = ctx.packageManager
        val intent = (if (app.isTvApp) pm.getLeanbackLaunchIntentForPackage(app.pkg) else null)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(ctx, intent, "No se pudo abrir ${app.label}")
    }

    fun systemSettings(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_SETTINGS), "No se pudieron abrir los ajustes")

    fun network(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS), null)) systemSettings(ctx)
    }

    fun appInfo(ctx: Context, pkg: String) = start(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        "No se pudo abrir la información de la app",
    )

    fun start(ctx: Context, intent: Intent, error: String?): Boolean {
        if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            if (error != null) Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
