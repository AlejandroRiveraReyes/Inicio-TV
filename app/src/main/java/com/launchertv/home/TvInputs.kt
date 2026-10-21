package com.launchertv.home

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class InputEntry(val id: String, val label: String, val isTuner: Boolean)

/** Entradas físicas de la TV (HDMI, AV...) y el sintonizador, vía la API estándar de Android TV. */
object TvInputs {

    fun list(ctx: Context): List<InputEntry> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val infos = try {
            tim.tvInputList
        } catch (e: Exception) {
            return emptyList()
        }
        val result = ArrayList<InputEntry>()
        var tunerAdded = false
        for (info in infos) {
            if (info.parentId != null) continue // dispositivos CEC colgando de un puerto HDMI
            if (isHidden(ctx, info)) continue
            if (info.isPassthroughInput && info.type == TvInputInfo.TYPE_HDMI) {
                result += InputEntry(info.id, label(ctx, info), isTuner = false)
            } else if (!tunerAdded && info.type == TvInputInfo.TYPE_TUNER && isSystem(info)) {
                result.add(0, InputEntry(info.id, "TV", isTuner = true))
                tunerAdded = true
            }
        }
        return result
    }

    fun open(ctx: Context, input: InputEntry): Boolean {
        val uris = if (input.isTuner) {
            listOf(TvContract.buildChannelsUriForInput(input.id), TvContract.Channels.CONTENT_URI)
        } else {
            listOf(TvContract.buildChannelUriForPassthroughInput(input.id))
        }
        for (uri in uris) {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                // probar la siguiente opción
            }
        }
        return false
    }

    private fun label(ctx: Context, info: TvInputInfo): String {
        val custom = try {
            info.loadCustomLabel(ctx)
        } catch (e: Exception) {
            null
        }
        val text = (custom?.takeIf { it.isNotBlank() } ?: info.loadLabel(ctx)).toString()
        return text.ifBlank { "Entrada" }
    }

    private fun isHidden(ctx: Context, info: TvInputInfo) = try {
        info.isHidden(ctx)
    } catch (e: Exception) {
        false
    }

    private fun isSystem(info: TvInputInfo): Boolean {
        val flags = info.serviceInfo?.applicationInfo?.flags ?: return false
        return flags and ApplicationInfo.FLAG_SYSTEM != 0
    }
}
