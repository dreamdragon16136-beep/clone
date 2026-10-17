package com.imperialreader.data.source

import com.imperialreader.App
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** MangaDex via its official public API (no scraping, no Cloudflare issues). */
class MangaDexSource : HttpSource() {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val lang = "en"
    override val baseUrl = "https://mangadex.org"

    private val api = "https://api.mangadex.org"
    private val covers = "https://uploads.mangadex.org/covers"
    private val pageSize = 20
    /** Official API ratings; erotica/pornographic are only requested when 18+ is enabled in Settings. */
    private val rating: String
        get() = if (App.instance.prefs.showNsfwExtensions)
            "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic"
        else "&contentRating[]=safe&contentRating[]=suggestive"

    override val extraHeaders: Map<String, String> get() = mapOf("Referer" to "https://mangadex.org/")

    override suspend fun getPopular(page: Int): MangasPage =
        list("$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}&includes[]=cover_art" +
            "&order[followedCount]=desc&hasAvailableChapters=true&availableTranslatedLanguage[]=en$rating")

    override suspend fun getLatest(page: Int): MangasPage =
        list("$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}&includes[]=cover_art" +
            "&order[latestUploadedChapter]=desc&hasAvailableChapters=true&availableTranslatedLanguage[]=en$rating")

    override suspend fun search(query: String, page: Int): MangasPage =
        list("$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}&includes[]=cover_art" +
            "&title=${enc(query)}&order[relevance]=desc&availableTranslatedLanguage[]=en$rating")

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage {
        val authors = JSONObject(getString("$api/author?limit=10&name=${enc(query)}"))
            .optJSONArray("data") ?: JSONArray()
        val authorIds = (0 until authors.length()).mapNotNull { index ->
            val author = authors.optJSONObject(index) ?: return@mapNotNull null
            val name = author.optJSONObject("attributes")?.optString("name").orEmpty()
            if (!name.contains(query, ignoreCase = true)) null else author.optString("id").ifBlank { null }
        }.distinct().take(3)
        if (authorIds.isEmpty()) return MangasPage(emptyList(), false)
        val authorFilter = authorIds.joinToString("&") { "authors[]=${enc(it)}" }
        return list(
            "$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}&includes[]=cover_art" +
                "&$authorFilter&order[relevance]=desc&availableTranslatedLanguage[]=en$rating"
        )
    }

    override suspend fun searchByGenre(query: String, page: Int): MangasPage {
        val tags = JSONObject(getString("$api/manga/tag")).optJSONArray("data") ?: JSONArray()
        val matches = (0 until tags.length()).mapNotNull { index ->
            val tag = tags.optJSONObject(index) ?: return@mapNotNull null
            val name = tag.optJSONObject("attributes")?.optJSONObject("name")?.optString("en").orEmpty()
            if (name.contains(query, ignoreCase = true)) tag.optString("id") to name else null
        }
        val tag = matches.minByOrNull { (_, name) ->
            if (name.equals(query.trim(), ignoreCase = true)) 0 else name.length
        } ?: return MangasPage(emptyList(), false)
        val url = "$api/manga?limit=$pageSize&offset=${(page - 1) * pageSize}" +
            "&includes[]=cover_art&includedTags[]=${tag.first}" +
            "&order[followedCount]=desc&hasAvailableChapters=true&availableTranslatedLanguage[]=en$rating"
        return list(url)
    }

    private suspend fun list(url: String): MangasPage {
        val json = JSONObject(getString(url))
        val data = json.optJSONArray("data") ?: JSONArray()
        val mangas = (0 until data.length()).map { toManga(data.getJSONObject(it)) }
        val total = json.optInt("total", 0)
        val offset = json.optInt("offset", 0)
        val limit = json.optInt("limit", pageSize)
        return MangasPage(mangas, offset + limit < total)
    }

    private fun title(attrs: JSONObject): String {
        val t = attrs.optJSONObject("title")
        val en = t?.optString("en").orEmpty()
        if (en.isNotBlank()) return en
        if (t != null) {
            val keys = t.keys()
            while (keys.hasNext()) {
                val v = t.optString(keys.next())
                if (v.isNotBlank()) return v
            }
        }
        val alts = attrs.optJSONArray("altTitles")
        if (alts != null) for (i in 0 until alts.length()) {
            val en2 = alts.getJSONObject(i).optString("en")
            if (en2.isNotBlank()) return en2
        }
        return "Untitled"
    }

