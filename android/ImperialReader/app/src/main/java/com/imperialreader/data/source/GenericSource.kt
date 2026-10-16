package com.imperialreader.data.source

import com.imperialreader.data.network.CloudflareException
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

/**
 * Best-effort scraper for sites that use neither Madara nor MangaThemesia (custom engines, Astro/Next sites,
 * comic blogs …). It finds listing pages, series cards, chapter links and page images by pattern instead of
 * by site-specific selectors, so it works on many sites but is not guaranteed on any given one.
 */
class GenericSource(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
    override val nsfw: Boolean = false,
    private val novel: Boolean = false
) : HttpSource() {

    override val id: String = "generic_" + hostId(baseUrl, name)

    @Volatile private var listPattern: String? = null
    @Volatile private var latestPattern: String? = null
    @Volatile private var searchPattern: String? = null
    @Volatile private var genrePattern: String? = null

    private val novelList = listOf(
        "/popular?page={p}", "/most-popular?page={p}", "/novel-list?page={p}", "/novels?page={p}", "/novel?page={p}",
        "/list/best-rated?page={p}", "/ranking?page={p}", "/library?page={p}"
    )
    private val listCandidates = (if (novel) novelList else emptyList()) + listOf(
        "/series?sort=popular&page={p}", "/webtoons?page={p}", "/manga?page={p}", "/manga/page/{p}", "/comics?page={p}", "/browse?page={p}",
        "/series?page={p}", "/manga-list?page={p}", "/comic-list?page={p}", "/all-comics?page={p}",
        "/page/{p}", "/?page={p}", "/?paged={p}"
    )
    private val latestCandidates = listOf("/latest?page={p}", "/latest-updates?page={p}", "/latest/page/{p}", "/page/{p}")
    private val searchCandidates = (if (novel) listOf(
        "/search?keyword={q}&page={p}", "/search?q={q}&page={p}", "/search?query={q}&page={p}",
        "/search?s={q}&page={p}", "/search?name={q}&page={p}", "/?s={q}&paged={p}"
    ) else emptyList()) + listOf(
        "/?s={q}&paged={p}", "/search?q={q}&page={p}", "/search?keyword={q}&page={p}",
        "/search?s={q}&page={p}", "/search?query={q}&page={p}", "/?s={q}&post_type=wp-manga",
        "/series?search={q}&page={p}", "/series?q={q}&page={p}", "/search?name={q}&page={p}"
    )

    private val badPath = Regex(
        "(?i)(^|/)(genres?|tags?|categor(y|ies)|page|login|register|chapters?|episodes?|issues?|authors?|search|feed|" +
            "privacy-policy|terms|dmca|contact-us|about-us|bookmarks|wp-content|wp-json|cdn-cgi|completed|raw" +
            (if (novel) "" else "|novels?|ebooks?") + ")(/|$)"
    )
    private val chapterRegex = Regex("(?i)(chapter|chap\\.?|ch\\.?|episode|ep\\.?|issue|#)\\s*[-_ ]?\\d")
    private val chapterHref = Regex("(?i)[-/_](chapter|chap|episode|ep|issue|ch)[-_/]?\\d")

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.lowercase()

    private suspend fun doc(url: String): Document = Jsoup.parse(getString(url), url)

    // ------------------------------------------------------------------ listings

    override suspend fun getPopular(page: Int): MangasPage = discoverList(page, latest = false)
    override suspend fun getLatest(page: Int): MangasPage = discoverList(page, latest = true)

    private suspend fun discoverList(page: Int, latest: Boolean): MangasPage {
        val known = if (latest) latestPattern ?: listPattern else listPattern
        if (known != null) {
            val result = cardsOf(page, known)
            if (result.mangas.isNotEmpty() || page > 1) return result
            if (latest && latestPattern != null && known == latestPattern) latestPattern = null else listPattern = null
        }
        val candidates = if (latest) latestCandidates + listCandidates else listCandidates
        var last: Exception? = null
        var fallbackPattern: String? = null
        var fallbackResult: MangasPage? = null
        for (c in candidates) {
            try {
                val result = cardsOf(1, c)
                if (result.mangas.isNotEmpty() && fallbackResult == null) {
                    fallbackPattern = c
                    fallbackResult = result
                }
                if (result.mangas.size >= 4) {
                    if (latest && c in latestCandidates) latestPattern = c else listPattern = c
                    return if (page == 1) result else cardsOf(page, c)
                }
            } catch (e: CloudflareException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        if (fallbackResult != null) {
            val pattern = fallbackPattern!!
            if (latest && pattern in latestCandidates) latestPattern = pattern else listPattern = pattern
            return if (page == 1) fallbackResult else cardsOf(page, pattern)
        }
        throw last ?: IOException("No series listing found on this site")
    }

    private suspend fun cardsOf(page: Int, pattern: String): MangasPage {
        val url = baseUrl.trimEnd('/') + pattern.replace("{p}", page.toString())
        val cards = try {
            parseCards(doc(url))
        } catch (e: HttpStatusException) {
            if (e.code == 404 && page > 1) emptyList() else throw e
        }
        return MangasPage(cards, cards.isNotEmpty())
    }

    /** Series cards = same-site links that wrap a cover image; the most common first path segment wins. */
    private fun parseCards(doc: Document): List<SManga> {
        val site = host(baseUrl)
        data class Card(val segment: String, val manga: SManga)
        val all = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.isBlank() || host(href) != site) return@mapNotNull null
            val path = runCatching { URI(href).path }.getOrNull().orEmpty().trim('/')
            if (path.isBlank() || path.count { it == '/' } > 2 || badPath.containsMatchIn(path)) return@mapNotNull null
            if (a.parents().any { it.tagName() in setOf("nav", "header", "footer") }) return@mapNotNull null
            val img = a.selectFirst("img")
            if (img == null && !novel) return@mapNotNull null
            val title = listOf(a.attr("title"), img?.attr("alt").orEmpty(), a.text(), a.parent()?.selectFirst("h1,h2,h3,h4,.title")?.text().orEmpty())
                .map { it.trim() }.firstOrNull { it.length >= 2 } ?: return@mapNotNull null
            Card(path.substringBefore('/'), SManga(url = href, title = title.take(150), thumbnailUrl = img?.let(::lazyImage)))
        }
        val dominant = all.groupBy { it.segment }.maxByOrNull { it.value.size }?.key ?: return emptyList()
        return all.filter { it.segment == dominant }.map { it.manga }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): MangasPage {
        val terms = Regex("""[\p{L}\p{N}]+""").findAll(query.lowercase())
            .map { normalizeTitle(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (terms.isEmpty()) return MangasPage(emptyList(), false)

        fun matchingResults(result: MangasPage): MangasPage {
            val matches = result.mangas.filter { manga ->
                val title = normalizeTitle(manga.title)
                terms.all(title::contains)
            }
            return MangasPage(matches, result.hasNextPage && matches.isNotEmpty())
        }

        suspend fun run(pattern: String): MangasPage {
            val url = baseUrl.trimEnd('/') + pattern.replace("{q}", enc(query)).replace("{p}", page.toString())
            val pageDoc = doc(url)
            return matchingResults(MangasPage(parseCards(pageDoc), true))
        }
        var last: Exception? = null
        var successfulResponse = false
        searchPattern?.let { pattern ->
            try {
                val result = run(pattern)
                successfulResponse = true
                if (result.mangas.isNotEmpty()) return result
                searchPattern = null
            } catch (e: CloudflareException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                searchPattern = null
            }
        }
        for (c in searchCandidates) {
            try {
                val result = run(c)
                successfulResponse = true
                if (result.mangas.isNotEmpty()) {
                    searchPattern = c
                    return result
                }
            } catch (e: CloudflareException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        if (successfulResponse) return MangasPage(emptyList(), false)
        throw last ?: IOException("Search failed on this site")
    }

    override suspend fun searchByGenre(query: String, page: Int): MangasPage {
        val slug = query.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        if (slug.isBlank()) return MangasPage(emptyList(), false)

        val known = genrePattern
        if (known != null) {
            val result = genreCards(query, slug, page, known)
            if (result.mangas.isNotEmpty() || page > 1) return result
            genrePattern = null
        }

        val candidates = listOf(
            "/genre/{g}/page/{p}", "/genres/{g}/page/{p}",
            "/tag/{g}/page/{p}", "/tags/{g}/page/{p}",
            "/genre/{g}?page={p}", "/tag/{g}?page={p}"
        )
        for (pattern in candidates) {
            try {
                val result = genreCards(query, slug, 1, pattern)
                if (result.mangas.isNotEmpty()) {
                    genrePattern = pattern
                    return if (page == 1) result else genreCards(query, slug, page, pattern)
                }
            } catch (e: CloudflareException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw e
            }
        }
        return super.searchByGenre(query, page)
    }

    private suspend fun genreCards(query: String, slug: String, page: Int, pattern: String): MangasPage {
        val url = baseUrl.trimEnd('/') + pattern
            .replace("{g}", enc(slug))
            .replace("{p}", page.toString())
        val candidates = parseCards(doc(url))
        val limiter = kotlinx.coroutines.sync.Semaphore(4)
        val matches = coroutineScope {
            candidates.map { candidate ->
                async {
                    try {
                        limiter.withPermit {
                            getMangaDetails(candidate).takeIf {
                                normalizeTitle(it.genre.orEmpty()).contains(normalizeTitle(query))
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
        return MangasPage(matches, pattern.contains("{p}") && matches.isNotEmpty())
    }

    // ------------------------------------------------------------------ details & chapters

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val d = doc(manga.url)
        manga.title = d.selectFirst("h1")?.text()?.trim()?.takeIf { it.length >= 2 }
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() }
            ?: manga.title
        manga.thumbnailUrl = d.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() } ?: manga.thumbnailUrl
        manga.description = d.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.length > 30 }
            ?: d.select("p").map { it.text().trim() }.filter { it.length > 80 }.maxByOrNull { it.length }
            ?: manga.description
        val selectedAreas = d.select(
            ".infox, .infotable, .post-content_item, .series-info, .manga-info, .book-info, .entry-content, .summary_content"
        )
        val detailAreas = if (selectedAreas.isEmpty()) d.select("main, article, body") else selectedAreas
        val genreLinks = detailAreas.select(
            ".genres a, .genre a, .tags a, .tag a, .manga-genres a, .series-genres a, " +
                ".genres-content a, .mgen a, .seriestugenre a, [itemprop=genre]"
        ) + d.select("[itemprop=genre]")
        val labeledGenres = detailAreas.select("li, p, div, span, td, tr").filter { element ->
            val text = element.ownText().ifBlank { element.text() }.trim()
            text.startsWith("Genre", true) || text.startsWith("Tag", true)
        }.flatMap { it.select("a, [itemprop=genre]") }
        manga.genre = (genreLinks + labeledGenres)
            .filter { link -> link.parents().none { it.tagName() in setOf("nav", "header", "footer") } }
            .map { it.text().trim().trim(',') }
            .filter { it.isNotBlank() && it.length < 50 }.distinct().take(20)
            .joinToString(", ").ifBlank { null }
        fun labeled(label: String): String? = detailAreas.select("li, p, div, span, td, tr").asSequence()
            .filter { item -> item.parents().none { it.tagName() in setOf("nav", "header", "footer") } }
            .map { it.ownText().ifBlank { it.text() }.trim() }
            .firstOrNull { it.length < 140 && it.startsWith(label, ignoreCase = true) && it.length > label.length }
            ?.substring(label.length)?.trim(' ', ':', '-')?.ifBlank { null }
        manga.author = labeled("Author") ?: labeled("Authors")
        manga.artist = labeled("Artist")
        manga.status = statusFromText(labeled("Status").orEmpty())
        return manga
    }

    override suspend fun getChapters(manga: SManga): List<SChapter> {
        val site = host(manga.url)
        val mangaPath = runCatching { URI(manga.url).path }.getOrNull().orEmpty().trimEnd('/')
        val found = LinkedHashMap<String, SChapter>()

        fun collect(d: Document) {
            for (a in d.select("a[href]")) {
                val href = a.absUrl("href")
                if (href.isBlank() || host(href) != site) continue
                val path = runCatching { URI(href).path }.getOrNull().orEmpty().trimEnd('/')
                if (path == mangaPath || path.isBlank()) continue
                val text = a.text().trim()
                if (!(chapterRegex.containsMatchIn(text) || chapterHref.containsMatchIn(path))) continue
                if (a.parents().any { it.tagName() in setOf("nav", "header", "footer") }) continue
                val name = text.ifBlank { a.attr("title").trim() }.ifBlank { path.substringAfterLast('/').replace('-', ' ') }
                val dateText = Regex("""\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},\s+\d{4}""").find(a.parent()?.text().orEmpty())?.value.orEmpty()
                found.putIfAbsent(
                    href,
                    SChapter(url = href, name = name.take(120), dateUpload = parseSiteDate(dateText), chapterNumber = parseChapterNumber(name))
                )
            }
        }

        var d = doc(manga.url)
        collect(d)
        if (novel) {
            val visited = mutableSetOf(d.location())
            var pages = 1
            while (true) {
                val next = nextNovelChapterPage(d, site) ?: break
                if (host(next) != site) throw IOException("Novel chapter pagination left the source site")
                if (!visited.add(next)) throw IOException("Novel chapter pagination contains a loop")
                if (pages >= MAX_NOVEL_CHAPTER_PAGES) {
                    throw IOException("Novel chapter list exceeds the safe limit of $MAX_NOVEL_CHAPTER_PAGES pages")
                }
                d = doc(next)
                collect(d)
                pages++
            }
        }
        // newest first: highest chapter number first, unnumbered ones keep page order at the end
        val list = found.values.toList()
        if (list.isEmpty()) throw IOException("No chapters could be read from this site")
        return list.filter { it.chapterNumber >= 0f }.sortedByDescending { it.chapterNumber } + list.filter { it.chapterNumber < 0f }
    }

    private fun nextNovelChapterPage(doc: Document, site: String?): String? {
        val currentUrl = doc.location()
        val current = runCatching { URI(currentUrl) }.getOrNull() ?: return null
        val identity = chapterPageIdentity(current) ?: return null
        val currentPage = paginationNumber(currentUrl) ?: 1
        return doc.select(
            "a[rel=next][href], ul.pagination a[href], .pagination a[href], .page-numbers[href], " +
                "nav[aria-label] a[href], .pages a[href], a[aria-label][href]"
        ).asSequence()
            .filter { anchor ->
                val label = "${anchor.text()} ${anchor.attr("aria-label")} ${anchor.className()}"
                val nav = anchor.parents().firstOrNull { it.tagName() == "nav" }
                val isPageLink = anchor.attr("rel").contains("next", ignoreCase = true) ||
                    anchor.hasClass("next") || anchor.hasClass("page-numbers") ||
                    label.contains("next", ignoreCase = true) ||
                    anchor.parents().any { it.hasClass("pagination") || it.hasClass("pages") } ||
                    nav?.attr("aria-label")?.contains("pagination", ignoreCase = true) == true
                isPageLink && (nav == null || nav.attr("aria-label").isBlank() ||
                    nav.attr("aria-label").contains("pagination", ignoreCase = true))
            }
            .mapNotNull { anchor ->
                val uri = runCatching { URI(anchor.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
                if (host(uri.toString()) != site || chapterPageIdentity(uri) != identity) return@mapNotNull null
                val number = paginationNumber(uri.toString()) ?: return@mapNotNull null
                if (number <= currentPage) return@mapNotNull null
                number to uri.toString()
            }
            .minByOrNull { it.first }?.second
    }

    private fun paginationNumber(url: String): Int? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val query = uri.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to it.substringAfter('=', "").toIntOrNull()
        }
        query["page"]?.let { return it }
        query["paged"]?.let { return it }
        query["p"]?.let { return it }
        return Regex("""(?:^|/)page/(\d+)(?:/|$)""")
            .find(uri.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Readable HTML of a novel chapter: the container with the most paragraph text. */
    suspend fun chapterText(url: String): String {
        val current = runCatching { URI(url) }.getOrNull()
            ?: throw IOException("Invalid novel chapter address")
        val site = current.host?.removePrefix("www.")?.lowercase()
            ?: throw IOException("Invalid novel chapter address")
        val visited = mutableSetOf<String>()
        val pages = mutableListOf<String>()
        var nextUrl: String? = url
        var pageCount = 0
        while (nextUrl != null) {
            if (host(nextUrl) != site) throw IOException("Novel chapter pagination left the source site")
            if (!visited.add(nextUrl)) throw IOException("Novel chapter pagination contains a loop")
            if (++pageCount > MAX_NOVEL_TEXT_PAGES) {
                throw IOException("Novel chapter exceeds the safe limit of $MAX_NOVEL_TEXT_PAGES pages")
            }
            val d = doc(nextUrl)
            val next = nextNovelTextPage(d, nextUrl)
            d.select("script, style, noscript, iframe, nav, header, footer, aside, form, .ads, .ad, [class*=advert], [id*=advert]").remove()
            val best = d.select("div, article, section, main").maxByOrNull { el ->
                el.children().asSequence().filter { it.tagName() == "p" }.sumOf { it.text().length }
            } ?: throw IOException("Couldn't find the chapter text on this page")
            if (best.select("p").size < 2 || best.text().isBlank()) {
                throw IOException("Couldn't find readable chapter text on this page")
            }
            pages += best.html()
            nextUrl = next
        }
        if (pages.isEmpty()) throw IOException("The source returned no readable chapter pages")
        return pages.joinToString("\n")
    }

    private fun nextNovelTextPage(doc: Document, currentUrl: String): String? {
        val current = runCatching { URI(currentUrl) }.getOrNull() ?: return null
        val currentIdentity = chapterPageIdentity(current) ?: return null
        val currentNumber = chapterPageNumber(current) ?: 1
        val candidates = doc.select(
            "a[rel=next][href], .chapter-pagination a[href], .chapter-content-pagination a[href], " +
                ".pagination a[href], .page-links a[href], a.next_page[href], a[aria-label][href]"
        ).asSequence()
            .filter { anchor ->
                val label = "${anchor.text()} ${anchor.attr("aria-label")} ${anchor.className()}"
                anchor.hasAttr("rel") && anchor.attr("rel").contains("next", ignoreCase = true) ||
                    anchor.hasClass("next") || anchor.hasClass("next_page") ||
                    label.contains("next page", ignoreCase = true) ||
                    anchor.parents().any { it.hasClass("pagination") || it.hasClass("chapter-pagination") || it.hasClass("page-links") }
            }
            .map { it.absUrl("href") }
            .mapNotNull { raw -> runCatching { URI(raw) }.getOrNull() }
            .filter { uri ->
                uri.host?.removePrefix("www.")?.equals(current.host?.removePrefix("www."), true) == true &&
                    chapterPageIdentity(uri) == currentIdentity &&
                    (chapterPageNumber(uri) ?: 1) > currentNumber
            }
            .distinctBy { it.toString() }
            .minByOrNull { chapterPageNumber(it) ?: Int.MAX_VALUE }
        return candidates?.toString()
    }

    private fun chapterPageIdentity(uri: URI): String? {
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isBlank()) return null
        val cleanPath = path.replace(Regex("""(?i)/(?:page|pages)/\d+$"""), "")
        val query = uri.rawQuery.orEmpty().split('&')
            .filterNot { it.substringBefore('=').lowercase() in setOf("page", "paged", "p", "page_num") }
            .sorted()
            .joinToString("&")
        return "${uri.host?.removePrefix("www.")?.lowercase()}:$cleanPath?$query"
    }

    private fun chapterPageNumber(uri: URI): Int? {
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            if (key in setOf("page", "paged", "p", "page_num")) value.toIntOrNull() else null
        }.firstOrNull()
        if (query != null) return query
        return Regex("""(?i)/(?:page|pages)/(\d+)/?$""")
            .find(uri.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }

    // ------------------------------------------------------------------ pages

    override suspend fun getPages(chapter: SChapter): List<String> {
        val html = getString(chapter.url)
        val d = Jsoup.parse(html, chapter.url)
        val bad = Regex("(?i)(logo|banner|avatar|icon|sprite|/ads?[/_-]|loading|placeholder|thumb|emoji|gravatar)")
        val imgExt = Regex("(?i)\\.(jpe?g|png|webp|gif|avif)(\\?.*)?$")

        fun good(img: Element): String? {
            val u = lazyImage(img) ?: return null
            if (!imgExt.containsMatchIn(u) || bad.containsMatchIn(u) || bad.containsMatchIn(img.className())) return null
            if (img.parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }) return null
            return u
        }

        // the container holding the most qualifying images is the reader
        val byParent = d.select("img").mapNotNull { img -> good(img)?.let { img.parent() to it } }
            .groupBy({ it.first }, { it.second })
        val best = byParent.maxByOrNull { it.value.size }?.value.orEmpty()
        if (best.size >= 2) return best.distinct()

        // fallback: image URLs embedded in scripts, keeping the most common folder
        val urls = Regex("https?:\\\\?/\\\\?/[^\"'\\s\\\\]+?\\.(?:jpe?g|png|webp|avif)", RegexOption.IGNORE_CASE)
            .findAll(d.select("script").joinToString("\n") { it.data() })
            .map { it.value.replace("\\/", "/") }.filter { !bad.containsMatchIn(it) }.toList()
        val folder = urls.groupBy { it.substringBeforeLast('/') }.maxByOrNull { it.value.size }?.value.orEmpty()
        if (folder.size >= 2) return folder.distinct()
        return best
    }

    private companion object {
        const val MAX_NOVEL_CHAPTER_PAGES = 1000
        const val MAX_NOVEL_TEXT_PAGES = 100
    }
}
