package com.imperialreader.data.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.imperialreader.data.network.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import kotlin.math.ceil

/** Doujins.com catalog. Protected or inaccessible pages fail explicitly instead of returning thumbnails. */
class DoujinsSource : HttpSource() {
    override val id = "doujins_com"
    override val name = "Doujins.com"
    override val lang = "en"
    override val baseUrl = "https://doujins.com"
    override val nsfw = true

    override suspend fun getPopular(page: Int): MangasPage = feed(page)
    override suspend fun getLatest(page: Int): MangasPage = feed(page)

    private suspend fun feed(page: Int): MangasPage {
        if (page < 1) return MangasPage(emptyList(), false)
        val end = System.currentTimeMillis() / DAY_MS * DAY_SECONDS - (page - 1) * DAY_SECONDS
        val start = end - DAY_SECONDS
        val json = JSONObject(getString("$baseUrl/folders?start=$start&end=$end"))
        val folders = sequenceOf(json.optJSONArray("data"), json.optJSONArray("premium"), json.optJSONArray("folders"))
            .filterNotNull()
            .flatMap { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            .toList()
        val items = folders.mapNotNull(::itemFromFolder).distinctBy { it.url }
        return MangasPage(items, folders.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank() || page < 1) return MangasPage(emptyList(), false)
        val url = "$baseUrl/searches?words=${enc(query)}&page=$page"
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("a[href]").asSequence()
            .mapNotNull { link ->
                val href = link.absUrl("href")
                if (!isGalleryUrl(href)) return@mapNotNull null
                val title = sequenceOf(link.attr("title"), link.selectFirst("h2,h3,h4")?.text().orEmpty(), link.text())
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && it.length < 200 }
                    ?: return@mapNotNull null
                val img = link.selectFirst("img")
                val parentText = link.parent()?.parent()?.text().orEmpty()
                SManga(
                    href,
                    title,
                    imageUrl(img),
                    artist = Regex("""(?i)artist\s*:\s*(.+?)(?:\s+tags?\s*:|$)""")
                        .find(parentText)?.groupValues?.get(1)?.trim()
                )
            }.distinctBy { it.url }.toList()
        return MangasPage(items, doc.select("a[rel=next], .pagination a[href]").any {
            it.attr("rel").contains("next", true) || it.text().contains("»") || it.text().equals("Next", true)
        })
    }

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage {
        val results = search(query, page)
        val matches = results.mangas.filter {
            it.artist?.contains(query, ignoreCase = true) == true ||
                it.title.substringBefore(']').contains(query, ignoreCase = true)
        }
        return MangasPage(matches, results.hasNextPage && matches.isNotEmpty())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (!isGalleryUrl(manga.url)) throw IOException("Invalid Doujins.com gallery address")
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: manga.title
        manga.description = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.ifBlank { null } ?: manga.description
        val detailText = doc.select("main, #content, .container").text()
        manga.artist = doc.select(".artist a, a[href*=/artist/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank {
                Regex("""(?i)artist\s*:\s*([^\n|]+)""").find(detailText)?.groupValues?.get(1)?.trim()
            } ?: manga.artist
        manga.genre = doc.select("a[href*=/tag], a[href*=/tags/]")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 60 }
            .distinct().joinToString(", ").ifBlank { null } ?: manga.genre
        doc.selectFirst("img.doujin[data-thumb]")?.let { img ->
            if (manga.thumbnailUrl.isNullOrBlank()) manga.thumbnailUrl = img.attr("data-thumb")
        }
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val images = doc.select("img.doujin").size
        val pageCount = Regex("""(?i)(?:length|pages?)\s*:?\s*(\d+)\s+pages?""")
            .find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        val count = pageCount ?: images
        if (count <= 0) throw IOException("Doujins.com did not provide a gallery page count")
        return listOf(SChapter(manga.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        if (!isGalleryUrl(chapter.url)) throw IOException("Invalid Doujins.com gallery address")
        val doc = Jsoup.parse(getString(chapter.url), chapter.url)
        val expected = Regex("""(?i)(?:length|pages?)\s*:?\s*(\d+)\s+pages?""")
            .find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        val readerImages = doc.select("img.doujin").mapNotNull { image ->
            val link = image.attr("data-link")
            if (link == "/subscribe") throw IOException("This Doujins.com gallery requires a subscription")
            val full = image.attr("data-file").ifBlank { image.attr("data-src") }.ifBlank { image.attr("src") }
            full.takeIf(::isFullReaderImage)
        }.distinct()
        if (readerImages.isEmpty()) throw IOException("Doujins.com returned no full-size reader images")
        if (expected != null && readerImages.size < expected) {
            throw IOException("Doujins.com exposed ${readerImages.size} of $expected gallery pages")
        }
        return readerImages
    }

    private fun itemFromFolder(folder: JSONObject): SManga? {
        val link = folder.optString("link").takeIf { it.startsWith("/") } ?: return null
        val title = folder.optString("name").trim().ifBlank { return null }
        return SManga(
            url = "$baseUrl$link",
            title = title,
            thumbnailUrl = folder.optString("thumbnail").takeIf { it.startsWith("https://") },
            artist = folder.optString("artistList").takeIf { it.isNotBlank() },
            genre = folder.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("tag") }
                    .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
            }
        )
    }

    private fun imageUrl(image: Element?): String? {
        if (image == null) return null
        return sequenceOf(image.attr("data-src"), image.attr("src"), image.attr("data-thumb"))
            .firstOrNull { it.startsWith("https://") }
    }

    private fun isGalleryUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.equals("doujins.com", true) &&
            uri.path.matches(Regex("""/(?:[^/]+/)+[^/]+-\d+/?"""))
    }

    private fun isFullReaderImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host.equals("static.doujins.com", true) &&
            uri.path.substringAfterLast('/').startsWith("n-") &&
            uri.path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val DAY_MS = DAY_SECONDS * 1000
    }
}

