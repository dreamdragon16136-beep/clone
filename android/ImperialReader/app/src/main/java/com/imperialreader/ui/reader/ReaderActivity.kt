package com.imperialreader.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.AppPrefs
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.abs

class ReaderActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPTER = "chapter_id"
        fun intent(ctx: Context, chapterId: Long): Intent =
            Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_CHAPTER, chapterId)
    }

    private lateinit var root: View
    private lateinit var pager: ViewPager2
    private lateinit var webtoon: RecyclerView
    private lateinit var progressBar: View
    private lateinit var errorView: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seek: SeekBar
    private lateinit var txtPage: TextView
    private lateinit var txtTotal: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnMode: MaterialButton
    private lateinit var nextChapterPrompt: LinearLayout
    private lateinit var nextChapterStar: StarSpriteView
    private lateinit var nextChapterMessage: TextView
    private lateinit var gesture: GestureDetector

    private var manga: MangaEntity? = null
    private var chapters: List<ChapterEntity> = emptyList() // oldest → newest
    private var index = 0
    private var currentChapterId = -1L
    private var pages: List<String> = emptyList()
    private var headers: Map<String, String> = emptyMap()
    private var currentPage = 0
    private var mode = AppPrefs.READER_RTL
    private var overlaysVisible = false
    private var restoring = false
    private var chapterTransitionActive = false
    private var loadJob: Job? = null
    private var pendingSaveBitmap: android.graphics.Bitmap? = null
    private val requestGalleryPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val bitmap = pendingSaveBitmap
            pendingSaveBitmap = null
            if (granted && bitmap != null) savePageBitmap(bitmap)
            else if (!granted) {
                bitmap?.recycle()
                toast("Storage permission is needed to save pages")
            }
        }

    private val pagerMode get() = mode != AppPrefs.READER_VERTICAL
    private val rtl get() = mode == AppPrefs.READER_RTL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = findViewById(R.id.readerRoot)
        pager = findViewById(R.id.pager)
        webtoon = findViewById(R.id.webtoon)
        progressBar = findViewById(R.id.readerProgress)
        errorView = findViewById(R.id.readerError)
        topBar = findViewById(R.id.readerTop)
        bottomBar = findViewById(R.id.readerBottom)
        seek = findViewById(R.id.seek)
        txtPage = findViewById(R.id.txtPage)
        txtTotal = findViewById(R.id.txtTotal)
        title = findViewById(R.id.readerTitle)
        subtitle = findViewById(R.id.readerSubtitle)
        btnMode = findViewById(R.id.btnMode)
        nextChapterPrompt = findViewById(R.id.nextChapterPrompt)
        nextChapterStar = findViewById(R.id.nextChapterStar)
        nextChapterMessage = findViewById(R.id.nextChapterMessage)

        mode = app.prefs.readerMode
        if (app.prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val topBase = topBar.paddingTop
        val bottomBase = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = topBase + bars.top)
            bottomBar.updatePadding(bottom = bottomBase + bars.bottom)
            insets
        }

        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        applySystemBars(visible = !app.prefs.fullscreen)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnPrevCh).setOnClickListener { openAdjacent(-1) }
        findViewById<ImageButton>(R.id.btnNextCh).setOnClickListener { openAdjacent(1) }
        btnMode.setOnClickListener { showModeDialog() }
        nextChapterPrompt.setOnClickListener { openAdjacent(1) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) goToPage(progress, smooth = false)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (pagerMode) onPageChanged(position)
            }
        })
        webtoon.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (pagerMode || pages.isEmpty()) return
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val pos = if (lastVisible == pages.lastIndex) lastVisible else lm.findFirstVisibleItemPosition()
                if (pos >= 0) onPageChanged(pos)
                updateNextChapterPrompt()
            }
        })
        var pulledNextChapter = false
        val webtoonPullGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2.rawY >= e1.rawY || webtoon.canScrollVertically(1) ||
                    index >= chapters.lastIndex || pages.isEmpty()
                ) return false
                webtoon.stopScroll()
                pulledNextChapter = true
                openAdjacent(1)
                return true
            }
        })
        webtoon.setOnTouchListener { _, event ->
            webtoonPullGesture.onTouchEvent(event)
            val consume = event.actionMasked == MotionEvent.ACTION_UP && pulledNextChapter
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pulledNextChapter = false
            }
            consume
        }

        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!pagerMode || pages.isEmpty() || e1 == null || !isInside(pager, e1)) return false
                if (overlaysVisible && (isInside(topBar, e1) || isInside(bottomBar, e1))) return false
                val dx = e2.rawX - e1.rawX
                val dy = e2.rawY - e1.rawY
                if (abs(dx) < 48 * resources.displayMetrics.density || abs(dx) <= abs(dy)) return false
                if ((rtl && dx > 0) || (!rtl && dx < 0)) nextPage() else prevPage()
                return true
            }
        })

        loadChapter(intent.getLongExtra(EXTRA_CHAPTER, -1L), null)
    }

    // ------------------------------------------------------------------ loading

    private fun loadChapter(chapterId: Long, startPage: Int?) {
        currentChapterId = chapterId
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showLoading()
            try {
                val chapterDao = app.db.chapterDao()
                val ch = chapterDao.getById(chapterId) ?: throw IOException("Chapter not found")
                val m = app.db.mangaDao().getById(ch.mangaId) ?: throw IOException("Manga not found")
                manga = m
                mode = app.prefs.readerModeFor(m.id)
                chapters = chapterDao.getAll(m.id).sortedByDescending { it.sourceOrder }
                index = chapters.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
                val current = chapters[index]
                title.text = m.title
                subtitle.text = current.name

                when (val loaded = ChapterLoader.load(this@ReaderActivity, m, current)) {
                    is LoadedChapter.Epub -> {
                        startActivity(EpubReaderActivity.intent(this@ReaderActivity, current.id, loaded.uri))
                        finish()
                        return@launch
                    }
                    is LoadedChapter.Pdf -> {
                        startActivity(PdfReaderActivity.intent(this@ReaderActivity, current.id, loaded.uri))
                        finish()
                        return@launch
                    }
                    is LoadedChapter.Images -> {
                        pages = loaded.pages
                        headers = loaded.headers
                    }
                }
                app.repo.recordHistory(current.id)
                val start = startPage ?: if (current.read) 0 else current.lastPageRead
                applyPages(start.coerceIn(0, pages.lastIndex))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        if (!chapterTransitionActive) hideNextChapterPrompt()
    }

    private fun showError(e: Exception) {
        chapterTransitionActive = false
        nextChapterMessage.text = "Pull up to read next chapter"
        hideNextChapterPrompt()
        progressBar.visibility = View.GONE
        pager.visibility = View.GONE
        webtoon.visibility = View.GONE
        val cloudflare = e is CloudflareException
        errorView.text = if (cloudflare) {
            "Cloudflare protection detected.\nTap to open the site and solve it, then come back and retry."
        } else {
            "Couldn't load this chapter:\n${e.message}\n\nTap to retry"
        }
        errorView.visibility = View.VISIBLE
        errorView.setOnClickListener {
            if (e is CloudflareException) startActivity(WebViewActivity.intent(this, e.url))
            else loadChapter(currentChapterId, null)
        }
        setOverlays(true)
    }

    private fun applyPages(start: Int) {
        progressBar.visibility = View.GONE
        errorView.visibility = View.GONE
        restoring = true
        val adapter = PageAdapter(headers, webtoon = !pagerMode, onSavePage = { image, _ ->
            if (image.drawable == null) toast("Wait for the page to finish loading before saving it")
            else GalleryPageSaver.captureDisplayedPage(this, image) { bitmap ->
                if (bitmap == null) toast("Could not capture the displayed page")
                else confirmSavePage(bitmap)
            }
        })
        adapter.pages = pages

        if (pagerMode) {
            webtoon.visibility = View.GONE
            pager.visibility = View.VISIBLE
            pager.isUserInputEnabled = false
            pager.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            pager.adapter = adapter
            pager.offscreenPageLimit = 2
            pager.setCurrentItem(start, false)
        } else {
            pager.visibility = View.GONE
            webtoon.visibility = View.VISIBLE
            val lm = LinearLayoutManager(this)
            webtoon.layoutManager = lm
            webtoon.adapter = adapter
            lm.scrollToPositionWithOffset(start, 0)
        }

        seek.max = (pages.size - 1).coerceAtLeast(0)
        seek.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        txtTotal.text = pages.size.toString()
        btnMode.text = modeLabel()
        currentPage = start
        updatePageUi(start)
        webtoon.post { updateNextChapterPrompt() }
        root.postDelayed({ restoring = false }, 500)
    }

    private fun savePageBitmap(bitmap: android.graphics.Bitmap) {
        lifecycleScope.launch {
            try {
                GalleryPageSaver.save(this@ReaderActivity, bitmap)
                toast("Page saved to Pictures/ImperialReader")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't save page: ${e.message}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun confirmSavePage(bitmap: android.graphics.Bitmap) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Save page to gallery?")
            .setMessage("Save this page to Pictures/ImperialReader?")
            .setNegativeButton("Cancel") { _, _ -> bitmap.recycle() }
            .setOnCancelListener { bitmap.recycle() }
            .setPositiveButton("Save") { _, _ ->
                if (GalleryPageSaver.hasLegacyPermission(this)) savePageBitmap(bitmap)
                else {
                    pendingSaveBitmap?.recycle()
                    pendingSaveBitmap = bitmap
                    requestGalleryPermission.launch(GalleryPageSaver.WRITE_PERMISSION)
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ paging

    private fun onPageChanged(position: Int) {
        if (position < 0 || position >= pages.size) return
        currentPage = position
        updatePageUi(position)
        if (restoring) return

        val ch = chapters.getOrNull(index) ?: return
        val reachedEnd = position >= pages.lastIndex && (pagerMode || !webtoon.canScrollVertically(1))
        val read = ch.read || reachedEnd
        chapters = chapters.mapIndexed { i, c -> if (i == index) c.copy(lastPageRead = position, read = read) else c }
        lifecycleScope.launch { app.db.chapterDao().saveProgress(ch.id, position, read) }
    }

    private fun updateNextChapterPrompt() {
        val show = !pagerMode && pages.isNotEmpty() && index < chapters.lastIndex &&
            !webtoon.canScrollVertically(1) && errorView.visibility != View.VISIBLE
        if (show) {
            nextChapterPrompt.visibility = View.VISIBLE
            nextChapterStar.start()
        } else {
            hideNextChapterPrompt()
        }
    }

    private fun hideNextChapterPrompt() {
        nextChapterPrompt.visibility = View.GONE
        nextChapterStar.stop()
    }

    private fun updatePageUi(position: Int) {
        txtPage.text = (position + 1).toString()
        if (seek.progress != position) seek.progress = position
    }

    private fun goToPage(page: Int, smooth: Boolean) {
        if (page < 0 || page >= pages.size) return
        if (pagerMode) pager.setCurrentItem(page, smooth)
        else (webtoon.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(page, 0)
    }

    private fun nextPage() {
        if (pagerMode) {
            if (pager.currentItem >= pages.lastIndex) openAdjacent(1) else pager.setCurrentItem(pager.currentItem + 1, true)
        } else webtoon.smoothScrollBy(0, (webtoon.height * 0.85f).toInt())
    }

    private fun prevPage() {
        if (pagerMode) {
            if (pager.currentItem <= 0) openAdjacent(-1) else pager.setCurrentItem(pager.currentItem - 1, true)
        } else webtoon.smoothScrollBy(0, -(webtoon.height * 0.85f).toInt())
    }

    private fun openAdjacent(delta: Int) {
        val target = index + delta
        if (target !in chapters.indices) {
            toast(if (delta > 0) "No next chapter" else "No previous chapter")
            return
        }
        chapterTransitionActive = true
        nextChapterMessage.text = "Opening chapter..."
        nextChapterPrompt.visibility = View.VISIBLE
        nextChapterStar.start()
        nextChapterPrompt.postDelayed({
            chapterTransitionActive = false
            nextChapterMessage.text = "Pull up to read next chapter"
            hideNextChapterPrompt()
        }, 500)
        if (!pagerMode) webtoon.stopScroll()
        loadChapter(chapters[target].id, if (delta < 0) Int.MAX_VALUE else 0)
    }

    // ------------------------------------------------------------------ input

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isInside(view: View, e: MotionEvent): Boolean {
        val r = Rect()
        if (!view.getGlobalVisibleRect(r)) return false
        return r.contains(e.rawX.toInt(), e.rawY.toInt())
    }

    private fun handleTap(e: MotionEvent) {
        if (overlaysVisible && (isInside(topBar, e) || isInside(bottomBar, e))) return
        if (!app.prefs.tapNavigation || pages.isEmpty()) {
            toggleOverlays()
            return
        }
        val w = window.decorView.width.toFloat()
        val h = window.decorView.height.toFloat()
        if (pagerMode) {
            val x = e.rawX / w
            when {
                x < 0.3f -> if (rtl) nextPage() else prevPage()
                x > 0.7f -> if (rtl) prevPage() else nextPage()
                else -> toggleOverlays()
            }
        } else {
            val y = e.rawY / h
            when {
                y < 0.25f -> prevPage()
                y > 0.75f -> nextPage()
                else -> toggleOverlays()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (app.prefs.volumeKeys && pages.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { if (rtl && pagerMode) nextPage() else prevPage(); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { if (rtl && pagerMode) prevPage() else nextPage(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ chrome

    private fun toggleOverlays() = setOverlays(!overlaysVisible)

    private fun setOverlays(visible: Boolean) {
        overlaysVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
        if (app.prefs.fullscreen) applySystemBars(visible)
    }

    private fun applySystemBars(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun modeLabel() = when (mode) {
        AppPrefs.READER_LTR -> "Left to right"
        AppPrefs.READER_VERTICAL -> "Vertical (webtoon)"
        else -> "Right to left"
    }

    private fun showModeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reading mode")
            .setSingleChoiceItems(arrayOf("Left to right", "Right to left", "Vertical (webtoon)"), mode) { dialog, which ->
                mode = which
                manga?.let { app.prefs.setReaderModeFor(it.id, which) }
                dialog.dismiss()
                if (pages.isNotEmpty()) applyPages(currentPage)
            }
            .show()
    }
}
