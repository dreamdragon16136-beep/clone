package com.imperialreader.ui.reader

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Size
import coil.transform.Transformation
import com.imperialreader.data.source.MangaFirePageTransform
import com.imperialreader.util.dp

/**
 * One adapter for both reader styles.
 * - paged modes (LTR/RTL): each page fills the ViewPager2
 * - webtoon mode: full-width images stacked vertically
 */
class PageAdapter(
    private val headers: Map<String, String>,
    private val webtoon: Boolean,
    private val onSavePage: (ImageView, String) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    var pages: List<String> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(
        val root: FrameLayout,
        val image: ImageView,
        val progress: ProgressBar,
        val error: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(match, if (webtoon) wrap else match)
            if (webtoon) minimumHeight = ctx.dp(320)
        }
        val image = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(match, if (webtoon) wrap else match)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (webtoon) adjustViewBounds = true
        }
        val progress = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(ctx.dp(36), ctx.dp(36), android.view.Gravity.CENTER)
        }
        val error = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(wrap, wrap, android.view.Gravity.CENTER)
            setTextColor(0xFFA0A0A0.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
            visibility = View.GONE
        }
        root.addView(image)
        root.addView(progress)
        root.addView(error)
        return VH(root, image, progress, error)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        load(h, position)
    }

    private fun load(h: VH, position: Int) {
        val url = pages[position]
        h.image.setOnLongClickListener {
            onSavePage(h.image, url)
            true
        }
        h.error.visibility = View.GONE
        h.progress.visibility = View.VISIBLE
        h.image.load(url) {
            headers.forEach { (k, v) -> addHeader(k, v) }
            MangaFirePageTransform.offset(url)?.let { transformations(MangaFireImageTransformation(it)) }
            listener(
                onSuccess = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.root.minimumHeight = 0
                },
                onError = { _, _ ->
                    h.progress.visibility = View.GONE
                    h.error.text = "Page ${position + 1} failed to load\nTap to retry"
                    h.error.visibility = View.VISIBLE
                    h.error.setOnClickListener { load(h, position) }
                }
            )
        }
    }

    private class MangaFireImageTransformation(private val offset: Int) : Transformation {
        override val cacheKey = "mangafire-scramble-$offset"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap =
            MangaFirePageTransform.unscramble(input, offset)
    }

    override fun onViewRecycled(holder: VH) {
        holder.image.setImageDrawable(null)
        if (webtoon) holder.root.minimumHeight = holder.root.context.dp(320)
        super.onViewRecycled(holder)
    }
}
