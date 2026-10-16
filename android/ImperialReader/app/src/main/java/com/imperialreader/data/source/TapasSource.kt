package com.imperialreader.data.source

import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import com.imperialreader.App
import com.imperialreader.data.book.fetchText
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class TapasEpisode(val title: String, val url: String)

internal object TapasSupport {
    suspend fun chapters(baseUrl: String, info: Document): List<TapasEpisode> {
        val episodes = linkedMapOf<String, TapasEpisode>()
        info.select(".episode-list a[href*=/episode/]").forEach { anchor ->
            val episode = episode(anchor.absUrl("href"), anchor)
            if (episode != null) episodes.putIfAbsent(episode.url, episode)
        }

        val indicator = info.selectFirst(".js-episode-loading-indicator")
        var page = indicator?.attr("data-page")?.toIntOrNull() ?: 1
        val hasNext = indicator?.attr("data-has-next").equals("true", ignoreCase = true)
        if (!hasNext) {
            if (episodes.isEmpty()) throw IOException("Tapas returned no episodes for this series")
            return episodes.values.toList()
        }

        val seriesId = info.selectFirst(".episode-item[data-tiara-event-meta-series-id]")
            ?.attr("data-tiara-event-meta-series-id")
            ?.takeIf { it.matches(Regex("""\d+""")) }
            ?: throw IOException("Tapas episode list is paginated but has no series identifier")
        val sort = indicator?.attr("data-sort")?.takeIf { it in setOf("OLDEST", "NEWEST") }
            ?: "OLDEST"
        val since = indicator?.attr("data-since")?.takeIf { it.matches(Regex("""\d+""")) }
            ?: throw IOException("Tapas episode list is paginated but has no paging token")

        var hasMore = true
        var pagesFetched = 0
        while (hasMore) {
            if (++pagesFetched > MAX_EPISODE_PAGES) {
                throw IOException("Tapas episode list exceeds the safe limit of $MAX_EPISODE_PAGES pages")
            }
            val endpoint = "$baseUrl/series/$seriesId/episodes?page=$page&sort=$sort" +
                "&since=$since&large=true&last_access=0&init_load=0"
            val response = JSONObject(fetchText(endpoint))
            if (response.optInt("code") != 200) {
                throw IOException("Tapas could not load episode page $page")
            }
            val data = response.optJSONObject("data")
                ?: throw IOException("Tapas returned an invalid episode page")
            val entries = data.optJSONArray("episodes")
                ?: throw IOException("Tapas returned no episode list for page $page")
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val id = entry.opt("id")?.toString()?.takeIf { it.matches(Regex("""\d+""")) }
                    ?: continue
                val name = episodeTitle(
                    entry.opt("scene")?.toString().orEmpty(),
                    entry.optString("title")
                )
                episodes.putIfAbsent("$baseUrl/episode/$id", TapasEpisode(name, "$baseUrl/episode/$id"))
            }
            val pagination = data.optJSONObject("pagination")
                ?: throw IOException("Tapas returned no pagination metadata for page $page")
            hasMore = pagination.optBoolean("has_next")
            page++
        }
        if (episodes.isEmpty()) throw IOException("Tapas returned no episodes for this series")
        return episodes.values.toList()
    }

    fun episodeTitle(anchor: Element): String =
        episodeTitle(
            anchor.selectFirst(".scene")?.text().orEmpty(),
            anchor.selectFirst(".title__body, .title")?.text().orEmpty()
        ).ifBlank {
            anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        }

    suspend fun imageFile(dataUri: String): String? {
        val match = Regex("""^data:image/([^;,]+);base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(dataUri.trim()) ?: return null
        val bytes = try {
            Base64.decode(match.groupValues[2], Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IOException("Tapas returned an invalid embedded image", e)
        }
        if (!isReadableImage(bytes)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val extension = when (match.groupValues[1].lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            else -> "img"
        }
        return withContext(Dispatchers.IO) {
            val dir = File(App.instance.cacheDir, "tapas-pages").apply { mkdirs() }
            val file = File(dir, "$digest.$extension")
            if (!file.exists()) file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        }
    }

    fun hasReadableImage(dataUri: String): Boolean {
        val match = Regex("""^data:image/[^;,]+;base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(dataUri.trim()) ?: return false
        val bytes = try {
            Base64.decode(match.groupValues[1], Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IOException("Tapas returned an invalid embedded image", e)
        }
        return isReadableImage(bytes)
    }

    private fun isReadableImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth >= 32 && bounds.outHeight >= 32 &&
            bounds.outWidth.toLong() * bounds.outHeight >= 4096
    }

    private fun episode(rawUrl: String, anchor: Element): TapasEpisode? {
        val url = episodeUrl(rawUrl) ?: return null
        val label = episodeTitle(anchor)
        return label.takeIf { it.isNotBlank() }?.let { TapasEpisode(it, url) }
    }

    private fun episodeTitle(scene: String, title: String): String {
        val cleanScene = scene.trim().takeIf { it.isNotBlank() }
        val cleanTitle = title.trim().takeIf { it.isNotBlank() }
        return when {
            cleanScene == null -> cleanTitle.orEmpty()
            cleanTitle == null -> cleanScene
            cleanScene.equals(cleanTitle, ignoreCase = true) -> cleanScene
            else -> "$cleanScene — $cleanTitle"
        }
    }

    fun episodeUrl(raw: String): String? {
        val uri = Uri.parse(if (raw.startsWith("http", true)) raw else "https://tapas.io/${raw.removePrefix("/")}")
        if (uri.host.orEmpty().lowercase() !in setOf("tapas.io", "www.tapas.io", "m.tapas.io")) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.matches(Regex("""^/episode/\d+$""", RegexOption.IGNORE_CASE))) return null
        return "https://tapas.io$path"
    }

    private const val MAX_EPISODE_PAGES = 300
}

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
                card.selectFirst("[title]")?.attr("title")?.trim().orEmpty()
            }.ifBlank {
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
        manga.genre = doc.select(
            "a[href*=/genre/], .genre a, .genres a, .tag-list a, .detail-row__body--genre a"
        )
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            .joinToString(", ").ifBlank { null } ?: manga.genre
        manga.author = doc.select("[class*=author] a, a[href*=/creator/]")
            .map { it.text().trim() }.firstOrNull { it.isNotBlank() } ?: manga.author
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val url = seriesUrl(manga.url) ?: throw IOException("Invalid Tapas series address")
        val doc = Jsoup.parse(getString("$url/info"), "$url/info")
        return TapasSupport.chapters(baseUrl, doc).map { episode ->
            SChapter(episode.url, episode.title, chapterNumber = parseChapterNumber(episode.title))
        }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val url = TapasSupport.episodeUrl(chapter.url) ?: throw IOException("Invalid Tapas episode address")
        val doc = Jsoup.parse(getString(url), url)
        val content = doc.selectFirst("article.viewer__body")
            ?: throw IOException("Tapas comic pages were not found")
        val pages = mutableListOf<String>()
        for (image in content.select("img")) {
            val src = image.attr("src")
            val lazySrc = lazyImage(image)
            val page = when {
                lazySrc?.startsWith("data:image/", ignoreCase = true) == true ->
                    TapasSupport.imageFile(lazySrc)
                lazySrc != null -> lazySrc
                src.startsWith("data:image/", ignoreCase = true) -> TapasSupport.imageFile(src)
                else -> null
            }
            if (page != null &&
                !page.contains("placeholder", true) &&
                !page.contains("avatar", true) &&
                page !in pages
            ) {
                pages += page
            }
        }
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
}