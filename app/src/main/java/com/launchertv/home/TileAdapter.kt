package com.launchertv.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Fija la proporción 16:9 de las tarjetas (la de los banners de Android TV). */
class AspectFrameLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY),
        )
    }
}

class TileAdapter(
    private val host: Host,
    private val showAddTile: Boolean,
    private val labelsAlways: Boolean,
) : RecyclerView.Adapter<TileAdapter.VH>() {

    interface Host {
        fun onTileClick(position: Int, app: AppEntry?)
        fun onTileMenu(position: Int, app: AppEntry): Boolean = false
        fun onTileFocused(position: Int, app: AppEntry?, art: AppArt?) {}
        fun isChecked(app: AppEntry): Boolean = false
    }

    val items = ArrayList<AppEntry>()
    var movingIndex = -1
        private set
    private var recycler: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<AppEntry>) {
        if (list == items) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun isAddTile(position: Int) = position >= items.size

    fun setMoving(index: Int) {
        val old = movingIndex
        movingIndex = index
        holderAt(old)?.setMoving(false)
        holderAt(index)?.setMoving(true)
    }

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        if (movingIndex == from) movingIndex = to
        notifyItemMoved(from, to)
    }

    fun refreshCheck(position: Int) = notifyItemChanged(position, PAYLOAD_CHECK)

    private fun holderAt(position: Int): VH? =
        if (position < 0) null else recycler?.findViewHolderForAdapterPosition(position) as? VH

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun getItemCount() = items.size + if (showAddTile) 1 else 0

    override fun getItemId(position: Int): Long =
        if (isAddTile(position)) Long.MIN_VALUE else items[position].pkg.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECK) && !isAddTile(position)) {
            holder.bindCheck(items[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (isAddTile(position)) holder.bindAdd() else holder.bind(items[position])
        holder.setMoving(position == movingIndex)
    }

    override fun onViewRecycled(holder: VH) {
        holder.setMoving(false)
        holder.boundKey = null
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val innerLabel: TextView = view.findViewById(R.id.innerLabel)
        private val label: TextView = view.findViewById(R.id.label)
        private val check: ImageView = view.findViewById(R.id.check)
        private val density = view.resources.displayMetrics.density
        var boundKey: String? = null
        private var art: AppArt? = null
        private var wiggle: ObjectAnimator? = null

        init {
            label.alpha = if (labelsAlways) 1f else 0f
            card.clipToOutline = true
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) host.onTileClick(p, items.getOrNull(p))
            }
            view.setOnLongClickListener { openMenu() }
            view.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && openMenu()
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                animateFocus(hasFocus)
                if (hasFocus) notifyFocus()
            }
        }

        private fun openMenu(): Boolean {
            val p = bindingAdapterPosition
            val app = items.getOrNull(p) ?: return false
            return host.onTileMenu(p, app)
        }

        private fun notifyFocus() {
            val p = bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) host.onTileFocused(p, items.getOrNull(p), art)
        }

        private fun animateFocus(focused: Boolean) {
            val scale = if (focused) 1.12f else 1f
            card.animate()
                .scaleX(scale).scaleY(scale)
                .translationZ(if (focused) 14 * density else 0f)
                .setDuration(if (focused) 200 else 150)
                .setInterpolator(DECELERATE)
                .start()
            // La tarjeta enfocada se dibuja por encima de sus vecinas.
            itemView.translationZ = if (focused) 1f else 0f
            if (!labelsAlways) {
                label.animate().alpha(if (focused) 1f else 0f)
                    .translationY(if (focused) 6 * density else 0f)
                    .setDuration(160).start()
            } else {
                label.animate().translationY(if (focused) 6 * density else 0f).setDuration(160).start()
            }
        }

        fun bind(app: AppEntry) {
            boundKey = app.pkg
            art = null
            label.text = app.label
            innerLabel.text = app.label
            banner.setImageDrawable(null)
            icon.setImageDrawable(null)
            icon.imageTintList = null
            setCardColor(itemView.context.getColor(R.color.tile))
            bindCheck(app)
            AppRepository.art(itemView.context, app) { a ->
                if (boundKey == app.pkg) applyArt(a)
            }
        }

        fun bindCheck(app: AppEntry) {
            check.visibility = if (host.isChecked(app)) View.VISIBLE else View.GONE
        }

        private fun applyArt(a: AppArt) {
            art = a
            if (a.banner != null) {
                banner.visibility = View.VISIBLE
                banner.setImageBitmap(a.banner)
                icon.visibility = View.GONE
                innerLabel.visibility = View.GONE
            } else {
                banner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageBitmap(a.icon)
                innerLabel.visibility = View.VISIBLE
                setCardColor(a.color)
            }
            if (itemView.hasFocus()) notifyFocus()
        }

        fun bindAdd() {
            boundKey = KEY_ADD
            art = null
            val ctx = itemView.context
            banner.visibility = View.GONE
            icon.visibility = View.VISIBLE
            icon.setImageResource(R.drawable.ic_add)
            icon.imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
            innerLabel.visibility = View.VISIBLE
            innerLabel.text = ctx.getString(R.string.add)
            label.text = ctx.getString(R.string.add_apps)
            check.visibility = View.GONE
            setCardColor(ctx.getColor(R.color.tile_add))
        }

        private fun setCardColor(color: Int) {
            (card.background.mutate() as? GradientDrawable)?.setColor(color)
        }

        fun setMoving(moving: Boolean) {
            if (moving) {
                if (wiggle != null) return
                wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.4f, 1.4f).apply {
                    duration = 140
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else {
                wiggle?.cancel()
                wiggle = null
                card.rotation = 0f
            }
        }
    }

    companion object {
        private const val PAYLOAD_CHECK = "check"
        private const val KEY_ADD = "+"
        private val DECELERATE = DecelerateInterpolator(1.6f)
    }
}
