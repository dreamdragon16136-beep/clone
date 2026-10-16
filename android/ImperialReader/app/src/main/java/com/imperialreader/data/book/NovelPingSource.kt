package com.imperialreader.data.book

import android.net.Uri
import com.imperialreader.App
import com.imperialreader.data.network.CloudflareException
import com.imperialreader.data.network.await
import com.imperialreader.data.source.lazyImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/** NovelPing's catalog and chapter archive use site-specific AJAX endpoints and markup. */
class NovelPingSource(
    catalogId: String = ID,
    displayName: String = "NovelPing",
    siteUrl: String = BASE
) : BookSource {
    override val id = catalogId
    override val name = displayName
    override val webUrl = siteUrl
    override val description = "Web novel • NovelPing catalog"
    override val isWebNovel = true

    override suspend fun popular(page: Int): List<BookItem> {
        if (page > 1) return emptyList()
        val doc = fetchDoc("$BASE/ajax/hot-novels")
        val items = doc.select("div.item > a[href]").mapNotNull { anchor ->
            val url = bookUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            val title = anchor.attr("title").ifBlank {
                anchor.selectFirst(".title")?.text().orEmpty()
            }.trim()
            if (title.isBlank()) return@mapNotNull null
            val image = anchor.selectFirst("img")
            BookItem(
                sourceId = id,
                sourceName = name,
                id = url,
                title = title,
                author = null,
                coverUrl = image?.let(::lazyImage),
                epubUrl = null
            )
        }.distinctBy { it.id }
        if (items.isEmpty()) throw IOException("NovelPing returned no popular novels")
        return items
    }

    override suspend fun search(query: String, page: Int): List<BookItem> {
        if (query.isBlank()) return emptyList()
        val url = "$BASE/ajax/search-results?keyword=${encode(query)}&page=$page"
        val results = fetchDoc(url).selectFirst("[data-fragment=results]")
            ?: throw IOException("NovelPing returned an unrecognized search response")
        val links = results.select("h3.novel-title > a[href]")
        if (links.isEmpty()) {
            if (results.selectFirst(".advanced-search__empty") != null) return emptyList()
            throw IOException("NovelPing search results could not be read")
        }
        return links.mapNotNull { anchor ->
            val bookUrl = bookUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            val title = anchor.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val row = anchor.closest("div.row")
            val image = row?.selectFirst("img.cover")
            val author = row?.selectFirst(".author")?.text()?.trim()?.ifBlank { null }
            BookItem(
                sourceId = id,
                sourceName = name,
                id = bookUrl,
                title = title,
                author = author,
                coverUrl = image?.let(::lazyImage),
                epubUrl = null
            )
        }.distinctBy { it.id }.also {
            if (it.isEmpty()) throw IOException("NovelPing returned unreadable search results")
        }
    }

    override suspend fun details(item: BookItem): BookItem {
        val url = bookUrl(item.id) ?: throw IOException("Invalid NovelPing title address")
        val doc = fetchDoc(url)
        val detail = doc.selectFirst(".book-info, .novel-info, .book-meta, .book-detail") ?: doc
        val genres = detail.select(".genres a, .genre a, .tags a, .tag a, a[href*=genre], a[href*=tag]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val author = detail.selectFirst(".author a, .book-author a, [itemprop=author]")?.text()?.trim()
            ?.ifBlank { null }
        return item.copy(
            author = author ?: item.author,
            genre = genres.joinToString(", ").ifBlank { null } ?: item.genre,
            description = doc.selectFirst(".description, #description, .book-description")
                ?.text()?.trim()?.ifBlank { null } ?: item.description
        )
    }

    override suspend fun chapters(item: BookItem): List<NovelChapter> {
        val novelUrl = bookUrl(item.id) ?: throw IOException("Invalid NovelPing title address")
        val slug = Uri.parse(novelUrl).lastPathSegment
            ?: throw IOException("Invalid NovelPing title address")
        val doc = fetchDoc("$BASE/ajax/chapter-archive?novelId=${encode(slug)}")
        val links = doc.select("li[data-chapter-item] a[href]")
        if (links.isEmpty()) throw IOException("NovelPing returned no chapters for this title")

        val chapters = links.mapNotNull { anchor ->
            val chapterUrl = chapterUrl(anchor.absUrl("href"), slug) ?: return@mapNotNull null
            val title = anchor.selectFirst(".chapter-title, .nchr-text")?.text()?.trim()
                ?.ifBlank { null }
                ?: anchor.attr("title").trim().ifBlank { anchor.text().trim() }
            if (title.isBlank()) null else NovelChapter(title, chapterUrl)
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("NovelPing returned an invalid chapter archive")
        return chapters
    }

    override suspend fun chapterHtml(chapter: NovelChapter): String {
        val url = chapterUrl(chapter.url) ?: throw IOException("Invalid NovelPing chapter address")
        val content = fetchDoc(url).selectFirst("#chr-content")
            ?: throw IOException("NovelPing chapter text was not found")
        content.select("script, style, noscript, iframe, nav, header, footer, aside, form").remove()
        if (content.select("p").none { it.text().isNotBlank() }) {
            throw IOException("NovelPing returned an empty chapter")
        }
        return content.html()
    }

    private suspend fun fetchDoc(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "$BASE/")
            .build()
        App.instance.network.client.await(request).use { response ->
            val body = response.body?.string().orEmpty()
            val cloudflare = response.header("cf-mitigated") == "challenge" ||
                (response.code in setOf(403, 503) &&
                    (response.header("Server")?.contains("cloudflare", true) == true ||
                        response.header("cf-ray") != null))
            if (cloudflare || body.contains("<title>Just a moment...", ignoreCase = true)) {
                throw CloudflareException(url)
            }
            if (!response.isSuccessful) throw IOException("NovelPing returned HTTP ${response.code}")
            if (body.isBlank()) throw IOException("NovelPing returned an empty response")
            Jsoup.parse(body, url)
        }
    }

    private fun bookUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (!uri.host.orEmpty().removePrefix("www.").equals("novelping.com", ignoreCase = true)) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/book/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$BASE$path"
    }

    private fun chapterUrl(raw: String, expectedSlug: String? = null): String? {
        val uri = Uri.parse(raw)
        if (!uri.host.orEmpty().removePrefix("www.").equals("novelping.com", ignoreCase = true)) return null
        val path = uri.path.orEmpty().trimEnd('/')
        val match = Regex("""^/book/([^/]+)/chapter-[^/]+$""", RegexOption.IGNORE_CASE).matchEntire(path)
            ?: return null
        if (expectedSlug != null && !match.groupValues[1].equals(expectedSlug, ignoreCase = true)) return null
        return "$BASE$path"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val ID = "novel_novelping_com"
        const val BASE = "https://novelping.com"
    }
}
