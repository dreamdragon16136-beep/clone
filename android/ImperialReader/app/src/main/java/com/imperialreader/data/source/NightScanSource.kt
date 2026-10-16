package com.imperialreader.data.source

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class NightScanSource : HttpSource() {
    override val id = "wp_night_scans_com"
    override val name = "NightScan"
    override val lang = "en"
    override val baseUrl = "https://www.nightscan.net"
    override val nsfw = true

    private val seriesIds = ConcurrentHashMap<String, String>()

    override suspend fun getPopular(page: Int): MangasPage =
        list("where[type][equals]=manhwa&sort=-viewCount&page=${page.coerceAtLeast(1)}")

    override suspend fun getLatest(page: Int): MangasPage =
        list("where[type][equals]=manhwa&sort=-updatedAt&page=${page.coerceAtLeast(1)}")

    override suspend fun search(query: String, page: Int): MangasPage =
        list(
            "where[type][equals]=manhwa&where[title][contains]=${enc(query)}&page=${page.coerceAtLeast(1)}"
        )

    private suspend fun list(query: String): MangasPage {
        val json = JSONObject(getString("$baseUrl/api/series?$query&limit=$PAGE_SIZE&depth=0"))
        val docs = json.optJSONArray("docs") ?: throw IOException("NightScan returned no series catalog")
        val mangas = (0 until docs.length()).mapNotNull { index ->
            val item = docs.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val slug = item.optString("slug").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            seriesIds[slug] = id
            SManga(url = "$baseUrl/series/$slug", title = title)
        }
        return MangasPage(mangas, json.optBoolean("hasNextPage"))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = resolveSeriesId(manga.url)
        val json = JSONObject(getString("$baseUrl/api/series/$id?depth=1"))
        manga.title = json.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: manga.title
        manga.description = json.optJSONObject("description")?.let(::plainText)?.ifBlank { null }
        manga.author = json.optJSONArray("creators")?.let(::names)?.ifBlank { null }
        manga.genre = json.optJSONArray("genres")?.let(::names)?.ifBlank { null }
        manga.status = statusFromText(json.optString("status"))
        if (json.optBoolean("isAdult")) {
            val doc = Jsoup.parse(getString(manga.url), manga.url)
            manga.thumbnailUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: manga.thumbnailUrl
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val id = resolveSeriesId(manga.url)
        val slug = seriesSlug(manga.url)
        val englishChapters = fetchChapters(id, slug, ENGLISH_LANGUAGE_ID)
        val chapters = if (englishChapters.isNotEmpty()) {
            englishChapters
        } else {
            fetchChapters(id, slug, languageId = null).distinctBy { it.url }
        }
        if (chapters.isEmpty()) {
            throw IOException("NightScan has no published chapters with page images for this series")
        }
        return chapters.sortedByDescending { it.chapterNumber }
    }

    private suspend fun fetchChapters(
        seriesId: String,
        slug: String,
        languageId: Int?
    ): List<SChapter> {
        val candidates = mutableListOf<Pair<String, SChapter>>()
        var page = 1
        var hasNext: Boolean
        do {
            val languageFilter = languageId?.let { "&where[language][equals]=$it" }.orEmpty()
            val json = JSONObject(
                getString(
                    "$baseUrl/api/chapter-translations?where[chapter.series][equals]=$seriesId" +
                        "$languageFilter&where[status][equals]=published" +
                        "&sort=-chapter.chapterNumber&page=$page&limit=$PAGE_SIZE&depth=1"
                )
            )
            val docs = json.optJSONArray("docs")
                ?: throw IOException("NightScan returned no English chapter list")
            for (index in 0 until docs.length()) {
                val translation = docs.optJSONObject(index) ?: continue
                val chapter = translation.optJSONObject("chapter") ?: continue
                val number = chapter.optString("chapterNumber").takeIf { it.isNotBlank() && it != "null" }
                val chapterId = chapter.optString("id").takeIf { it.isNotBlank() && it != "null" }
                    ?: continue
                val translationId = translation.optString("id")
                    .takeIf { it.matches(Regex("""\d+""")) }
                    ?: continue
                val title = translation.optString("displayTitle")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: "Chapter ${number ?: chapterId}"
                candidates += translationId to SChapter(
                        url = "$baseUrl/series/$slug/chapter/${number ?: chapterId}",
                        name = title,
                        dateUpload = translation.optString("publishedAt").toEpochMillis(),
                        chapterNumber = number?.toFloatOrNull() ?: -1f
                )
            }
            hasNext = json.optBoolean("hasNextPage")
            page++
        } while (hasNext)
        if (candidates.isEmpty()) return emptyList()
        val translationsWithPages = fetchTranslationsWithPages(candidates.map { it.first }.distinct())
        return candidates.filter { it.first in translationsWithPages }
            .map { it.second }
            .distinctBy { it.url }
    }

    private suspend fun fetchTranslationsWithPages(translationIds: List<String>): Set<String> {
        val translationsWithPages = mutableSetOf<String>()
        for (batch in translationIds.distinct().chunked(PAGE_SIZE)) {
            var page = 1
            var hasNext: Boolean
            do {
                val json = JSONObject(
                    getString(
                        "$baseUrl/api/chapter-pages?where[chapterTranslation][in]=${enc(batch.joinToString(","))}" +
                            "&page=$page&limit=1000&depth=0"
                    )
                )
                val docs = json.optJSONArray("docs")
                    ?: throw IOException("NightScan returned no chapter page records")
                for (index in 0 until docs.length()) {
                    val record = docs.optJSONObject(index) ?: continue
                    val translation = record.opt("chapterTranslation")
                    val id = when (translation) {
                        is JSONObject -> translation.optString("id")
                        else -> translation?.toString().orEmpty()
                    }
                    if (id in batch) translationsWithPages += id
                }
                hasNext = json.optBoolean("hasNextPage")
                page++
                if (page > MAX_CHAPTER_PAGE_LIST_PAGES) {
                    throw IOException("NightScan chapter page list exceeds the safe limit")
                }
            } while (hasNext)
        }
        return translationsWithPages
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val pages = doc.select("img[src], img[data-src], img[data-original]")
            .mapNotNull { image ->
                val url = image.absUrl("data-src").ifBlank {
                    image.absUrl("data-original").ifBlank { image.absUrl("src") }
                }
                val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
                url.takeIf {
                    host.endsWith("r2.dev") &&
                        (it.contains("/media/") || it.contains("r2.dev/media")) &&
                        !it.contains("logo", true) &&
                        !it.contains("placeholder", true)
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
        if (pages.isEmpty()) throw IOException("NightScan returned no readable pages for this chapter")
        return pages
    }

    private suspend fun resolveSeriesId(url: String): String {
        val slug = seriesSlug(url)
        seriesIds[slug]?.let { return it }
        val json = JSONObject(
            getString("$baseUrl/api/series?where[slug][equals]=${enc(slug)}&limit=1&depth=0")
        )
        val doc = json.optJSONArray("docs")?.optJSONObject(0)
            ?: throw IOException("NightScan could not find the requested series")
        val id = doc.optString("id").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("NightScan returned an invalid series identifier")
        seriesIds[slug] = id
        return id
    }

    private fun seriesSlug(url: String): String =
        runCatching { URI(url).path.substringAfter("/series/").substringBefore('/') }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != url }
            ?: throw IOException("NightScan returned an invalid series address")

    private fun names(data: JSONArray): String =
        (0 until data.length()).mapNotNull { index ->
            when (val value = data.opt(index)) {
                is JSONObject -> value.optString("name").ifBlank { value.optString("title") }
                    .takeIf { it.isNotBlank() && it != "null" }
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
        }.distinct().joinToString(", ")

    private fun plainText(value: JSONObject): String = buildString {
        fun appendText(item: Any?) {
            when (item) {
                is JSONObject -> {
                    if (item.optString("type") == "text") {
                        append(item.optString("text"))
                    } else {
                        val keys = item.keys()
                        while (keys.hasNext()) appendText(item.opt(keys.next()))
                    }
                }
                is JSONArray -> for (index in 0 until item.length()) appendText(item.opt(index))
            }
        }
        appendText(value)
    }.trim()

    private fun String.toEpochMillis(): Long =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    private companion object {
        const val PAGE_SIZE = 100
        const val ENGLISH_LANGUAGE_ID = 1
        const val MAX_CHAPTER_PAGE_LIST_PAGES = 100
    }
}
