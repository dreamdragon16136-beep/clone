package com.imperialreader.ui.details

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.imperialreader.R
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.ui.common.LibraryActions
import com.imperialreader.ui.reader.ReaderActivity
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.relativeTime
import com.imperialreader.util.statusLabel
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

data class ChapterRow(val chapter: ChapterEntity, val progress: Int?, val selected: Boolean = false)

class MangaDetailsActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "manga_id"
        fun intent(ctx: Context, mangaId: Long): Intent =
            Intent(ctx, MangaDetailsActivity::class.java).putExtra(EXTRA_ID, mangaId)
    }

    private var mangaId = -1L
    private var manga: MangaEntity? = null
    private var rows: List<ChapterRow> = emptyList()
    private val reversed = MutableStateFlow(false)
    private val selectedChapterIds = linkedSetOf<Long>()
    private var chapterSelectionMode = false
    private var chapterSelectionAnchorId: Long? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var headerAdapter: HeaderAdapter
    private lateinit var chapterAdapter: ChapterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_details)
        mangaId = intent.getLongExtra(EXTRA_ID, -1L)
        if (mangaId < 0) {
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.details_menu)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        swipe = findViewById(R.id.swipe)
        fab = findViewById(R.id.fabRead)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        headerAdapter = HeaderAdapter(
            onLibrary = { toggleLibrary() },
            onWeb = { openWeb() }
        )
        chapterAdapter = ChapterAdapter(
            onClick = { row ->
                if (chapterSelectionMode) toggleChapterSelection(row.chapter.id)
                else startActivity(ReaderActivity.intent(this, row.chapter.id))
            },
            onLong = { row, anchor ->
                val wasInSelectionMode = chapterSelectionMode
                if (wasInSelectionMode) {
                    if (row.chapter.id in selectedChapterIds) {
                        showChapterMenu(row, anchor)
                    } else {
                        chapterSelectionAnchorId?.let { selectChapterRange(it, row.chapter.id) }
                            ?: toggleChapterSelection(row.chapter.id)
                    }
                } else {
                    chapterSelectionMode = true
                    selectedChapterIds.clear()
                    selectedChapterIds.add(row.chapter.id)
                    chapterSelectionAnchorId = row.chapter.id
                    renderChapterSelection()
                }
                if (!wasInSelectionMode && row.chapter.read) showChapterMenu(row, anchor)
            },
            onOptions = { row, anchor -> showChapterMenu(row, anchor) },
            onDownload = { row -> onDownloadClick(row) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ConcatAdapter(headerAdapter, chapterAdapter)

        swipe.setOnRefreshListener { refresh() }
        fab.setOnClickListener {
            val target = resumeTarget()
            if (target != null) startActivity(ReaderActivity.intent(this, target.id))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.db.mangaDao().observe(mangaId).filterNotNull().collect { m ->
                        manga = m
                        headerAdapter.update(m, rows.size)
                        updateChapterSelectionMenu()
                    }
                }
                launch {
                    combine(app.db.chapterDao().observe(mangaId), app.downloads.progress, reversed) { list, progress, rev ->
                        val mapped = list.map { ChapterRow(it, progress[it.id], it.id in selectedChapterIds) }
                        if (rev) mapped.reversed() else mapped
                    }.collect { list ->
                        selectedChapterIds.retainAll(list.map { it.chapter.id }.toSet())
                        if (chapterSelectionAnchorId == null ||
                            chapterSelectionAnchorId !in selectedChapterIds
                        ) {
                            chapterSelectionAnchorId = selectedChapterIds.firstOrNull()
                        }
                        rows = list
                        chapterAdapter.submitList(list)
                        manga?.let { headerAdapter.update(it, list.size) }
                        updateFab()
                        updateChapterSelectionMenu()
                    }
                }
                launch {
                    app.downloads.errors.collect { msg ->
                        Snackbar.make(findViewById(R.id.recycler), msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            val m = app.db.mangaDao().getById(mangaId)
            if (m != null && !m.initialized) refresh()
        }
    }

    // ------------------------------------------------------------ reading order helpers

    /** Chapters oldest → newest, regardless of how the list is displayed. */
    private fun readingOrder(): List<ChapterEntity> = rows.map { it.chapter }.sortedByDescending { it.sourceOrder }

    private fun resumeTarget(): ChapterEntity? {
        val ordered = readingOrder()
        if (ordered.isEmpty()) return null
        return ordered.firstOrNull { !it.read } ?: ordered.first()
    }

    private fun updateFab() {
        val ordered = readingOrder()
        if (ordered.isEmpty()) {
            fab.visibility = View.GONE
            return
        }
        fab.visibility = View.VISIBLE
        val anyProgress = ordered.any { it.read || it.lastPageRead > 0 }
        fab.text = when {
            ordered.all { it.read } -> "Read again"
            anyProgress -> "Resume"
            else -> "Start"
        }
    }

    // ------------------------------------------------------------ actions

    private fun refresh() {
        val m = manga ?: return
        lifecycleScope.launch {
            swipe.isRefreshing = true
            try {
                if (m.source == LOCAL_SOURCE_ID) {
                    LocalScanner(this@MangaDetailsActivity).scan()
                } else {
                    app.repo.refresh(m)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareException) {
                Snackbar.make(findViewById(R.id.recycler), "Cloudflare challenge", Snackbar.LENGTH_LONG)
                    .setAction("Solve") { startActivity(WebViewActivity.intent(this@MangaDetailsActivity, e.url)) }
                    .show()
            } catch (e: Exception) {
                toast("Refresh failed: ${e.message}")
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun toggleLibrary() {
        val m = manga ?: return
        lifecycleScope.launch {
            if (m.favorite) {
                confirmRemoveFromLibrary(m)
            } else if (LibraryActions.addToLibrary(this@MangaDetailsActivity, m)) {
                toast("Added to library")
            }
        }
    }

    private fun confirmRemoveFromLibrary(manga: MangaEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove “${manga.title}” from library?")
            .setItems(arrayOf("Remove and keep downloaded files", "Delete downloaded files and remove")) { _, which ->
                lifecycleScope.launch {
                    try {
                        if (which == 1) {
                            app.db.chapterDao().getAll(manga.id).filter { it.downloaded }.forEach { chapter ->
                                app.downloads.deleteDownload(manga, chapter)
                            }
                        }
                        app.repo.toggleFavorite(manga)
                        toast("Removed from library")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast("Could not remove entry: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun absoluteUrl(): String? {
        val m = manga ?: return null
        if (m.source == CombinedSource.ID) return CombinedSource.webUrl(m.url)
        val source = app.sources.get(m.source)
        if (source != null) return if (m.url.startsWith("http")) m.url else source.baseUrl.trimEnd('/') + m.url
        return m.url.takeIf { BookSources.get(m.source) != null && it.startsWith("http") }
    }

    private fun openWeb() {
        val url = absoluteUrl()
        if (url == null) toast("No web page for this entry") else startActivity(WebViewActivity.intent(this, url))
    }

    private fun onMenu(id: Int) {
        when (id) {
            R.id.action_sort -> reversed.value = !reversed.value
            R.id.action_refresh -> refresh()
            R.id.action_download -> showDownloadDialog()
            R.id.action_mark_all_read -> markAll(true)
            R.id.action_mark_all_unread -> markAll(false)
            R.id.action_delete_downloads -> deleteAllDownloads()
            R.id.action_select_chapters -> {
                chapterSelectionMode = true
                updateChapterSelectionMenu()
            }
            R.id.action_select_all_chapters -> selectAllChapters()
            R.id.action_mark_selected_read -> markSelectedChapters(true)
            R.id.action_mark_selected_unread -> markSelectedChapters(false)
            R.id.action_cancel_chapter_selection -> clearChapterSelection()
        }
    }

    private fun updateChapterSelectionMenu() {
        if (!::toolbar.isInitialized || toolbar.menu.size() == 0) return
        toolbar.title = when {
            !chapterSelectionMode -> manga?.title.orEmpty()
            selectedChapterIds.isEmpty() -> "Select chapters"
            else -> "${selectedChapterIds.size} selected"
        }
        val menu = toolbar.menu
        menu.findItem(R.id.action_download)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_sort)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_refresh)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_mark_all_read)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_mark_all_unread)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_delete_downloads)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_select_chapters)?.isVisible = !chapterSelectionMode
        menu.findItem(R.id.action_select_all_chapters)?.isVisible = chapterSelectionMode
        menu.findItem(R.id.action_mark_selected_read)?.isVisible =
            chapterSelectionMode && selectedChapterIds.isNotEmpty()
        menu.findItem(R.id.action_mark_selected_unread)?.isVisible =
            chapterSelectionMode && selectedChapterIds.isNotEmpty()
        menu.findItem(R.id.action_cancel_chapter_selection)?.isVisible = chapterSelectionMode
    }

    private fun toggleChapterSelection(chapterId: Long) {
        if (!chapterSelectionMode) chapterSelectionMode = true
        if (!selectedChapterIds.add(chapterId)) {
            selectedChapterIds.remove(chapterId)
            if (chapterSelectionAnchorId == chapterId) {
                chapterSelectionAnchorId = selectedChapterIds.firstOrNull()
            }
        } else if (chapterSelectionAnchorId == null) {
            chapterSelectionAnchorId = chapterId
        }
        if (selectedChapterIds.isEmpty()) {
            chapterSelectionMode = false
            chapterSelectionAnchorId = null
        }
        renderChapterSelection()
    }

    private fun selectChapterRange(anchorId: Long, chapterId: Long) {
        val anchorPosition = rows.indexOfFirst { it.chapter.id == anchorId }
        val chapterPosition = rows.indexOfFirst { it.chapter.id == chapterId }
        if (anchorPosition < 0 || chapterPosition < 0) return

        val firstPosition = minOf(anchorPosition, chapterPosition)
        val lastPosition = maxOf(anchorPosition, chapterPosition)
        rows.subList(firstPosition, lastPosition + 1)
            .mapTo(selectedChapterIds) { it.chapter.id }
        renderChapterSelection()
    }

    private fun selectAllChapters() {
        val ids = rows.map { it.chapter.id }
        if (ids.isNotEmpty() && ids.all { it in selectedChapterIds }) selectedChapterIds.removeAll(ids.toSet())
        else selectedChapterIds.addAll(ids)
        if (chapterSelectionAnchorId == null || chapterSelectionAnchorId !in selectedChapterIds) {
            chapterSelectionAnchorId = selectedChapterIds.firstOrNull()
        }
        if (selectedChapterIds.isEmpty()) {
            chapterSelectionMode = false
            chapterSelectionAnchorId = null
        }
        renderChapterSelection()
    }

    private fun clearChapterSelection() {
        chapterSelectionMode = false
        selectedChapterIds.clear()
        chapterSelectionAnchorId = null
        renderChapterSelection()
    }

    private fun renderChapterSelection() {
        rows = rows.map { it.copy(selected = it.chapter.id in selectedChapterIds) }
        chapterAdapter.submitList(rows)
        updateChapterSelectionMenu()
    }

    private fun markSelectedChapters(read: Boolean) {
        val ids = selectedChapterIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            ids.chunked(400).forEach { app.db.chapterDao().setRead(it, read) }
            clearChapterSelection()
            toast(if (read) "Marked ${ids.size} chapters as read" else "Marked ${ids.size} chapters as unread")
        }
    }

    private fun markAll(read: Boolean) {
        val ids = rows.map { it.chapter.id }
        lifecycleScope.launch { app.db.chapterDao().setRead(ids, read) }
    }

    private fun showDownloadDialog() {
        val m = manga ?: return
        if (m.source == LOCAL_SOURCE_ID) {
            toast("Local entries are already on your device")
            return
        }
        val options = arrayOf("Next unread chapter", "Next 5 unread", "Next 10 unread", "All unread", "All chapters")
        MaterialAlertDialogBuilder(this)
            .setTitle("Download")
            .setItems(options) { _, which ->
                val ordered = readingOrder()
                val unread = ordered.filter { !it.read }
                val chosen = when (which) {
                    0 -> unread.take(1)
                    1 -> unread.take(5)
                    2 -> unread.take(10)
                    3 -> unread
                    else -> ordered
                }.filter { !it.downloaded }
                if (chosen.isEmpty()) toast("Nothing to download") else {
                    app.downloads.enqueue(m, chosen)
                    toast("Queued ${chosen.size} chapter(s)")
                }
            }
            .show()
    }

    private fun deleteAllDownloads() {
        val m = manga ?: return
        val downloaded = rows.map { it.chapter }.filter { it.downloaded }
        if (downloaded.isEmpty()) {
            toast("No downloads")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${downloaded.size} downloaded chapter(s)?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { downloaded.forEach { app.downloads.deleteDownload(m, it) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDownloadClick(row: ChapterRow) {
        val m = manga ?: return
        when {
            row.progress != null -> toast("Already queued")
            row.chapter.downloaded -> MaterialAlertDialogBuilder(this)
                .setTitle("Delete download?")
                .setMessage(row.chapter.name)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { app.downloads.deleteDownload(m, row.chapter) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            m.source == LOCAL_SOURCE_ID -> toast("Local entries are already on your device")
            else -> {
                app.downloads.enqueue(m, listOf(row.chapter))
                toast("Queued")
            }
        }
    }

    private fun showChapterMenu(row: ChapterRow, anchor: View) {
        val c = row.chapter
        val dao = app.db.chapterDao()
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 1, "Mark as read")
        popup.menu.add(0, 4, 2, "Mark as unread")
        popup.menu.add(0, 5, 3, "Select all chapters")
        popup.menu.add(0, 6, 4, "Cancel selection")
        popup.menu.add(0, 2, 5, "Mark previous as read")
        popup.menu.add(0, 3, 6, if (c.bookmark) "Remove bookmark" else "Bookmark")
        popup.menu.findItem(6).isVisible = chapterSelectionMode
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 5) {
                chapterSelectionMode = true
                selectedChapterIds.clear()
                selectAllChapters()
                return@setOnMenuItemClickListener true
            }
            if (item.itemId == 6) {
                clearChapterSelection()
                return@setOnMenuItemClickListener true
            }
            lifecycleScope.launch {
                when (item.itemId) {
                    1 -> dao.setRead(if (chapterSelectionMode) selectedChapterIds.toList() else listOf(c.id), true)
                    4 -> dao.setRead(if (chapterSelectionMode) selectedChapterIds.toList() else listOf(c.id), false)
                    2 -> dao.setRead(rows.map { it.chapter }.filter { it.sourceOrder > c.sourceOrder }.map { it.id }, true)
                    3 -> dao.setBookmark(c.id, !c.bookmark)
                }
                if (chapterSelectionMode && item.itemId in setOf(1, 4)) clearChapterSelection()
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------ adapters

    private inner class HeaderAdapter(
        val onLibrary: () -> Unit,
        val onWeb: () -> Unit
    ) : RecyclerView.Adapter<HeaderAdapter.VH>() {

        private var m: MangaEntity? = null
        private var count = 0
        private var expanded = false

        fun update(manga: MangaEntity, chapterCount: Int) {
            m = manga
            count = chapterCount
            notifyItemChanged(0)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.hCover)
            val title: TextView = v.findViewById(R.id.hTitle)
            val author: TextView = v.findViewById(R.id.hAuthor)
            val status: TextView = v.findViewById(R.id.hStatus)
            val btnLibrary: MaterialButton = v.findViewById(R.id.btnLibrary)
            val btnWeb: MaterialButton = v.findViewById(R.id.btnWeb)
            val desc: TextView = v.findViewById(R.id.hDesc)
            val genres: TextView = v.findViewById(R.id.hGenres)
            val chapters: TextView = v.findViewById(R.id.hChapterCount)
        }

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_manga_header, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val manga = m ?: return
            val source = app.sources.get(manga.source)
            h.cover.load(manga.coverUrl) { source?.imageHeaders()?.forEach { (k, v) -> addHeader(k, v) } }
            h.title.text = manga.title
            h.title.setOnLongClickListener {
                h.title.context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Title", manga.title))
                h.title.context.toast("Title copied")
                true
            }
            val people = listOfNotNull(manga.author, manga.artist?.takeIf { it != manga.author }).joinToString(" • ")
            h.author.text = people.ifBlank { "Unknown author" }
            val sourceLabel = when {
                manga.source == CombinedSource.ID -> "Combined (${CombinedSource.decode(manga.url).size} sources)"
                manga.source == LOCAL_SOURCE_ID -> "Local"
                else -> source?.name ?: BookSources.get(manga.source)?.name ?: manga.source
            }
            h.status.text = statusLabel(manga.status) + " • " + sourceLabel
            h.btnLibrary.text = if (manga.favorite) "In library" else "Add to library"
            h.btnLibrary.setIconResource(if (manga.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            h.btnLibrary.setOnClickListener { onLibrary() }
            h.btnWeb.setOnClickListener { onWeb() }
            h.desc.text = manga.description ?: "No description."
            h.desc.maxLines = if (expanded) Int.MAX_VALUE else 4
            h.desc.setOnClickListener {
                expanded = !expanded
                notifyItemChanged(0)
            }
            h.genres.text = manga.genre.orEmpty()
            h.genres.visibility = if (manga.genre.isNullOrBlank()) View.GONE else View.VISIBLE
            h.chapters.text = "$count chapters"
        }
    }

    private inner class ChapterAdapter(
        val onClick: (ChapterRow) -> Unit,
        val onLong: (ChapterRow, View) -> Unit,
        val onOptions: (ChapterRow, View) -> Unit,
        val onDownload: (ChapterRow) -> Unit
    ) : ListAdapter<ChapterRow, ChapterAdapter.VH>(Diff) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.chTitle)
            val sub: TextView = v.findViewById(R.id.chSub)
            val selectionMark: TextView = v.findViewById(R.id.chSelectionMark)
            val frame: FrameLayout = v.findViewById(R.id.chDlFrame)
            val icon: ImageView = v.findViewById(R.id.chDlIcon)
            val progress: CircularProgressIndicator = v.findViewById(R.id.chDlProgress)
            val options: TextView = v.findViewById(R.id.chOptions)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = getItem(position)
            val c = row.chapter
            h.title.text = (if (c.bookmark) "★ " else "") + c.name
            h.title.alpha = if (c.read) 0.5f else 1f
            h.selectionMark.visibility = if (row.selected) View.VISIBLE else View.GONE
            h.sub.text = listOfNotNull(
                relativeTime(c.dateUpload).takeIf { it.isNotBlank() },
                c.scanlator?.takeIf { it.isNotBlank() },
                if (!c.read && c.lastPageRead > 0) "Page ${c.lastPageRead + 1}" else null
            ).joinToString(" • ")
            h.sub.alpha = if (c.read) 0.5f else 1f

            if (row.progress != null) {
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = row.progress == 0
                if (row.progress > 0) h.progress.setProgressCompat(row.progress, true)
                h.icon.visibility = View.GONE
            } else {
                h.progress.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.setImageResource(if (c.downloaded) R.drawable.ic_check else R.drawable.ic_download)
                h.icon.alpha = if (c.downloaded) 1f else 0.6f
            }
            h.frame.setOnClickListener { onDownload(row) }
            h.options.setOnClickListener { onOptions(row, h.itemView) }
            h.itemView.setOnClickListener { onClick(row) }
            h.itemView.setOnLongClickListener { onLong(row, h.itemView); true }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChapterRow>() {
        override fun areItemsTheSame(a: ChapterRow, b: ChapterRow) = a.chapter.id == b.chapter.id
        override fun areContentsTheSame(a: ChapterRow, b: ChapterRow) = a == b
    }
}