/** E-Hentai's gdata API supplies gallery titles, page counts, and complete tag metadata. */
class EHentaiSource : HttpSource() {
    override val id = "e_hentai_org"
    override val name = "E-Hentai"
    override val lang = "en"
    override val baseUrl = "https://e-hentai.org"
    override val nsfw = true

    private val apiUrl = "$baseUrl/api.php"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getPopular(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/popular" else "$baseUrl/popular?page=${page - 1}")

    override suspend fun getLatest(page: Int): MangasPage =
        list(if (page <= 1) "$baseUrl/" else "$baseUrl/?page=${page - 1}")

    override suspend fun search(query: String, page: Int): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)
        return list("$baseUrl/?f_search=${enc(query)}&page=${(page - 1).coerceAtLeast(0)}")
    }

    override suspend fun searchByAuthor(query: String, page: Int): MangasPage =
        search("artist:\"$query\"", page)

    private suspend fun list(url: String): MangasPage {
        val doc = Jsoup.parse(getString(url), url)
        val items = doc.select("a[href]").asSequence()
            .mapNotNull { link ->
                val address = link.absUrl("href")
                val gallery = galleryAddress(address) ?: return@mapNotNull null
                val img = link.selectFirst("img")
                val title = sequenceOf(link.attr("title"), img?.attr("alt").orEmpty(), link.text())
                    .map { it.trim() }.firstOrNull { it.isNotBlank() && it.length < 300 }
                    ?: return@mapNotNull null
                SManga(
                    url = gallery.url,
                    title = title,
                    thumbnailUrl = img?.let(::imageUrl)
                )
            }.distinctBy { it.url }.toList()
        val current = pageFromUrl(url)
        val hasNext = doc.select("a[href]").any {
            it.text().trim() in setOf(">", "»", "Next") ||
                it.attr("rel").contains("next", ignoreCase = true) ||
                pageFromUrl(it.absUrl("href")) > current
        }
        return MangasPage(items, hasNext)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val address = galleryAddress(manga.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val meta = galleryMetadata(address)
        if (meta.optBoolean("expunged")) throw IOException("This E-Hentai gallery has been removed")
        manga.title = meta.optString("title").ifBlank { manga.title }
        manga.thumbnailUrl = meta.optString("thumb").takeIf { it.startsWith("https://") } ?: manga.thumbnailUrl
        val tags = meta.optJSONArray("tags") ?: JSONArray()
        val artists = mutableListOf<String>()
        val genres = mutableListOf<String>()
        for (index in 0 until tags.length()) {
            val tagged = tags.optString(index).trim()
            val namespace = tagged.substringBefore(':', "")
            val value = tagged.substringAfter(':', tagged).trim()
            if (value.isBlank()) continue
            when (namespace.lowercase()) {
                "artist" -> artists += value
                "language", "group", "parody", "character", "reclass" -> Unit
                else -> genres += value
            }
        }
        manga.artist = artists.distinct().joinToString(", ").ifBlank { null } ?: manga.artist
        manga.genre = (genres + meta.optString("category").takeIf { it.isNotBlank() })
            .distinct().joinToString(", ").ifBlank { null }
        manga.author = meta.optString("uploader").ifBlank { null } ?: manga.author
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val address = galleryAddress(manga.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val metadata = galleryMetadata(address)
        if (metadata.optBoolean("expunged")) throw IOException("This E-Hentai gallery has been removed")
        val count = metadata.optInt("filecount", 0)
        if (count <= 0) throw IOException("E-Hentai returned an empty gallery")
        return listOf(SChapter(address.url, "Gallery ($count pages)", chapterNumber = 1f))
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val address = galleryAddress(chapter.url) ?: throw IOException("Invalid E-Hentai gallery address")
        val metadata = galleryMetadata(address)
        val count = metadata.optInt("filecount", 0)
        if (count <= 0 || count > MAX_GALLERY_PAGES) {
            throw IOException("E-Hentai returned an invalid gallery page count")
        }
        val indexPageCount = ceil(count.toDouble() / GALLERY_INDEX_PAGE_SIZE).toInt()
        val indexLimiter = Semaphore(4)
        val readerPages = coroutineScope {
            (0 until indexPageCount).map { indexPage ->
                async {
                    indexLimiter.withPermit {
                        val url = if (indexPage == 0) address.url else "${address.url}?p=$indexPage"
                        val doc = Jsoup.parse(getString(url), url)
                        doc.select("a[href*=/s/]").mapNotNull { link ->
                            readerAddress(link.absUrl("href"), address.id)
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.page }
        }.sortedBy { it.page }
        if (readerPages.size < count) {
            throw IOException("E-Hentai exposed ${readerPages.size} of $count reader pages")
        }

        val readerLimiter = Semaphore(6)
        val images = coroutineScope {
            readerPages.take(count).map { reader ->
                async {
                    readerLimiter.withPermit {
                        val doc = Jsoup.parse(getString(reader.url), reader.url)
                        val image = doc.selectFirst("#img[src], img#img[src]")?.absUrl("src")
                            ?: throw IOException("E-Hentai did not return page ${reader.page}")
                        image.takeIf(::isReaderImage)
                            ?: throw IOException("E-Hentai returned an invalid image for page ${reader.page}")
                    }
                }
            }.awaitAll()
        }
        if (images.size != count) throw IOException("E-Hentai returned an incomplete gallery")
        return images
    }

    private suspend fun galleryMetadata(address: GalleryAddress): JSONObject {
        val payload = JSONObject()
            .put("method", "gdata")
            .put("gidlist", JSONArray().put(JSONArray().put(address.id).put(address.token)))
            .put("namespace", 1)
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        return client.await(request).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, apiUrl)
            val galleries = JSONObject(response.body?.string().orEmpty()).optJSONArray("gmetadata")
                ?: throw IOException("E-Hentai returned no gallery metadata")
            galleries.optJSONObject(0) ?: throw IOException("E-Hentai returned no gallery metadata")
        }
    }

    private fun galleryAddress(url: String): GalleryAddress? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("e-hentai.org", true)) return null
        val match = Regex("""^/g/(\d+)/([A-Za-z0-9]+)/?""").matchEntire(uri.path.orEmpty()) ?: return null
        return GalleryAddress(url.substringBefore('?').trimEnd('/') + "/", match.groupValues[1].toLongOrNull()
            ?: return null, match.groupValues[2])
    }

    private fun readerAddress(url: String, galleryId: Long): ReaderAddress? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("e-hentai.org", true)) return null
        val match = Regex("""^/s/([A-Za-z0-9]+)/(\d+)-(\d+)/?$""").matchEntire(uri.path.orEmpty()) ?: return null
        if (match.groupValues[2].toLongOrNull() != galleryId) return null
        val page = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ReaderAddress(url, page)
    }

    private fun pageFromUrl(url: String): Int =
        runCatching { URI(url).rawQuery.orEmpty().substringAfter("p=", "").substringBefore('&').toInt() }
            .getOrDefault(0)

    private fun imageUrl(image: Element): String? =
        sequenceOf(image.attr("data-src"), image.attr("data-original"), image.attr("src"))
            .firstOrNull { it.startsWith("https://") && !it.contains("placeholder", true) }

    private fun isReaderImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return false
        if (uri.host.equals("ehgt.org", true)) return false
        val path = uri.path.orEmpty()
        return path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif") &&
            !path.contains(Regex("""(?i)(placeholder|loading|thumb|logo|avatar|blank)"""))
    }

    private data class GalleryAddress(val url: String, val id: Long, val token: String)
    private data class ReaderAddress(val url: String, val page: Int)

    private companion object {
        const val GALLERY_INDEX_PAGE_SIZE = 40
        const val MAX_GALLERY_PAGES = 2_000
    }
}
