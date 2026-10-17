package com.imperialreader.data.source

import com.imperialreader.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

/** MangaK's public title and chapter APIs provide catalog metadata and full reader image URLs. */
class MangaKSource : HttpSource() {
    override val id = "mangak_io"
    override val name = "MangaK.io"
    override val lang = "en"
    override val baseUrl = "https://mangak.io"

    private val api = "https://api.mangak.io"
    private val pageSize = 24

    override suspend fun getPopular(page: Int): MangasPage = titleSearch("", page, "popular")
    override suspend fun getLatest(page: Int): MangasPage = titleSearch("", page, "latest")
    override suspend fun search(query: String, page: Int): MangasPage = titleSearch(query, page)

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage {
        val results = search(query, page)
        val limiter = Semaphore(4)
        val matches = coroutineScope {
            results.mangas.map { item ->
                async {
                    try {
                        limiter.withPermit {
                            getMangaDetails(item).takeIf { manga ->
                                listOfNotNull(manga.author, manga.artist)
                                    .any { it.contains(query, ignoreCase = true) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return MangasPage(matches, results.hasNextPage && matches.isNotEmpty())
    }

    override suspend fun searchByGenre(query: String, page: Int): MangasPage {
        val results = search(query, page)
        val limiter = Semaphore(4)
        val matches = coroutineScope {
            results.mangas.map { item ->
                async {
                    try {
                        limiter.withPermit {
                            getMangaDetails(item).takeIf { it.genre?.contains(query, ignoreCase = true) == true }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return MangasPage(matches, results.hasNextPage && matches.isNotEmpty())
    }

    private suspend fun titleSearch(query: String, page: Int, sort: String? = null): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val params = buildList {
            add("page=$page")
            add("limit=$pageSize")
            query.takeIf { it.isNotBlank() }?.let { add("q=${enc(it)}") }
            sort?.let { add("sort=${enc(it)}") }
        }.joinToString("&")
        val data = JSONObject(getString("$api/titles/search?$params")).optJSONObject("data")
            ?: throw IOException("MangaK returned no catalog data")
        val entries = data.optJSONArray("items") ?: JSONArray()
        val items = (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            if (entry.optBoolean("is_adult") && !App.instance.prefs.showNsfwExtensions) return@mapNotNull null
            val id = entry.optString("id").takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
                ?: return@mapNotNull null
            val slug = entry.optString("url").takeIf { it.startsWith("/") }
                ?: entry.optString("slug").takeIf { it.isNotBlank() }?.let { "/$it" }
                ?: return@mapNotNull null
            val title = entry.optString("name").trim().ifBlank { return@mapNotNull null }
            SManga(
                url = "$baseUrl$slug?id=$id",
                title = title,
                thumbnailUrl = entry.optString("cover").takeIf { it.startsWith("https://") },
                description = entry.optString("summary").takeIf { it.isNotBlank() },
                genre = names(entry.optJSONArray("genres")).joinToString(", ").ifBlank { null },
                status = status(entry.optString("status"))
            )
        }.distinctBy { it.url }
        val pagination = data.optJSONObject("pagination")
        return MangasPage(items, pagination?.optBoolean("has_next") ?: (items.size >= pageSize))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val id = titleId(manga.url) ?: lookupTitleId(manga.url)
        val title = JSONObject(getString("$api/titles/$id")).optJSONObject("data")
            ?.optJSONObject("title") ?: throw IOException("MangaK could not find title details")
        if (title.optBoolean("is_adult") && !App.instance.prefs.showNsfwExtensions) {
            throw IOException("This MangaK title is adult content; enable 18+ sources to view it")
        }
        val authors = names(title.optJSONArray("authors"))
        val artists = names(title.optJSONArray("artists"))
        val genres = names(title.optJSONArray("genres"))
        val tags = names(title.optJSONArray("tags"))
        manga.title = title.optString("name").ifBlank { manga.title }
        manga.thumbnailUrl = title.optString("cover").takeIf { it.startsWith("https://") } ?: manga.thumbnailUrl
        manga.description = title.optString("summary").takeIf { it.isNotBlank() } ?: manga.description
        manga.author = authors.firstOrNull()
        manga.artist = artists.firstOrNull()
        manga.genre = (genres + tags).distinct().joinToString(", ").ifBlank { null }
        manga.status = status(title.optString("status"))
        manga.url = withTitleId(manga.url, id)
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val titleId = titleId(manga.url) ?: lookupTitleId(manga.url)
        val all = mutableListOf<SChapter>()
        var page = 1
        while (page <= MAX_CHAPTER_INDEX_PAGES) {
            val data = JSONObject(
                getString("$api/titles/$titleId/chapters?page=$page&limit=$CHAPTER_PAGE_SIZE")
            ).optJSONObject("data") ?: throw IOException("MangaK returned no chapter data")
            val entries = data.optJSONArray("chapters") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val chapter = entries.optJSONObject(index) ?: continue
                val chapterId = chapter.optString("id").takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
                    ?: continue
                val name = chapter.optString("name").trim()
                if (name.isBlank()) continue
                val number = chapter.optDouble("number", -1.0).toFloat()
                all += SChapter(
                    url = "$api/titles/$titleId/chapters/$chapterId",
                    name = name,
                    chapterNumber = number
                )
            }
            val pagination = data.optJSONObject("pagination")
            if (pagination?.optBoolean("has_next") != true || entries.length() == 0) break
            page++
        }
        if (page > MAX_CHAPTER_INDEX_PAGES) {
            throw IOException("MangaK chapter list exceeds the safe limit")
        }
        if (all.isEmpty()) throw IOException("MangaK returned no chapters for this title")
        return all.distinctBy { it.url }
            .sortedWith(compareByDescending<SChapter> { it.chapterNumber }.thenByDescending { it.name })
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val uri = runCatching { URI(chapter.url) }.getOrNull()
            ?: throw IOException("Invalid MangaK chapter address")
        if (!uri.host.equals("api.mangak.io", ignoreCase = true) ||
            !uri.path.matches(Regex("""/titles/[A-Za-z0-9]+/chapters/[A-Za-z0-9]+"""))
        ) throw IOException("Invalid MangaK chapter address")
        val data = JSONObject(getString(chapter.url)).optJSONObject("data")
            ?: throw IOException("MangaK returned no reader data")
        val chapterData = data.optJSONObject("chapter") ?: data
        val images = chapterData.optJSONArray("images") ?: JSONArray()
        val pages = (0 until images.length()).mapNotNull { index ->
            images.optString(index).takeIf {
                it.startsWith("https://") &&
                    it.substringBefore('?').matches(Regex("""(?i).+\.(?:jpe?g|png|webp|avif)$""")) &&
                    !it.contains(Regex("""(?i)(placeholder|loading|thumb|logo|avatar)"""))
            }
        }.distinct()
        if (pages.isEmpty()) throw IOException("MangaK returned no readable chapter images")
        return pages
    }

    private suspend fun lookupTitleId(url: String): String {
        val slug = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            ?.ifBlank { null } ?: throw IOException("Invalid MangaK title address")
        val entries = JSONObject(getString("$api/titles/search?q=${enc(slug)}&page=1&limit=$pageSize"))
            .optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        return (0 until entries.length()).asSequence()
            .mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("slug") == slug || it.optString("url").trim('/') == slug }
            ?.optString("id")?.takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
            ?: throw IOException("MangaK title identifier was not found")
    }

    private fun titleId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("mangak.io", ignoreCase = true)) return null
        return uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') == "id") pair.substringAfter('=', "").takeIf {
                it.matches(Regex("""[A-Za-z0-9]+"""))
            } else null
        }
    }

    private fun withTitleId(url: String, id: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val query = uri.rawQuery.orEmpty().split('&')
            .filterNot { it.substringBefore('=') == "id" && it.substringAfter('=') != id }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (query.none { it.substringBefore('=') == "id" }) query += "id=$id"
        return "$baseUrl${uri.path}?${query.joinToString("&")}"
    }

    private fun names(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index)
            item?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun status(value: String): Int = when (value.lowercase()) {
        "ongoing", "publishing" -> 1
        "completed", "complete", "finished" -> 2
        "hiatus", "cancelled", "canceled" -> 3
        else -> 0
    }

    private companion object {
        const val CHAPTER_PAGE_SIZE = 100
        const val MAX_CHAPTER_INDEX_PAGES = 100
    }
}
