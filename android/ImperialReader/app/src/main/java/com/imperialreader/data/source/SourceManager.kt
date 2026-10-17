package com.imperialreader.data.source

import android.content.Context
import com.imperialreader.util.app

class SourceManager(private val context: Context) {

    /**
     * Built-in sites: dedicated scrapers (MangaDex, MangaKatana, MangaPill, MangaBuddy) plus WordPress sites that auto-detect the Madara or MangaThemesia engine. Sites move/die often, so treat these as a starting point:
     * disable broken ones in Browse → Sources (long-press) or add your own with "Add custom source".
     */
    private val builtin: List<Source> = listOf(
        MangaDexSource(),
        MangaKSource(),
        MangaKatanaSource(),
        MangaPillSource(),
        MangaBuddySource(),
        ThunderScansSource(),
        HentaiDbSource(),
        NhentaiXxxSource(),
        NineHentaiSource(),
        ImhentaiXxxSource(),
        DoujinsSource(),
        EHentaiSource(),
        TapasSource(),
        ComicHubFreeSource(),
        ReadComicsOnlineSource(),
        // Confirmed against the live site: WordPress + MangaThemesia (/manga/?order=update, chapters at /<slug>-chapter-N/)
        MangaThemesiaSource("KingOfShojo", "https://kingofshojo.com"),
        NightScanSource(),
        WitchToonsSource(),
        WordPressSource(
            "PhiliaScans",
            "https://philiascans.org",
            idOverride = "wp_philiascans_com"
        ),
        WordPressSource("RavenScans", "https://ravenscans.com"),
        StoneScapeSource(),
        WordPressSource("Cocomic", "https://cocomic.co", idOverride = "wp_cocomics_com"),
        WordPressSource("RokariComics", "https://rokaricomics.com"),
        // Added on request (engine is auto-detected; sites without a known engine use the generic scraper)
        Manhwa18Source(),
        DemonicScansSource(),
        AsuraScansSource(),
        WordPressSource("ManhwaRead", "https://manhwaread.com"),
        HentaraSource(),
        Manga18Source("Manga18 ME", "https://manga18.me"),
        MangaFireSource(),
        // Western comics
        WordPressSource("TritiniaScans", "https://tritinia.org"),
        WordPressSource("VortexScans", "https://vortexscans.org"),
        // 18+ (hidden until enabled in Settings)
        WordPressSource("ManhuaUS", "https://manhuaus.com", nsfw = true)
    )

    private fun custom(): List<Source> =
        context.app.prefs.customMadara.mapNotNull { entry ->
            val name = entry.substringBefore('|')
            val url = entry.substringAfter('|', "")
            if (name.isBlank() || !url.startsWith("http")) null else WordPressSource(name, url.trimEnd('/'))
        }

    fun all(): List<Source> = (builtin + custom()).distinctBy { it.id }

    /** Sources the user may see (18+ ones only when enabled in Settings). */
    fun visible(): List<Source> {
        val showAdult = context.app.prefs.showNsfwExtensions
        return all().filter { showAdult || !it.nsfw }
    }

    fun enabled(): List<Source> {
        val disabled = context.app.prefs.disabledSources
        return visible().filter { it.id !in disabled }
    }

    private val combined = CombinedSource()

    fun get(id: String): Source? =
        if (id == CombinedSource.ID) combined else all().firstOrNull { it.id == id }

    fun isCustom(id: String): Boolean = builtin.none { it.id == id }

    fun setEnabled(id: String, enabled: Boolean) {
        val p = context.app.prefs
        p.disabledSources = if (enabled) p.disabledSources - id else p.disabledSources + id
    }

    /** Accepts "example.com", "https://example.com/some/page" … any domain (.com, .org, .cc …). */
    fun normalizeBaseUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isBlank()) return null
        if (!u.contains("://")) u = "https://$u"
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || !uri.host.contains('.')) return null
        return "${uri.scheme}://${uri.authority}"
    }

    /** false = the site is already built in. Adding the same site again replaces the older custom entry. */
    fun addCustom(name: String, rawUrl: String): Boolean {
        val url = normalizeBaseUrl(rawUrl) ?: return false
        val host = hostId(url, name)
        if (builtin.any { it is HttpSource && hostId(it.baseUrl, it.name) == host }) return false
        val p = context.app.prefs
        p.customMadara = p.customMadara
            .filterNot { hostId(it.substringAfter('|', ""), "") == host }
            .toSet() + "${name.replace("|", " ")}|$url"
        return true
    }

    fun removeCustom(id: String) {
        val p = context.app.prefs
        p.customMadara = p.customMadara.filterNot { entry ->
            val name = entry.substringBefore('|')
            val url = entry.substringAfter('|', "")
            WordPressSource(name, url.trimEnd('/')).id == id
        }.toSet()
    }
}
