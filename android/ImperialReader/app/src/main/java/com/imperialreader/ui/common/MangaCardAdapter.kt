package com.imperialreader.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.imperialreader.R
import com.imperialreader.util.dp
import com.imperialreader.util.toast

data class CardItem(
    val key: String,
    val title: String,
    val cover: String?,
    val badge1: String? = null,
    val badge2: String? = null,
    val dim: Boolean = false,
    val selected: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val tag: Any? = null
)

/** Cover-grid card used by Library, Browse and Global search (Mihon-style). */
class MangaCardAdapter(
    private val fixedWidthDp: Int? = null,
    private val onClick: (CardItem) -> Unit,
    private val onLongClick: ((CardItem) -> Unit)? = null
) : ListAdapter<CardItem, MangaCardAdapter.VH>(Diff) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val badge1: TextView = view.findViewById(R.id.badgeUnread)
        val badge2: TextView = view.findViewById(R.id.badgeDl)
        val selectedMark: TextView = view.findViewById(R.id.selectionMark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_manga_card, parent, false)
        if (fixedWidthDp != null) {
            val lp = v.layoutParams
            lp.width = parent.context.dp(fixedWidthDp)
            v.layoutParams = lp
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.cover.load(item.cover) {
            item.headers.forEach { (k, v) -> addHeader(k, v) }
        }
        holder.itemView.alpha = if (item.dim) 0.5f else 1f
        bindBadge(holder.badge1, item.badge1)
        bindBadge(holder.badge2, item.badge2)
        holder.selectedMark.visibility = if (item.selected) View.VISIBLE else View.GONE
        holder.itemView.isActivated = item.selected
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(item)
            onLongClick != null
        }
        holder.title.setOnLongClickListener {
            val clipboard = holder.title.context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Title", item.title))
            holder.title.context.toast("Title copied")
            true
        }
    }

    private fun bindBadge(tv: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<CardItem>() {
        override fun areItemsTheSame(a: CardItem, b: CardItem) = a.key == b.key
        override fun areContentsTheSame(a: CardItem, b: CardItem) = a == b
    }
}
