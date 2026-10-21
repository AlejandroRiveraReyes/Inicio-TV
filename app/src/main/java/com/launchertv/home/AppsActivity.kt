package com.launchertv.home

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Todas las apps instaladas. En modo "elegir" (EXTRA_PICK), OK añade o quita la app de Inicio;
 * en modo normal, OK la abre y mantener OK la añade o quita.
 */
class AppsActivity : Activity(), TileAdapter.Host {

    private lateinit var prefs: Prefs
    private lateinit var adapter: TileAdapter
    private lateinit var grid: RecyclerView
    private lateinit var hint: TextView
    private var pick = false
    private val favorites = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        prefs = Prefs(this)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        favorites.addAll(prefs.favorites)

        findViewById<TextView>(R.id.title).setText(if (pick) R.string.pick_title else R.string.all_apps)
        hint = findViewById(R.id.hint)
        updateHint()

        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 6)
        adapter = TileAdapter(this, showAddTile = false, labelsAlways = true)
        grid.adapter = adapter

        AppRepository.apps(this) { apps ->
            adapter.submit(apps)
            grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun updateHint() {
        hint.text = if (pick) {
            getString(R.string.pick_hint) + " · ${favorites.size} en Inicio"
        } else {
            getString(R.string.apps_hint)
        }
    }

    override fun onTileClick(position: Int, app: AppEntry?) {
        app ?: return
        if (pick) toggle(position, app) else Launch.app(this, app)
    }

    override fun onTileMenu(position: Int, app: AppEntry): Boolean {
        val added = toggle(position, app)
        Toast.makeText(
            this,
            if (added) "${app.label} añadida a Inicio" else "${app.label} quitada de Inicio",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    override fun isChecked(app: AppEntry) = app.pkg in favorites

    /** Devuelve true si la app ha quedado en Inicio. */
    private fun toggle(position: Int, app: AppEntry): Boolean {
        val added = if (favorites.remove(app.pkg)) false else favorites.add(app.pkg)
        prefs.favorites = favorites
        adapter.refreshCheck(position)
        updateHint()
        return added
    }

    companion object {
        const val EXTRA_PICK = "pick"
    }
}
