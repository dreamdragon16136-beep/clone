package com.imperialreader.data.source

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.imperialreader.util.parseChapterNumber
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import kotlin.math.min

class MangaFireSource : HttpSource() {
    override val id = "wp_mangafire_to"
    override val name = "MangaFire"
    override val lang = "en"
    override val baseUrl = "https://mangafire.to"
    override val extraHeaders: Map<String, String>
        get() = super.extraHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.9"
        )

    override suspend fun getPopular(page: Int): MangasPage =
        list(
            listOf(
                "$baseUrl/filter?sort=most_viewed&page=${page.coerceAtLeast(1)}",
                "$baseUrl/filter?sort=most_viewed&language%5B%5D=en&page=${page.coerceAtLeast(1)}"
            ),
            page
        )

    override suspend fun getLatest(page: Int): MangasPage =
        list(
            listOf(
                "$baseUrl/filter?sort=recently_updated&page=${page.coerceAtLeast(1)}",
                "$baseUrl/filter?sort=recently_updated&language%5B%5D=en&page=${page.coerceAtLeast(1)}"
            ),
            page
        )

    override suspend fun search(query: String, page: Int): MangasPage {
        val pageNumber = page.coerceAtLeast(1)
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val vrf = MangaFireVrf.generate(query)
        return list(
            listOf(
                "$baseUrl/filter?keyword=$encodedQuery&vrf=$vrf&sort=most_relevance&language%5B%5D=en&page=$pageNumber",
                "$baseUrl/filter?keyword=$encodedQuery&sort=most_relevance&language%5B%5D=en&page=$pageNumber",
                "$baseUrl/filter?keyword=$encodedQuery&sort=most_relevance&page=$pageNumber"
            ),
            pageNumber
        )
    }

    private suspend fun list(urls: List<String>, page: Int): MangasPage {
        var lastError: Exception? = null
        var successfulResponse = false
        for (url in urls.distinct()) {
            try {
                val doc = Jsoup.parse(getString(url), url)
                successfulResponse = true
                val mangas = doc.select(
                    ".original .unit .inner, .unit .inner, .flw-item, .manga-card, .item"
                ).mapNotNull { card ->
                    val link = card.selectFirst(
                        ".info > a[href], .info a[href], a.name[href], a[href*=/manga/], a[href]"
                    ) ?: return@mapNotNull null
                    parseManga(link, card.selectFirst("img"))
                }.ifEmpty {
                    doc.select("a[href*=/manga/]").mapNotNull { link ->
                        parseManga(link, link.selectFirst("img") ?: link.parent()?.selectFirst("img"))
                    }
                }.distinctBy { it.url }
                if (mangas.isNotEmpty()) {
                    val hasNext = doc.selectFirst(
                        ".page-item.active + .page-item .page-link, a[rel=next], .pagination a[href]"
                    ) != null
                    return MangasPage(mangas, hasNext)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (successfulResponse && page > 1) return MangasPage(emptyList(), false)
        throw lastError ?: IOException("MangaFire returned no readable titles")
    }

    private fun parseManga(link: Element, image: Element?): SManga? {
        val url = link.absUrl("href").ifBlank { absoluteUrl(link.attr("href")) }
        if (url.isBlank() || !url.contains("/manga/")) return null
        val title = link.ownText().trim().ifBlank { link.text().trim() }
            .ifBlank { image?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null
        return SManga(url = url, title = title, thumbnailUrl = lazyImage(image))
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        manga.title = doc.selectFirst(".info > h1")?.ownText()?.trim()?.ifBlank { null } ?: manga.title
        manga.thumbnailUrl = lazyImage(doc.selectFirst("div.manga-detail div.poster img")) ?: manga.thumbnailUrl
        manga.description = doc.selectFirst("#synopsis .modal-content, .description")?.text()?.trim()
            ?.ifBlank { null } ?: manga.description
        manga.author = doc.select("div.meta a[href*=/author/]").joinToString { it.ownText().trim() }
            .ifBlank { manga.author.orEmpty() }
        manga.genre = doc.select("div.meta a[href*=/genre/]").joinToString { it.ownText().trim() }
            .ifBlank { manga.genre.orEmpty() }
        manga.status = statusFromText(doc.selectFirst(".info > p")?.ownText().orEmpty())
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val doc = Jsoup.parse(getString(manga.url), manga.url)
        val mangaId = mangaId(manga.url)
        val chapterType = doc.select(".chapvol-tab > a").firstOrNull { it.attr("data-name") == "chapter" }
            ?.attr("data-name")
            ?: throw IOException("MangaFire has no chapter list for this title")
        val language = doc.select(".m-list .tab-content[data-name=chapter] .list-menu .dropdown-item")
            .firstOrNull { it.attr("data-code").equals(lang, ignoreCase = true) }
            ?: throw IOException("MangaFire has no English chapters for this title")
        val langCode = language.attr("data-code").lowercase(Locale.ROOT)
        val listingVrf = MangaFireVrf.generate("$mangaId@$chapterType@$langCode")
        val listJson = JSONObject(getString("$baseUrl/ajax/read/$mangaId/$chapterType/$langCode?vrf=$listingVrf"))
        val chapterLinks = Jsoup.parseBodyFragment(listJson.getJSONObject("result").getString("html"))
            .select("ul li a")
        if (chapterLinks.isEmpty()) throw IOException("MangaFire returned an empty chapter list")

        val dateJson = JSONObject(getString("$baseUrl/ajax/manga/$mangaId/$chapterType/$langCode"))
        val dateLinks = Jsoup.parseBodyFragment(dateJson.getString("result")).select("ul li a")
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        return chapterLinks.mapIndexedNotNull { index, link ->
            val number = link.attr("data-number").toFloatOrNull() ?: return@mapIndexedNotNull null
            val chapterId = link.attr("data-id").ifBlank { return@mapIndexedNotNull null }
            val label = link.attr("title").trim().ifBlank {
                link.text().trim().ifBlank { "Chapter ${link.attr("data-number")}" }
            }
            val date = dateLinks.getOrNull(index)?.select("span")?.getOrNull(1)?.ownText().orEmpty()
            val pageVrf = MangaFireVrf.generate("$chapterType@$chapterId")
            SChapter(
                url = "$baseUrl/ajax/read/$chapterType/$chapterId?vrf=$pageVrf",
                name = label,
                dateUpload = runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L),
                chapterNumber = number
            )
        }.also {
            if (it.isEmpty()) throw IOException("MangaFire returned no valid chapters")
        }.sortedByDescending { it.chapterNumber }
    }

    override suspend fun getPages(chapter: SChapter): List<String> {
        val result = JSONObject(getString(chapter.url)).getJSONObject("result")
        val images = result.getJSONArray("images")
        val pages = (0 until images.length()).mapNotNull { index ->
            val image = images.getJSONArray(index)
            val url = image.optString(0).takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            val offset = image.optInt(2)
            if (offset > 0) "$url#scrambled_$offset" else url
        }
        if (pages.isEmpty()) throw IOException("MangaFire returned no readable pages for this chapter")
        return pages
    }

    private fun mangaId(url: String): String {
        val slug = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
        return slug.substringAfterLast('.').takeIf { it.isNotBlank() && it != slug }
            ?: throw IOException("MangaFire returned an invalid manga address")
    }
}

internal object MangaFirePageTransform {
    fun offset(url: String): Int? =
        Regex("""^scrambled_(\d+)$""").matchEntire(runCatching { URI(url).fragment }.getOrNull().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()

    fun unscramble(bitmap: Bitmap, offset: Int): Bitmap {
        fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor

        val width = bitmap.width
        val height = bitmap.height
        val pieceWidth = min(200, ceilDiv(width, 5))
        val pieceHeight = min(200, ceilDiv(height, 5))
        val xMax = ceilDiv(width, pieceWidth) - 1
        val yMax = ceilDiv(height, pieceHeight) - 1
        if (xMax <= 0 || yMax <= 0) return bitmap

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        for (y in 0..yMax) {
            for (x in 0..xMax) {
                val xDst = pieceWidth * x
                val yDst = pieceHeight * y
                val tileWidth = min(pieceWidth, width - xDst)
                val tileHeight = min(pieceHeight, height - yDst)
                val sourceX = pieceWidth * if (x == xMax) x else (xMax - x + offset) % xMax
                val sourceY = pieceHeight * if (y == yMax) y else (yMax - y + offset) % yMax
                canvas.drawBitmap(
                    bitmap,
                    Rect(sourceX, sourceY, sourceX + tileWidth, sourceY + tileHeight),
                    Rect(xDst, yDst, xDst + tileWidth, yDst + tileHeight),
                    paint
                )
            }
        }
        return output
    }
}

private object MangaFireVrf {
    private val rc4Keys = listOf(
        "FgxyJUQDPUGSzwbAq/ToWn4/e8jYzvabE+dLMb1XU1o=",
        "CQx3CLwswJAnM1VxOqX+y+f3eUns03ulxv8Z+0gUyik=",
        "fAS+otFLkKsKAJzu3yU+rGOlbbFVq+u+LaS6+s1eCJs=",
        "Oy45fQVK9kq9019+VysXVlz1F9S1YwYKgXyzGlZrijo=",
        "aoDIdXezm2l3HrcnQdkPJTDT8+W6mcl2/02ewBHfPzg="
    ).map(Base64.getDecoder()::decode)
    private val seeds = listOf(
        "yH6MXnMEcDVWO/9a6P9W92BAh1eRLVFxFlWTHUqQ474=",
        "RK7y4dZ0azs9Uqz+bbFB46Bx2K9EHg74ndxknY9uknA=",
        "rqr9HeTQOg8TlFiIGZpJaxcvAaKHwMwrkqojJCpcvoc=",
        "/4GPpmZXYpn5RpkP7FC/dt8SXz7W30nUZTe8wb+3xmU=",
        "wsSGSBXKWA9q1oDJpjtJddVxH+evCfL5SO9HZnUDFU8="
    ).map(Base64.getDecoder()::decode)
    private val prefixes = listOf(
        "l9PavRg=",
        "Ml2v7ag1Jg==",
        "i/Va0UxrbMo=",
        "WFjKAHGEkQM=",
        "5Rr27rWd"
    ).map(Base64.getDecoder()::decode)
    private val schedules = listOf(
        listOf("s223", "r4", "r4", "a234", "r7", "r2", "r7", "s223", "r7", "r6"),
        listOf("a19", "r7", "a19", "r6", "a19", "r1", "a19", "r6", "r7", "r4"),
        listOf("s223", "r1", "a19", "s223", "l2", "s223", "a19", "l1", "l2", "l1"),
        listOf("a19", "l1", "l1", "r1", "a234", "l1", "s223", "l6", "l4", "l1"),
        listOf("r1", "l1", "l6", "r1", "l2", "r4", "l1", "l1", "s223", "l2")
    )

    fun generate(input: String): String {
        var bytes = URLEncoder.encode(input, StandardCharsets.UTF_8.name())
            .replace("+", "%20").toByteArray(StandardCharsets.UTF_8)
        for (stage in rc4Keys.indices) {
            bytes = rc4(rc4Keys[stage], bytes)
            bytes = transform(bytes, seeds[stage], prefixes[stage], schedules[stage])
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val temp = state[i]
            state[i] = state[j]
            state[j] = temp
        }
        val output = ByteArray(input.size)
        var i = 0
        j = 0
        for (index in input.indices) {
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val temp = state[i]
            state[i] = state[j]
            state[j] = temp
            output[index] = (input[index].toInt() xor state[(state[i] + state[j]) and 0xff]).toByte()
        }
        return output
    }

    private fun transform(input: ByteArray, seed: ByteArray, prefix: ByteArray, schedule: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        input.forEachIndexed { index, byte ->
            if (index < prefix.size) output.write(prefix[index].toInt())
            val value = (byte.toInt() and 0xff) xor (seed[index % seed.size].toInt() and 0xff)
            output.write(apply(value, schedule[index % schedule.size]))
        }
        return output.toByteArray()
    }

    private fun apply(value: Int, operation: String): Int {
        val amount = operation.drop(1).toInt()
        return when (operation.first()) {
            'a' -> (value + amount) and 0xff
            's' -> (value - amount + 256) and 0xff
            'l' -> ((value shl amount) or (value ushr (8 - amount))) and 0xff
            else -> ((value ushr amount) or (value shl (8 - amount))) and 0xff
        }
    }
}
