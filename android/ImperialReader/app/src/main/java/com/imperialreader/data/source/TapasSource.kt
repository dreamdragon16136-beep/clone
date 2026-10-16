package com.imperialreader.data.source

import android.net.Uri
import com.imperialreader.util.parseChapterNumber
import org.jsoup.Jsoup
import java.io.IOException

class TapasSource : HttpSource() {
    override val id = "tapas_io"
    override val name = "Tapas Comics"
    override val lang = "en"
    override val baseUrl = "https://tapas.io"

    override suspend fun getPopular(page: Int): MangasPage =
        list("$baseUrl/landing-list/1${if (page > 1) "?page=$page" else ""}", page = page)

    override suspend fun getLatest(page: Int): MangasPage =
        getPopular(page)

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$baseUrl/search?q=${enc(query)}&t=COMICS&page=$page", page = page)

    private suspend fun list(url: String, page: Int = 1): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val cards = doc.select("a[href^=/series/], li[data-href^=/series/]").mapNotNull { card ->
            val rawUrl = if (card.hasAttr("href")) card.attr("href") else card.attr("data-href")
            val canonical = seriesUrl(rawUrl) ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = card.attr("title").trim().ifBlank {
                image?.attr("alt")?.trim().orEmpty().ifBlank { card.text().trim() }
            }
            if (title.isBlank()) return@mapNotNull null
            SManga(canonical, title, lazyImage(image))
        }.distinctBy { it.url }
        val results = cards
        if (results.isEmpty() && url.contains("search?")) {
            if (!doc.text().contains("No results", ignoreCase = true)) {
                throw IOException("Tapas search results could not be read")
            }
        }
        return MangasPage(results, results.isNotEmpty() && page < 20)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val url = seriesUrl(manga.url) ?: throw IOException("Invalid Tapas series address")
        val doc = Jsoup.parse(getString("$url/info"), "$url/info")
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removePrefix("Read ")?.substringBefore(" | Tapas")?.trim()?.ifBlank { null }
            ?: manga.title
        manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        manga.genre = doc.select("a[href*=/genre/], .genre a, .genres a, .tag-list a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            .joinToString(", ").ifBlank { null } ?: manga.genre
        manga.author = doc.select("[class*=author] a, a[href*=/creator/]")
            .map { it.text().trim() }.firstOrNull { it.isNotBlank() } ?: manga.author
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val url = seriesUrl(manga.url) ?: throw IOException("Invalid Tapas series address")
        val doc = Jsoup.parse(getString("$url/info"), "$url/info")
        val chapters = doc.select(".episode-list a[href*=/episode/]").mapNotNull { anchor ->
            val episode = episodeUrl(anchor.absUrl("href")) ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            if (title.isBlank()) null else SChapter(episode, title, chapterNumber = parseChapterNumber(title))
        }.distinctBy { it.url }
        if (chapters.isEmpty()) throw IOException("Tapas did not return any episodes for this series")
        return chapters
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val url = episodeUrl(chapter.url) ?: throw IOException("Invalid Tapas episode address")
        val doc = Jsoup.parse(getString(url), url)
        val pages = doc.select("article.viewer__body img.content__img").mapNotNull(::lazyImage)
            .filterNot { it.contains("placeholder", true) || it.contains("avatar", true) }
            .distinct()
        if (pages.isEmpty()) throw IOException("Tapas episode contains no comic pages")
        return pages
    }

    private fun seriesUrl(raw: String): String? {
        val uri = Uri.parse(if (raw.startsWith("http", true)) raw else "$baseUrl/${raw.removePrefix("/")}")
        if (uri.host.orEmpty().lowercase() !in setOf("tapas.io", "www.tapas.io", "m.tapas.io")) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/series/[^/]+$""", RegexOption.IGNORE_CASE))) return null
        return "$baseUrl$path"
    }

    private fun episodeUrl(raw: String): String? {
        val uri = Uri.parse(raw)
        if (uri.host.orEmpty().lowercase() !in setOf("tapas.io", "www.tapas.io", "m.tapas.io")) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/episode/\d+$""", RegexOption.IGNORE_CASE))) return null
        return "$baseUrl$path"
    }
}