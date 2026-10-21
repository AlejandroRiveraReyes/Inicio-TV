package com.launchertv.home

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var rows: LinearLayout
    private val refreshers = ArrayList<() -> Unit>()
    private val adbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var adbBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        rows = findViewById(R.id.rows)
        build()
        rows.getChildAt(1)?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    private fun build() {
        section("Pantalla de inicio")
        row("Elegir apps de Inicio", { "${prefs.favorites.size} apps" }) {
            startActivity(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, true))
        }
        row("Columnas", { prefs.columns.toString() }) {
            prefs.columns = when (prefs.columns) { 4 -> 5; 5 -> 6; else -> 4 }
        }
        row("Entradas HDMI en la barra superior", { yesNo(prefs.showInputs) }) {
            prefs.showInputs = !prefs.showInputs
        }
        row("Fondo de pantalla", { Wallpapers.find(prefs.wallpaper).name }) {
            startActivity(Intent(this, WallpaperActivity::class.java))
        }
        row(
            "Fondo ambiental", { yesNo(prefs.ambientBackground) },
            subtitle = "Desactívalo si la TV va muy justa de memoria",
        ) {
            prefs.ambientBackground = !prefs.ambientBackground
        }

        section("Launcher")
        row("Estado", { if (LauncherSwitcher.isDefault(this)) "Predeterminado" else "No predeterminado" }) {}
        row("Usar Inicio TV como predeterminado", { "" }) { makeDefault() }
        row(
            "Volver al launcher original", { "" },
            subtitle = "Inicio TV deja de ser el launcher. Puedes reactivarlo desde la lista de apps.",
        ) { restoreOriginal() }
        row("Abrir launcher original una vez", { "" }) { openOriginal() }

        section("Sistema")
        row("Ajustes de la TV", { "" }) { Launch.systemSettings(this) }
        row("Acerca de", { "Inicio TV ${packageManager.getPackageInfo(packageName, 0).versionName}" }) {
            showAdbHelp()
        }
    }

    private fun yesNo(value: Boolean) = if (value) "Sí" else "No"

    private fun section(title: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_section, rows, false) as TextView
        view.text = title
        rows.addView(view)
    }

    private fun row(title: String, value: () -> String, subtitle: String? = null, onClick: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting, rows, false)
        view.findViewById<TextView>(R.id.rowTitle).text = title
        if (subtitle != null) {
            view.findViewById<TextView>(R.id.rowSubtitle).apply {
                text = subtitle
                visibility = View.VISIBLE
            }
        }
        val valueView = view.findViewById<TextView>(R.id.rowValue)
        val refresh = { valueView.text = value() }
        refresh()
        refreshers += refresh
        view.setOnClickListener {
            onClick()
            refreshers.forEach { it() }
        }
        rows.addView(view)
    }

    // --- Cambio de launcher ----------------------------------------------------------------

    private fun makeDefault() {
        LauncherSwitcher.setHomeRoleEnabled(this, true)
        // El launcher de fábrica tiene prioridad de sistema: mientras esté activo, gana siempre.
        val stock = LauncherSwitcher.otherHomes(this).filter { it.isStock && it.enabled }
        if (stock.isEmpty() && LauncherSwitcher.isDefault(this)) {
            toast("Inicio TV ya es el launcher predeterminado")
            return
        }
        if (stock.isNotEmpty()) {
            val disable = stock.joinToString("; ") {
                val pkg = it.component.packageName
                "pm disable-user --user 0 $pkg; grep -qx $pkg $STATE_FILE 2>/dev/null || echo $pkg >> $STATE_FILE"
            }
            val command = "$disable; cmd package set-home-activity $packageName/$packageName.HomeAlias"
            runAdb(command, "Activando Inicio TV…") {
                Launch.start(this, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), null)
                finish()
            }
            return
        }
        // TV sin launcher de Google: basta con el rol de launcher del sistema.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                } catch (e: Exception) {
                    // seguir con la siguiente opción
                }
            }
        }
        if (!Launch.start(this, Intent(Settings.ACTION_HOME_SETTINGS), null)) showAdbHelp()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE && !LauncherSwitcher.isDefault(this)) showAdbHelp()
    }

    private fun restoreOriginal() {
        val original = LauncherSwitcher.original(this)
        if (original == null) {
            toast("No hay ningún otro launcher instalado")
            return
        }
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle("¿Volver a ${original.label}?")
            .setMessage(
                "Al pulsar Inicio en el mando se abrirá de nuevo el launcher original. Tu configuración " +
                    "se guarda: para volver, abre \"Inicio TV\" desde la lista de apps → Personalizar → " +
                    "Usar Inicio TV como predeterminado."
            )
            .setPositiveButton("Volver al original") { _, _ ->
                if (original.enabled) {
                    switchToOriginal()
                } else {
                    val pkg = original.component.packageName
                    runAdb("pm enable $pkg; sed -i '/^${pkg.replace(".", "\\.")}\$/d' $STATE_FILE 2>/dev/null", "Restaurando ${original.label}…") {
                        switchToOriginal()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun switchToOriginal() {
        LauncherSwitcher.setHomeRoleEnabled(this, false)
        val original = LauncherSwitcher.original(this)
        if (original == null || !LauncherSwitcher.openHome(this, original)) {
            Launch.start(this, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), null)
        }
        finishAffinity()
    }

    private fun openOriginal() {
        val original = LauncherSwitcher.original(this)
        if (original == null || !original.enabled || !LauncherSwitcher.openHome(this, original)) {
            toast("El launcher original está desactivado. Usa «Volver al launcher original».")
        }
    }

    /** Ejecuta un comando con permisos de ADB en la propia TV, fuera del hilo principal. */
    private fun runAdb(command: String, progress: String, onSuccess: () -> Unit) {
        if (adbBusy) return
        adbBusy = true
        android.widget.Toast.makeText(
            this,
            "$progress\nSi la TV pregunta si permites la depuración, marca «Permitir siempre» y acepta.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        adbExecutor.execute {
            val result = runCatching {
                val out = LocalAdb.shell(applicationContext, command)
                if (out.contains("Exception") || out.contains("Error:")) throw LocalAdb.AdbException(out.trim())
            }
            runOnUiThread {
                adbBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ onSuccess() }, { showAdbError(it) })
            }
        }
    }

    private fun showAdbError(error: Throwable) {
        val notEnabled = error.message?.contains("no está activada") == true
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle("No se pudo cambiar el launcher")
            .setMessage(
                if (notEnabled) {
                    "Inicio TV necesita la depuración por red de la TV para cambiar de launcher.\n\n" +
                        "1. Ajustes → Preferencias del dispositivo → Información: pulsa 7 veces «Compilación».\n" +
                        "2. Opciones de desarrollador: activa «Depuración por red» (o «ADB por red»).\n\n" +
                        "También puedes hacerlo desde el ordenador con ./scripts/instalar.sh o revertir.sh."
                } else {
                    "${error.message ?: "Error desconocido"}\n\nSi la TV mostró un aviso de depuración, " +
                        "vuelve a intentarlo y elige «Permitir siempre»."
                }
            )
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun showAdbHelp() {
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle("Cambiar de launcher con ADB")
            .setMessage(
                "Algunas TV no dejan elegir el launcher desde el propio televisor. Desde el ordenador, " +
                    "con la depuración de red activada en la TV:\n\n" +
                    "• Activar Inicio TV:\n  ./scripts/instalar.sh <IP>\n\n" +
                    "• Volver al launcher original:\n  ./scripts/revertir.sh <IP>"
            )
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_ROLE = 1
        private const val STATE_FILE = "/data/local/tmp/launchertv_desactivados.txt"
    }
}
