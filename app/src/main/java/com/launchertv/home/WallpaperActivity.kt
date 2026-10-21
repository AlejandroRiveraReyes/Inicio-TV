package com.launchertv.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Selector de fondo de pantalla: el fondo enfocado se previsualiza detrás de la cuadrícula. */
class WallpaperActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: ImageView
    private lateinit var adapter: Adapter
    private var previewKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper)
        prefs = Prefs(this)
        preview = findViewById(R.id.preview)
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 4)
        adapter = Adapter()
        grid.adapter = adapter
        val selected = Wallpapers.ALL.indexOf(Wallpapers.find(prefs.wallpaper))
        grid.post { grid.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus() }
    }

    private fun showPreview(wp: Wallpapers.Wallpaper) {
        previewKey = wp.key
        Wallpapers.load(this, wp, 2) { bmp ->
            if (previewKey != wp.key) return@load
            preview.animate().alpha(0f).setDuration(100).withEndAction {
                preview.setImageBitmap(bmp)
                preview.animate().alpha(if (bmp != null) 1f else 0f).setDuration(300).start()
            }.start()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun getItemCount() = Wallpapers.ALL.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(Wallpapers.ALL[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val card: View = view.findViewById(R.id.card)
            private val image: ImageView = view.findViewById(R.id.image)
            private val check: View = view.findViewById(R.id.check)
            private val label: TextView = view.findViewById(R.id.label)
            private val density = view.resources.displayMetrics.density
            private var key: String? = null

            init {
                card.clipToOutline = true
                view.setOnClickListener {
                    val wp = Wallpapers.ALL[bindingAdapterPosition]
                    prefs.wallpaper = wp.key
                    notifyDataSetChanged()
                }
                view.setOnFocusChangeListener { _, focused ->
                    val s = if (focused) 1.1f else 1f
                    card.animate().scaleX(s).scaleY(s)
                        .translationZ(if (focused) 12 * density else 0f)
                        .setDuration(180).setInterpolator(DecelerateInterpolator(1.6f)).start()
                    itemView.translationZ = if (focused) 1f else 0f
                    if (focused) showPreview(Wallpapers.ALL[bindingAdapterPosition])
                }
            }

            fun bind(wp: Wallpapers.Wallpaper) {
                key = wp.key
                label.text = wp.name
                check.visibility = if (prefs.wallpaper == wp.key) View.VISIBLE else View.GONE
                image.setImageDrawable(null)
                Wallpapers.load(itemView.context, wp, 4) { bmp ->
                    if (key == wp.key) image.setImageBitmap(bmp)
                }
            }
        }
    }
}
