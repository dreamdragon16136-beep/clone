package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSource
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.ui.books.BookActions
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.common.StarSpriteView
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Popular + searchable grid for one EPUB / web-novel catalog. Tap a book to download it into the library. */
class BookBrowseActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SOURCE = "source_id"
        fun intent(ctx: Context, sourceId: String): Intent =
            Intent(ctx, BookBrowseActivity::class.java).putExtra(EXTRA_SOURCE, sourceId)
    }

    private lateinit var source: BookSource
    private lateinit var adapter: MangaCardAdapter
    private lateinit var sprite: StarSpriteView
    private lateinit var emptyText: TextView

    private var query = ""
    private var page = 1
    private var hasMore = true
    private var loading = false
    private var cloudflareUrl: String? = null
    private var retryAfterWebView = false
    private var job: Job? = null
    private var searchDebounceJob: Job? = null
    private val items = mutableListOf<BookItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_browse)

        val found = BookSources.get(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
        if (found == null) {
            toast("Catalog not found")
            finish()
            return
        }
        source = found

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = source.name
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.book_menu)
        toolbar.menu.findItem(R.id.action_webview)?.isVisible = source.webUrl != null
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId != R.id.action_webview) return@setOnMenuItemClickListener false
            val url = source.webUrl ?: return@setOnMenuItemClickListener false
            startActivity(WebViewActivity.intent(this, url))
            true
        }
        val searchView = toolbar.menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = "Search ${source.name}"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                searchDebounceJob?.cancel()
                query = q.orEmpty().trim()
                searchView.clearFocus()
                reset()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(350)
                    query = newText.orEmpty().trim()
                    reset()
                }
                return true
            }
        })

        sprite = findViewById(R.id.sprite)
        emptyText = findViewById(R.id.emptyText)
        emptyText.setOnClickListener {
            val url = cloudflareUrl ?: return@setOnClickListener
            retryAfterWebView = true
            startActivity(WebViewActivity.intent(this, url))
        }
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = MangaCardAdapter(onClick = { card -> BookActions.show(this, card.tag as BookItem) })
        val lm = GridLayoutManager(this, app.prefs.libraryColumns)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !loading && hasMore && lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) loadPage()
            }
        })
        reset()
    }

    override fun onResume() {
        super.onResume()
        if (retryAfterWebView) {
            retryAfterWebView = false
            reset()
        }
    }

    private fun reset() {
        job?.cancel()
        loading = false
        items.clear()
        adapter.submitList(emptyList())
        page = 1
        hasMore = true
        emptyText.visibility = View.GONE
        loadPage()
    }

    private fun loadPage() {
        if (loading) return
        loading = true
        if (page == 1) sprite.start()
        job = lifecycleScope.launch {
            try {
                val titleResults = if (query.isBlank()) source.popular(page) else source.search(query, page)
                var genreError: String? = null
                val genreResults = if (query.length >= 2) {
                    try {
                        withTimeout(12_000L) { source.searchByGenre(query, page) }
                    } catch (e: TimeoutCancellationException) {
                        genreError = "Genre search timed out"
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        genreError = e.message ?: "Genre search failed"
                        emptyList()
                    }
                } else emptyList()
                cloudflareUrl = null
                (titleResults + genreResults).forEach { b -> if (items.none { it.key == b.key }) items.add(b) }
                hasMore = titleResults.isNotEmpty() || genreResults.isNotEmpty()
                page++
                adapter.submitList(items.map {
                    CardItem(key = it.key, title = it.title, cover = it.coverUrl, badge1 = if (source.isWebNovel) "NOVEL" else "EPUB", tag = it)
                })
                if (items.isEmpty()) show(genreError?.let { "Genre search failed: $it" } ?: "Nothing found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (items.isEmpty() && e is CloudflareException) {
                    cloudflareUrl = e.url
                    show("Cloudflare protection detected. Tap here to open the site and solve it, then retry.")
                } else if (items.isEmpty()) {
                    show("Couldn't load this catalog: ${e.message}\n\nSome sites need a browser (Cloudflare) or have moved.")
                } else {
                    hasMore = false
                }
            } finally {
                loading = false
                sprite.stop()
            }
        }
    }

    private fun show(text: String) {
        emptyText.text = text
        emptyText.visibility = View.VISIBLE
    }
}
