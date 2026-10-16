package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.imperialreader.R
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.source.MangasPage
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.Source
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.ui.webview.WebViewActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SourceBrowseActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SOURCE = "source_id"
        private const val EXTRA_LATEST = "latest"

        fun intent(ctx: Context, sourceId: String, latest: Boolean = false): Intent =
            Intent(ctx, SourceBrowseActivity::class.java)
                .putExtra(EXTRA_SOURCE, sourceId)
                .putExtra(EXTRA_LATEST, latest)
    }

    private lateinit var source: Source
    private lateinit var adapter: MangaCardAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    private var mode = 0 // 0 popular, 1 latest, 2 search
    private var genreOnly = false
    private var query = ""
    private var page = 1
    private var hasNext = true
    private var loading = false
    private var loadJob: Job? = null
    private var searchDebounceJob: Job? = null
    private val items = mutableListOf<SManga>()
    private var favoriteUrls: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_browse)

        val found = app.sources.get(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
        if (found == null) {
            toast("Source not found")
            finish()
            return
        }
        source = found

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = source.name
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.source_menu)

        val chips = findViewById<ChipGroup>(R.id.chips)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        recycler = findViewById(R.id.recycler)

        adapter = MangaCardAdapter(onClick = { item -> open(item) })
        val lm = GridLayoutManager(this, app.prefs.libraryColumns)
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !loading && hasNext && lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadPage()
                }
            }
        })

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "Search ${source.name}"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                searchDebounceJob?.cancel()
                applySearchQuery(q.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(350)
                    applySearchQuery(newText.orEmpty())
                }
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (mode == 2) {
                    mode = if (chips.checkedChipId == R.id.chipLatest) 1 else 0
                    reset()
                }
                return true
            }
        })
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_webview -> startActivity(WebViewActivity.intent(this, source.baseUrl))
                R.id.action_genre_search -> {
                    genreOnly = !genreOnly
                    it.isChecked = genreOnly
                    reset()
                }
            }
            true
        }

        mode = if (intent.getBooleanExtra(EXTRA_LATEST, false)) 1 else 0
        if (mode == 1) chips.check(R.id.chipLatest)
        chips.setOnCheckedStateChangeListener { _, ids ->
            mode = if (ids.firstOrNull() == R.id.chipLatest) 1 else 0
            reset()
        }

        findViewById<Button>(R.id.btnRetry).setOnClickListener { reset() }
        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            startActivity(WebViewActivity.intent(this, source.baseUrl))
        }

        reset()
    }

    override fun onStart() {
        super.onStart()
        if (::source.isInitialized) {
            lifecycleScope.launch {
                favoriteUrls = app.db.mangaDao().favoriteUrls(source.id).toSet()
                render()
            }
        }
    }

    private fun reset() {
        loadJob?.cancel()
        loading = false
        items.clear()
        adapter.submitList(emptyList())
        page = 1
        hasNext = true
        errorView.visibility = View.GONE
        loadPage()
    }

    private fun applySearchQuery(text: String) {
        query = text.trim()
        mode = if (query.isBlank()) {
            if (findViewById<ChipGroup>(R.id.chips).checkedChipId == R.id.chipLatest) 1 else 0
        } else 2
        reset()
    }

    private fun loadPage() {
        if (loading) return
        loading = true
        if (page == 1) progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                favoriteUrls = app.db.mangaDao().favoriteUrls(source.id).toSet()
                val titleResults = when {
                    mode == 0 -> source.getPopular(page)
                    mode == 1 -> source.getLatest(page)
                    mode == 2 && !genreOnly -> source.search(query, page)
                    else -> MangasPage(emptyList(), false)
                }
                var genreError: String? = null
                val genreResults = if (mode == 2 && query.isNotBlank() && (genreOnly || query.length >= 2)) {
                    try {
                        withTimeout(12_000L) { source.searchByGenre(query, page) }
                    } catch (e: TimeoutCancellationException) {
                        genreError = "Genre search timed out"
                        MangasPage(emptyList(), false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        genreError = e.message ?: "Genre search failed"
                        MangasPage(emptyList(), false)
                    }
                } else MangasPage(emptyList(), false)
                (titleResults.mangas + genreResults.mangas).forEach { m ->
                    if (items.none { it.url == m.url }) items.add(m)
                }
                hasNext = titleResults.hasNextPage || genreResults.hasNextPage
                page++
                render()
                if (items.isEmpty()) showError(genreError?.let { "Genre search failed: $it" } ?: "No results")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (items.isEmpty()) showError(e.message ?: "Failed to load", e is CloudflareException)
                else toast(e.message ?: "Failed to load more")
            } finally {
                loading = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String, cloudflare: Boolean = false) {
        errorText.text = message
        findViewById<View>(R.id.btnOpenWeb).visibility = if (cloudflare) View.VISIBLE else View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun render() {
        val headers = source.imageHeaders()
        adapter.submitList(items.map {
            CardItem(
                key = it.url,
                title = it.title,
                cover = it.thumbnailUrl,
                badge1 = if (it.url in favoriteUrls) "In library" else null,
                headers = headers,
                tag = it
            )
        })
    }

    private fun open(item: CardItem) {
        val manga = item.tag as SManga
        lifecycleScope.launch {
            val entity = app.repo.getOrCreate(source.id, manga)
            startActivity(MangaDetailsActivity.intent(this@SourceBrowseActivity, entity.id))
        }
    }
}
