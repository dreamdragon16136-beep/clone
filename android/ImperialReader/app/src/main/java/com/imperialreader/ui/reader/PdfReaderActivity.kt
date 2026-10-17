package com.imperialreader.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.min

class PdfReaderActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPTER = "chapter_id"
        private const val EXTRA_URI = "uri"

        fun intent(ctx: Context, chapterId: Long, uri: String): Intent =
            Intent(ctx, PdfReaderActivity::class.java)
                .putExtra(EXTRA_CHAPTER, chapterId)
                .putExtra(EXTRA_URI, uri)
    }

    private lateinit var pageImage: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var pageSeek: SeekBar
    private var renderer: PdfRenderer? = null
    private var chapterId = -1L
    private var pageIndex = 0
    private var renderJob: Job? = null
    private var updatingSeek = false
    private var darkMode = false
    private val renderMutex = Mutex()
    private var pendingSaveBitmap: Bitmap? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)
        pageImage = findViewById(R.id.pdfPage)
        pageImage.setOnLongClickListener {
            if (pageImage.drawable == null) {
                toast("Wait for the page to finish rendering before saving it")
            } else {
                GalleryPageSaver.captureDisplayedPage(this, pageImage) { bitmap ->
                    if (bitmap == null) toast("Could not capture the displayed page")
                    else confirmSavePage(bitmap)
                }
            }
            true
        }
        pageLabel = findViewById(R.id.pageLabel)
        pageSeek = findViewById(R.id.pageSeek)
        chapterId = intent.getLongExtra(EXTRA_CHAPTER, -1L)
        darkMode = app.prefs.sp.getBoolean("pdf_dark_mode", false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = "PDF reader"
        toolbar.inflateMenu(R.menu.pdf_reader_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.action_pdf_theme) return@setOnMenuItemClickListener false
            darkMode = !darkMode
            app.prefs.sp.edit().putBoolean("pdf_dark_mode", darkMode).apply()
            applyReaderTheme(toolbar)
            renderer?.let { showPage(pageIndex) }
            true
        }

        applyReaderTheme(toolbar)
        findViewById<View>(R.id.btnPrevious).setOnClickListener { showPage(pageIndex - 1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { showPage(pageIndex + 1) }
        pageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updatePageLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!updatingSeek) showPage(seekBar.progress)
            }
        })

        lifecycleScope.launch {
            try {
                val chapter = withContext(Dispatchers.IO) {
                    app.db.chapterDao().getById(chapterId) ?: throw IOException("Chapter not found")
                }
                findViewById<MaterialToolbar>(R.id.toolbar).title = chapter.name
                val uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
                val opened = withContext(Dispatchers.IO) {
                    val descriptor = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("Cannot open PDF")
                    try {
                        PdfRenderer(descriptor)
                    } catch (e: Exception) {
                        descriptor.close()
                        throw e
                    }
                }
                if (opened.pageCount == 0) {
                    opened.close()
                    throw IOException("PDF contains no pages")
                }
                renderer = opened
                applyReaderTheme(toolbar)
                pageSeek.max = opened.pageCount - 1
                val startPage = if (chapter.read) 0 else chapter.lastPageRead
                showPage(startPage.coerceIn(0, opened.pageCount - 1))
                app.repo.recordHistory(chapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't open PDF: ${e.message}")
                finish()
            }
        }
    }

    private fun savePageBitmap(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                GalleryPageSaver.save(this@PdfReaderActivity, bitmap)
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

    private fun confirmSavePage(bitmap: Bitmap) {
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

    private fun showPage(target: Int) {
        val pdf = renderer ?: return
        if (target !in 0 until pdf.pageCount) return
        pageIndex = target
        updatingSeek = true
        pageSeek.progress = target
        updatingSeek = false
        updatePageLabel(target)
        val targetWidth = (pageImage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).coerceAtMost(1800)
        val targetHeight = (pageImage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).coerceAtMost(2400)
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    renderMutex.withLock { render(pdf, target, targetWidth, targetHeight) }
                }
                pageImage.setImageBitmap(if (darkMode) invert(bitmap) else bitmap)
                val chapter = app.db.chapterDao().getById(chapterId)
                    ?: throw IOException("Chapter no longer exists")
                app.db.chapterDao().saveProgress(chapterId, target, chapter.read || target == pdf.pageCount - 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't render PDF page: ${e.message}")
            }
        }
    }

    private fun updatePageLabel(page: Int) {
        val pageCount = renderer?.pageCount ?: 0
        pageLabel.text = "Page ${page + 1} / $pageCount"
    }

    private fun applyReaderTheme(toolbar: MaterialToolbar) {
        val background = if (darkMode) Color.BLACK else Color.WHITE
        val foreground = if (darkMode) Color.WHITE else Color.BLACK
        findViewById<View>(R.id.pdfReaderRoot).setBackgroundColor(background)
        toolbar.setBackgroundColor(background)
        toolbar.setTitleTextColor(foreground)
        toolbar.menu.findItem(R.id.action_pdf_theme)?.title = if (darkMode) "Light" else "Dark"
        pageImage.setBackgroundColor(background)
        pageLabel.setTextColor(foreground)
        findViewById<View>(R.id.pdfControls).setBackgroundColor(if (darkMode) Color.rgb(24, 24, 24) else Color.rgb(240, 240, 240))
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPrevious).apply {
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(background))
            setTextColor(foreground)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext).apply {
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(background))
            setTextColor(foreground)
        }
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (darkMode) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun invert(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
        Canvas(result).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return result
    }

    private fun render(pdf: PdfRenderer, index: Int, maxWidth: Int, maxHeight: Int): Bitmap {
        val page = pdf.openPage(index)
        try {
            val scale = min(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height).coerceAtMost(1.5f)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        val opened = renderer
        renderer = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            renderMutex.withLock { opened?.close() }
        }
        super.onDestroy()
    }
}
