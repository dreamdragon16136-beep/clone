package com.imperialreader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.TextView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/** Minimal EPUB reader: unzips to cache, follows the OPF spine and shows each section in a WebView. */
class EpubReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHAPTER = "chapter_id"
        private const val EXTRA_URI = "uri"
        fun intent(ctx: Context, chapterId: Long, uri: String): Intent =
            Intent(ctx, EpubReaderActivity::class.java)
                .putExtra(EXTRA_CHAPTER, chapterId)
                .putExtra(EXTRA_URI, uri)
    }

    private class Book(val title: String, val spine: List<File>, val titles: List<String>)

    private lateinit var web: WebView
    private lateinit var txtSection: TextView
    private lateinit var sectionProgress: SeekBar
    private var book: Book? = null
    private var index = 0
    private var zoom = 100
    private var chapterId = -1L
    private var manga: MangaEntity? = null
    private var epubChapters: List<ChapterEntity> = emptyList()
    private var epubChapterIndex = -1
    private var switchingChapter = false
    private var transientEpubFile: File? = null
    private var readerTheme = 0
    private var horizontalReading = false
    private var pendingEndPosition = false
    private var pendingResumePage = 0
    private var seekingProgress = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var resumeSpeaking = false
    private lateinit var gesture: GestureDetector
    private lateinit var btnTts: com.google.android.material.button.MaterialButton
    private lateinit var btnReadingMode: com.google.android.material.button.MaterialButton

    /** background, text, link colours: dark, sepia, light */
    private val themes = listOf(
        Triple("#0A0A0A", "#E8E8E8", "#F0D77A"),
        Triple("#F4ECD8", "#3B2F20", "#8A5A00"),
        Triple("#FFFFFF", "#111111", "#1A5FB4")
    )

    private fun applyTheme() {
        val (bg, fg, link) = themes[readerTheme]
        val flow = if (horizontalReading) {
            "html{height:100%;direction:ltr;overflow-x:auto;overflow-y:hidden}" +
                "body{height:100vh!important;direction:ltr;unicode-bidi:plaintext;overflow-x:auto;overflow-y:hidden;" +
                "column-width:calc(100vw - 24px);column-gap:24px;column-fill:auto}"
        } else {
            "html{height:auto;overflow:auto}body{height:auto!important;overflow:auto;column-width:auto;column-gap:normal}"
        }
        web.setBackgroundColor(Color.parseColor(bg))
        web.evaluateJavascript(
            "(function(){var s=document.getElementById('ir-style');if(!s){s=document.createElement('style');" +
                "s.id='ir-style';document.head.appendChild(s);}" +
                "s.innerHTML='html,body{background:$bg !important;color:$fg !important;padding:0 12px;line-height:1.65} " +
                "a{color:$link !important} img{max-width:100% !important;height:auto} $flow';})()", null
        )
    }

    // ---------------------------------------------------------------- read aloud (text-to-speech)

    private fun startSpeaking() {
        if (!ttsReady) {
            toast("Text-to-speech isn't ready on this device")
            return
        }
        speaking = true
        btnTts.text = "Stop"
        web.evaluateJavascript("(function(){return document.body.innerText;})()") { raw ->
            val text = (JSONTokener(raw).nextValue() as? String).orEmpty().trim()
            if (text.isBlank()) {
                stopSpeaking()
                return@evaluateJavascript
            }
            val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
            val chunks = ArrayList<String>()
            val current = StringBuilder()
            for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (current.length + sentence.length + 1 > max && current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current.clear()
                }
                current.append(sentence).append(' ')
            }
            if (current.isNotBlank()) chunks.add(current.toString())

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != "last") return
                    runOnUiThread {
                        if (!speaking) return@runOnUiThread
                        val b = book
                        if (b != null && index < b.spine.lastIndex) {
                            resumeSpeaking = true // keep going: next section starts when it has loaded
                            show(index + 1)
                        } else if (
                            epubChapterIndex in epubChapters.indices &&
                            epubChapterIndex < epubChapters.lastIndex
                        ) {
                            resumeSpeaking = true
                            openAdjacentChapter(epubChapterIndex + 1, 0)
                        } else {
                            stopSpeaking()
                        }
                    }
                }
            })
            chunks.forEachIndexed { i, chunk ->
                val id = if (i == chunks.lastIndex) "last" else "c$i"
                tts?.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun stopSpeaking() {
        speaking = false
        tts?.stop()
        if (::btnTts.isInitialized) btnTts.text = "Listen"
    }

    override fun onDestroy() {
        transientEpubFile?.let { file ->
            File(cacheDir, "epub/${Uri.fromFile(file).toString().hashCode()}").deleteRecursively()
            file.delete()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub_reader)
        chapterId = intent.getLongExtra(EXTRA_CHAPTER, -1L)
        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        rememberTransientEpub(uri)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        web = findViewById(R.id.web)
        txtSection = findViewById(R.id.txtSection)
        sectionProgress = findViewById(R.id.sectionProgress)
        sectionProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seekingProgress = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekingProgress = false
                if (book?.spine?.size == 1) {
                    scrollToPage(seekBar.progress)
                } else {
                    show(seekBar.progress)
                }
            }
        })

        readerTheme = app.prefs.sp.getInt("epub_theme", 0)
        horizontalReading = app.prefs.sp.getBoolean("epub_horizontal_reading", false)
        btnReadingMode = findViewById(R.id.btnReadingMode)
        updateReadingModeButton()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        btnTts = findViewById(R.id.btnTts)
        findViewById<View>(R.id.btnTheme).setOnClickListener {
            readerTheme = (readerTheme + 1) % themes.size
            app.prefs.sp.edit().putInt("epub_theme", readerTheme).apply()
            applyTheme()
        }
        btnReadingMode.setOnClickListener {
            horizontalReading = !horizontalReading
            app.prefs.sp.edit().putBoolean("epub_horizontal_reading", horizontalReading).apply()
            updateReadingModeButton()
            applyTheme()
            web.evaluateJavascript("window.scrollTo(0,0)", null)
        }
        btnTts.setOnClickListener { if (speaking) stopSpeaking() else startSpeaking() }
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.textZoom = zoom
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onProgress(page: Int, total: Int) {
                runOnUiThread {
                    val currentBook = book ?: return@runOnUiThread
                    if (isFinishing || isDestroyed || total <= 0) return@runOnUiThread
                    if (currentBook.spine.size == 1) {
                        sectionProgress.max = (total - 1).coerceAtLeast(0)
                        if (!seekingProgress) sectionProgress.progress = page.coerceIn(0, sectionProgress.max)
                    } else if (!seekingProgress) {
                        sectionProgress.progress = index
                    }
                    txtSection.text = progressDescription(page, total, currentBook.titles[index])
                    val storedPage = if (currentBook.spine.size == 1) page else index
                    val atEnd = index == currentBook.spine.lastIndex && page >= total - 1
                    lifecycleScope.launch {
                        val dao = app.db.chapterDao()
                        val current = dao.getById(chapterId) ?: return@launch
                        dao.saveProgress(chapterId, storedPage, current.read || atEnd)
                    }
                }
            }
        }, "ImperialReaderProgress")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                applyTheme()
                if (pendingEndPosition || pendingResumePage > 0) {
                    val resumeAtEnd = pendingEndPosition
                    val page = pendingResumePage
                    pendingEndPosition = false
                    pendingResumePage = 0
                    val scrollPosition = if (resumeAtEnd) "total-1" else page.toString()
                    val x = if (horizontalReading) "p*dim" else "0"
                    val y = if (horizontalReading) "0" else "p*dim"
                    view.evaluateJavascript(
                        "(function(){var r=document.documentElement,b=document.body;" +
                            "var dim=${if (horizontalReading) "window.innerWidth" else "window.innerHeight"};" +
                            "var total=Math.max(1,Math.ceil((${if (horizontalReading) "Math.max(r.scrollWidth,b.scrollWidth)" else "Math.max(r.scrollHeight,b.scrollHeight)"})/dim));" +
                            "var p=Math.min($scrollPosition,total-1);" +
                            "window.scrollTo($x,$y);})()"
                    ) { installProgressReporter(view) }
                } else {
                    installProgressReporter(view)
                }
                if (resumeSpeaking) {
                    resumeSpeaking = false
                    view.postDelayed({ startSpeaking() }, 500)
                }
            }
        }

        findViewById<View>(R.id.btnPrev).setOnClickListener { moveReadingPage(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { moveReadingPage(1) }
        findViewById<View>(R.id.btnSmaller).setOnClickListener { setZoom(zoom - 10) }
        findViewById<View>(R.id.btnLarger).setOnClickListener { setZoom(zoom + 10) }
        txtSection.setOnClickListener { showContents() }
        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || !isInsideWeb(e1)) return false
                val dx = e2.rawX - e1.rawX
                val dy = e2.rawY - e1.rawY
                if (abs(dx) < 48 * resources.displayMetrics.density || abs(dx) <= abs(dy)) return false
                handleSwipe(if (dx < 0) 1 else -1)
                return true
            }
        })

        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { openBook(uri) }
                book = loaded
                toolbar.title = loaded.title
                val chapter = app.db.chapterDao().getById(chapterId)
                manga = chapter?.let { app.db.mangaDao().getById(it.mangaId) }
                val currentManga = manga
                if (chapter != null && currentManga != null &&
                    (currentManga.source == LOCAL_SOURCE_ID || BookSources.get(currentManga.source)?.isWebNovel == true)
                ) {
                    epubChapters = withContext(Dispatchers.IO) {
                        val all = app.db.chapterDao().getAll(chapter.mangaId).sortedByDescending { it.sourceOrder }
                        if (currentManga.source == LOCAL_SOURCE_ID) {
                            all.filter { chapterFile(it)?.name.orEmpty().endsWith(".epub", ignoreCase = true) }
                        } else {
                            all
                        }
                    }
                    epubChapterIndex = epubChapters.indexOfFirst { it.id == chapter.id }
                }
                if (chapter != null && !chapter.read && loaded.spine.size == 1) {
                    pendingResumePage = chapter.lastPageRead
                }
                app.repo.recordHistory(chapterId)
                val startSection = if (loaded.spine.size == 1) 0
                else if (chapter != null && !chapter.read) chapter.lastPageRead.coerceIn(0, loaded.spine.lastIndex)
                else 0
                show(startSection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't open EPUB: ${e.message}")
                finish()
            }
        }
    }

    private fun setZoom(value: Int) {
        zoom = value.coerceIn(60, 220)
        web.settings.textZoom = zoom
    }

    private fun updateReadingModeButton() {
        btnReadingMode.text = if (horizontalReading) "Left to right" else "Scroll"
    }

    private fun scrollToPage(page: Int) {
        val x = if (horizontalReading) "$page*window.innerWidth" else "0"
        val y = if (horizontalReading) "0" else "$page*window.innerHeight"
        web.evaluateJavascript("window.scrollTo($x,$y)", null)
    }

    private fun installProgressReporter(view: WebView) {
        val extent = if (horizontalReading) "Math.max(root.scrollWidth,body.scrollWidth)" else "Math.max(root.scrollHeight,body.scrollHeight)"
        val offset = if (horizontalReading) "window.scrollX||root.scrollLeft||body.scrollLeft" else "window.scrollY||root.scrollTop||body.scrollTop"
        val dimension = if (horizontalReading) "window.innerWidth" else "window.innerHeight"
        view.evaluateJavascript(
            "(function(){if(window.__irProgressTimer)clearInterval(window.__irProgressTimer);" +
                "var root=document.documentElement,body=document.body,last=-1,totalLast=-1;" +
                "function report(){var dim=Math.max(1,$dimension);var total=Math.max(1,Math.ceil($extent/dim));" +
                "var page=Math.min(total-1,Math.max(0,Math.floor(($offset)/dim)));" +
                "if(page!==last||total!==totalLast){last=page;totalLast=total;ImperialReaderProgress.onProgress(page,total);}}" +
                "window.__irProgressTimer=setInterval(report,300);report();})()",
            null
        )
    }

    private fun isInsideWeb(event: MotionEvent): Boolean {
        val bounds = Rect()
        return web.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    private fun handleSwipe(direction: Int) {
        if (!horizontalReading) {
            if (epubChapterIndex in epubChapters.indices) {
                val target = epubChapterIndex + direction
                if (target in epubChapters.indices) {
                    openAdjacentChapter(target, if (direction > 0) 0 else Int.MAX_VALUE)
                }
            } else {
                moveSection(direction)
            }
            return
        }

        val edge = if (direction > 0) {
            "x + window.innerWidth >= Math.max(root.scrollWidth, body.scrollWidth) - 2"
        } else {
            "x <= 2"
        }
        web.evaluateJavascript(
            "(function(){var root=document.documentElement,body=document.body;var x=window.scrollX||root.scrollLeft||body.scrollLeft;return $edge;})()"
        ) { atEdge ->
            if (atEdge == "true") moveSection(direction)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::gesture.isInitialized) gesture.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun show(i: Int) {
        val b = book ?: return
        if (i !in b.spine.indices) return
        if (!resumeSpeaking) stopSpeaking()
        index = i
        sectionProgress.max = (b.spine.size - 1).coerceAtLeast(0)
        sectionProgress.progress = i
        web.loadUrl(Uri.fromFile(b.spine[i]).toString())
        txtSection.text = progressDescription(i, b.spine.size, b.titles[i])
    }

    private fun progressDescription(page: Int, total: Int, sectionTitle: String): String {
        val chapter = if (epubChapterIndex in epubChapters.indices) {
            "Chapter ${epubChapterIndex + 1}/${epubChapters.size} • "
        } else ""
        return "$chapter${page + 1}/$total • $sectionTitle"
    }

    private fun moveSection(direction: Int) {
        val b = book ?: return
        val targetSection = index + direction
        if (targetSection in b.spine.indices) {
            pendingEndPosition = horizontalReading && direction < 0
            show(targetSection)
            return
        }

        if (epubChapterIndex !in epubChapters.indices) return
        val targetChapter = epubChapterIndex + direction
        if (targetChapter !in epubChapters.indices) return
        val targetStart = if (direction > 0) 0 else Int.MAX_VALUE
        openAdjacentChapter(targetChapter, targetStart)
    }

    private fun moveReadingPage(direction: Int) {
        if (!horizontalReading) {
            moveSection(direction)
            return
        }
        val directionLiteral = direction.coerceIn(-1, 1)
        web.evaluateJavascript(
            "(function(){var r=document.documentElement,b=document.body;" +
                "var x=window.scrollX||r.scrollLeft||b.scrollLeft;" +
                "var max=Math.max(0,Math.max(r.scrollWidth,b.scrollWidth)-window.innerWidth);" +
                "if(($directionLiteral<0&&x>2)||($directionLiteral>0&&x<max-2)){" +
                "window.scrollTo(Math.max(0,Math.min(max,x+$directionLiteral*window.innerWidth)),window.scrollY);return true;}return false;})()"
        ) { moved ->
            if (moved != "true") moveSection(directionLiteral)
        }
    }

    private fun openAdjacentChapter(targetIndex: Int, startSection: Int) {
        if (switchingChapter || targetIndex !in epubChapters.indices) return
        switchingChapter = true
        lifecycleScope.launch {
            try {
                val target = epubChapters[targetIndex]
                val currentManga = manga ?: throw IOException("Manga not found")
                val loadedChapter = ChapterLoader.load(this@EpubReaderActivity, currentManga, target)
                val uri = (loadedChapter as? LoadedChapter.Epub)?.uri
                    ?: throw IOException("The next chapter is not an EPUB")
                val loaded = withContext(Dispatchers.IO) { openBook(uri) }
                transientEpubFile?.let { oldFile ->
                    File(cacheDir, "epub/${Uri.fromFile(oldFile).toString().hashCode()}").deleteRecursively()
                    oldFile.delete()
                }
                rememberTransientEpub(uri)
                chapterId = target.id
                epubChapterIndex = targetIndex
                book = loaded
                pendingEndPosition = startSection == Int.MAX_VALUE
                pendingResumePage = 0
                findViewById<MaterialToolbar>(R.id.toolbar).title = manga?.title ?: loaded.title
                app.repo.recordHistory(target.id)
                val section = if (startSection == Int.MAX_VALUE) loaded.spine.lastIndex else startSection
                show(section.coerceIn(0, loaded.spine.lastIndex))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resumeSpeaking = false
                stopSpeaking()
                toast("Couldn't open EPUB chapter: ${e.message}")
            } finally {
                switchingChapter = false
            }
        }
    }

    private fun chapterFile(chapter: ChapterEntity): DocumentFile? {
        val currentManga = manga ?: return null
        return app.storage.localChapterFile(currentManga, chapter)
    }

    private fun rememberTransientEpub(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        if (file.parentFile == File(cacheDir, "online-epub")) transientEpubFile = file
    }

    private fun showContents() {
        val b = book ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Contents")
            .setItems(b.titles.toTypedArray()) { _, which -> show(which) }
            .show()
    }

    // ------------------------------------------------------------------ EPUB parsing

    private fun openBook(uriString: String): Book {
        val root = File(cacheDir, "epub/${uriString.hashCode()}")
        val marker = File(root, ".done")
        val uri = Uri.parse(uriString)
        val sourceFile = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
        val sourceDoc = if (sourceFile == null) DocumentFile.fromSingleUri(this, uri) else null
        val signature = listOf(
            uriString,
            sourceFile?.lastModified() ?: sourceDoc?.lastModified() ?: 0L,
            sourceFile?.length() ?: sourceDoc?.length() ?: -1L
        ).joinToString(":")
        if (!marker.exists() || marker.readText() != signature) {
            root.deleteRecursively()
            root.mkdirs()
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            val rootPath = root.canonicalPath + File.separator
            input.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(root, entry.name)
                        if (out.canonicalPath.startsWith(rootPath)) {
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            marker.writeText(signature)
        }

        val container = parse(File(root, "META-INF/container.xml"))
        val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
            ?: throw IOException("Invalid EPUB (no rootfile)")
        val opfFile = File(root, URLDecoder.decode(rootfile.getAttribute("full-path"), "UTF-8"))
        val opfDir = opfFile.parentFile ?: root
        val opf = parse(opfFile)

        val title = opf.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim().orEmpty().ifBlank { "EPUB" }

        val manifest = mutableMapOf<String, Pair<String, String>>() // id -> (href, mediaType)
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            manifest[el.getAttribute("id")] = el.getAttribute("href") to el.getAttribute("media-type")
        }

        val spine = mutableListOf<File>()
        val refs = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until refs.length) {
            val (href, type) = manifest[(refs.item(i) as Element).getAttribute("idref")] ?: continue
            if (!type.contains("html") && !type.contains("xml")) continue
            val f = File(opfDir, URLDecoder.decode(href, "UTF-8"))
            if (f.exists()) spine.add(f)
        }
        if (spine.isEmpty()) throw IOException("This EPUB has no readable sections")

        // chapter titles from the NCX table of contents, when present
        val titleByPath = mutableMapOf<String, String>()
        val ncxHref = manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first
        if (ncxHref != null) {
            val ncxFile = File(opfDir, URLDecoder.decode(ncxHref, "UTF-8"))
            if (ncxFile.exists()) {
                val ncx = parse(ncxFile)
                val points = ncx.getElementsByTagNameNS("*", "navPoint")
                for (i in 0 until points.length) {
                    val p = points.item(i) as Element
                    val label = firstChild(p, "navLabel")?.textContent?.trim().orEmpty()
                    val src = (firstChild(p, "content") as? Element)?.getAttribute("src").orEmpty()
                    if (label.isNotBlank() && src.isNotBlank()) {
                        val f = File(ncxFile.parentFile, URLDecoder.decode(src.substringBefore('#'), "UTF-8"))
                        titleByPath.putIfAbsent(f.canonicalPath, label)
                    }
                }
            }
        }
        val titles = spine.mapIndexed { i, f -> titleByPath[f.canonicalPath] ?: "Section ${i + 1}" }
        return Book(title, spine, titles)
    }

    private fun firstChild(parent: Element, localName: String): Node? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName).substringAfter(':') == localName) return n
        }
        return null
    }

    private fun parse(file: File): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        // never fetch external DTDs (EPUB files reference remote ones)
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(file)
    }
}