    private fun relationship(obj: JSONObject, type: String): JSONObject? {
        val rels = obj.optJSONArray("relationships") ?: return null
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            if (r.optString("type") == type) return r
        }
        return null
    }

    private fun toManga(obj: JSONObject): SManga {
        val id = obj.getString("id")
        val attrs = obj.getJSONObject("attributes")
        val file = relationship(obj, "cover_art")?.optJSONObject("attributes")?.optString("fileName").orEmpty()
        val tags = attrs.optJSONArray("tags")
        val genres = if (tags == null) emptyList() else (0 until tags.length()).mapNotNull { index ->
            tags.optJSONObject(index)?.optJSONObject("attributes")?.optJSONObject("name")
                ?.optString("en")?.ifBlank { null }
        }
        return SManga(
            url = "/manga/$id",
            title = title(attrs),
            thumbnailUrl = if (file.isNotBlank()) "$covers/$id/$file.256.jpg" else null,
            genre = genres.joinToString(", ").ifBlank { null }
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = manga.url.substringAfterLast('/')
        val json = JSONObject(getString("$api/manga/$id?includes[]=cover_art&includes[]=author&includes[]=artist"))
        val obj = json.getJSONObject("data")
        val attrs = obj.getJSONObject("attributes")
        manga.title = title(attrs)
        manga.description = attrs.optJSONObject("description")?.optString("en")?.ifBlank { null }
        manga.status = when (attrs.optString("status")) {
            "ongoing" -> 1
            "completed" -> 2
            "hiatus", "cancelled" -> 3
            else -> 0
        }
        val tags = attrs.optJSONArray("tags")
        if (tags != null) {
            manga.genre = (0 until tags.length()).mapNotNull {
                tags.getJSONObject(it).optJSONObject("attributes")?.optJSONObject("name")?.optString("en")
            }.joinToString(", ").ifBlank { null }
        }
        manga.author = relationship(obj, "author")?.optJSONObject("attributes")?.optString("name")?.ifBlank { null }
        manga.artist = relationship(obj, "artist")?.optJSONObject("attributes")?.optString("name")?.ifBlank { null }
        val file = relationship(obj, "cover_art")?.optJSONObject("attributes")?.optString("fileName").orEmpty()
        if (file.isNotBlank()) manga.thumbnailUrl = "$covers/$id/$file.512.jpg"
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val id = manga.url.substringAfterLast('/')
        val result = mutableListOf<SChapter>()
        var offset = 0
        val limit = 500
        while (true) {
            val url = "$api/manga/$id/feed?limit=$limit&offset=$offset&translatedLanguage[]=en" +
                "&order[volume]=desc&order[chapter]=desc&includeFuturePublishAt=0&includeEmptyPages=0" +
                "&includes[]=scanlation_group" +
                "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic"
            val json = JSONObject(getString(url))
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val attrs = obj.getJSONObject("attributes")
                if (!attrs.isNull("externalUrl")) continue
                val vol = attrs.optString("volume").takeIf { it.isNotBlank() && it != "null" }
                val ch = attrs.optString("chapter").takeIf { it.isNotBlank() && it != "null" }
                val title = attrs.optString("title").takeIf { it.isNotBlank() && it != "null" }
                val name = buildString {
                    if (vol != null) append("Vol.$vol ")
                    if (ch != null) append("Ch.$ch") else if (title == null) append("Oneshot")
                    if (title != null) { if (isNotEmpty()) append(" - "); append(title) }
                }.trim()
                val date = runCatching { dateFormat.parse(attrs.optString("publishAt"))?.time }.getOrNull() ?: 0L
                result.add(
                    SChapter(
                        url = "/chapter/${obj.getString("id")}",
                        name = name,
                        dateUpload = date,
                        chapterNumber = ch?.toFloatOrNull() ?: -1f,
                        scanlator = relationship(obj, "scanlation_group")?.optJSONObject("attributes")?.optString("name")?.ifBlank { null }
                    )
                )
            }
            val total = json.optInt("total", 0)
            offset += limit
            if (offset >= total || data.length() == 0) break
        }
        return result
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val id = chapter.url.substringAfterLast('/')
        val json = JSONObject(getString("$api/at-home/server/$id"))
        val base = json.getString("baseUrl")
        val ch = json.getJSONObject("chapter")
        val hash = ch.getString("hash")
        val files = ch.getJSONArray("data")
        return (0 until files.length()).map { "$base/data/$hash/${files.getString(it)}" }
    }
}
