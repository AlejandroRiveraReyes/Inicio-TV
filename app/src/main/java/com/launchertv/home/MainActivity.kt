package com.launchertv.home

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity(), TileAdapter.Host {

    private lateinit var prefs: Prefs
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: TileAdapter
    private lateinit var pills: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroBg: ImageView
    private lateinit var wallpaper: ImageView
    private var wallpaperKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pillsSignature = ""
    private var firstLoad = true
    private var focusFirstOnWindowFocus = false
    private var resumed = false
    private var dirty = false
    private var pendingHeroArt: AppArt? = null
    private val heroRunnable = Runnable { applyHeroBackground() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppRepository.invalidate()
            if (resumed) refresh() else dirty = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        grid = findViewById(R.id.grid)
        pills = findViewById(R.id.pills)
        heroTitle = findViewById(R.id.heroTitle)
        heroBg = findViewById(R.id.heroBg)
        wallpaper = findViewById(R.id.wallpaper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            heroBg.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
        }

        layoutManager = GridLayoutManager(this, prefs.columns)
        adapter = TileAdapter(this, showAddTile = true, labelsAlways = false)
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.itemAnimator?.moveDuration = 140

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        dirty = false
        if (layoutManager.spanCount != prefs.columns) layoutManager.spanCount = prefs.columns
        buildPills()
        applyWallpaper()
        refresh()
    }

    private fun applyWallpaper() {
        val wp = Wallpapers.find(prefs.wallpaper)
        if (wp.key == wallpaperKey) return
        wallpaperKey = wp.key
        Wallpapers.load(this, wp, 1) { bmp ->
            if (wallpaperKey != wp.key) return@load
            if (bmp == null) {
                wallpaper.animate().alpha(0f).setDuration(250).withEndAction { wallpaper.setImageDrawable(null) }.start()
            } else {
                wallpaper.setImageBitmap(bmp)
                wallpaper.animate().alpha(1f).setDuration(350).start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (adapter.movingIndex >= 0) endMove()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Pulsar Inicio estando ya en Inicio: volver arriba, a la primera app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (adapter.movingIndex >= 0) endMove()
        grid.scrollToPosition(0)
        focusPosition(0)
        focusFirstOnWindowFocus = true
    }

    // Al volver con Inicio desde otra pantalla, el sistema restaura el foco anterior
    // después de onNewIntent; se vuelve a pedir la primera app cuando la ventana lo recupera.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && focusFirstOnWindowFocus) {
            focusFirstOnWindowFocus = false
            focusPosition(0)
        }
    }

    private fun refresh() {
        AppRepository.apps(this) { apps ->
            if (!prefs.initialized) {
                val installed = apps.map { it.pkg }.toSet()
                val defaults = Prefs.DEFAULT_FAVORITES.filter { it in installed }
                prefs.favorites = defaults.ifEmpty { apps.filter { it.isTvApp }.take(8).map { it.pkg } }
            }
            val byPkg = apps.associateBy { it.pkg }
            adapter.submit(prefs.favorites.mapNotNull { byPkg[it] })
            if (firstLoad) {
                firstLoad = false
                focusPosition(0)
            }
        }
    }

    private fun focusPosition(position: Int) {
        grid.post {
            val vh = grid.findViewHolderForAdapterPosition(position)
            if (vh != null) vh.itemView.requestFocus()
            else grid.postDelayed({ grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }, 50)
        }
    }

    // --- Barra superior: entradas, ajustes y accesos rápidos -----------------------------

    private data class Pill(val icon: Int, val text: String, val action: () -> Unit)

    private fun buildPills() {
        val list = ArrayList<Pill>()
        if (prefs.showInputs) {
            for (input in TvInputs.list(this)) {
                list += Pill(if (input.isTuner) R.drawable.ic_tv else R.drawable.ic_input, input.label) {
                    if (!TvInputs.open(this, input)) {
                        Toast.makeText(this, "Esta TV no permite abrir ${input.label} directamente", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        list += Pill(R.drawable.ic_apps, getString(R.string.all_apps)) {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        list += Pill(R.drawable.ic_settings, "Ajustes") { Launch.systemSettings(this) }
        list += Pill(R.drawable.ic_wifi, "Red") { Launch.network(this) }
        list += Pill(R.drawable.ic_tune, getString(R.string.settings_title)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Solo se reconstruye si algo cambió, para no perder el foco al volver.
        val signature = list.joinToString("|") { it.text }
        if (signature == pillsSignature) return
        pillsSignature = signature

        pills.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (pill in list) {
            val view = inflater.inflate(R.layout.item_pill, pills, false) as TextView
            view.text = pill.text
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(pill.icon, 0, 0, 0)
            view.setOnClickListener { pill.action() }
            view.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(150).start()
            }
            pills.addView(view)
        }
    }

    // --- Tarjetas --------------------------------------------------------------------------

    override fun onTileClick(position: Int, app: AppEntry?) {
        if (adapter.movingIndex >= 0) return
        if (app == null) {
            startActivity(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_PICK, true))
        } else {
            Launch.app(this, app)
        }
    }

    override fun onTileFocused(position: Int, app: AppEntry?, art: AppArt?) {
        heroTitle.text = app?.label ?: getString(R.string.add_apps)
        pendingHeroArt = art
        handler.removeCallbacks(heroRunnable)
        handler.postDelayed(heroRunnable, 140)
    }

    private fun applyHeroBackground() {
        val bmp = pendingHeroArt?.ambient?.takeIf { prefs.ambientBackground }
        if (bmp == null) {
            heroBg.animate().alpha(0f).setDuration(250).start()
            heroBg.tag = null
            return
        }
        if (heroBg.tag === bmp) return
        heroBg.tag = bmp
        heroBg.animate().alpha(0f).setDuration(110).withEndAction {
            heroBg.setImageBitmap(bmp)
            heroBg.animate().alpha(0.55f).setDuration(400).start()
        }.start()
    }

    override fun onTileMenu(position: Int, app: AppEntry): Boolean {
        if (adapter.movingIndex >= 0) return true
        val options = arrayOf("Mover", "Quitar de Inicio", "Información de la app")
        AlertDialog.Builder(this, R.style.Dialog)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startMove(position)
                    1 -> removeFavorite(app)
                    2 -> Launch.appInfo(this, app.pkg)
                }
            }
            .show()
        return true
    }

    private fun removeFavorite(app: AppEntry) {
        prefs.favorites = prefs.favorites - app.pkg
        val index = adapter.items.indexOf(app)
        refresh()
        focusPosition(index.coerceAtMost(adapter.items.size).coerceAtLeast(0))
    }

    // --- Modo mover (como en tvOS: la app tiembla y se mueve con las flechas) ---------------

    private fun startMove(position: Int) {
        adapter.setMoving(position)
        focusPosition(position)
        Toast.makeText(this, "Usa las flechas para mover · OK para terminar", Toast.LENGTH_SHORT).show()
    }

    private fun endMove() {
        adapter.setMoving(-1)
        prefs.favorites = adapter.items.map { it.pkg }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val moving = adapter.movingIndex
        if (moving >= 0) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val cols = layoutManager.spanCount
            val target = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moving - 1
                KeyEvent.KEYCODE_DPAD_RIGHT -> moving + 1
                KeyEvent.KEYCODE_DPAD_UP -> moving - cols
                KeyEvent.KEYCODE_DPAD_DOWN -> moving + cols
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    endMove()
                    return true
                }
                else -> return true
            }
            if (target in adapter.items.indices && target != moving) {
                adapter.move(moving, target)
                grid.scrollToPosition(target)
                focusPosition(target)
            }
            return true
        }
        // Atrás no sale del launcher.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isHome()) {
            if (event.action == KeyEvent.ACTION_UP) {
                grid.scrollToPosition(0)
                focusPosition(0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isHome() = intent?.hasCategory(Intent.CATEGORY_HOME) == true
}
