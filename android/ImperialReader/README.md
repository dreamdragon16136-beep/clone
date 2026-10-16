# Imperial Reader

A Mihon/Tachiyomi-style manga reader for Android (Kotlin, Views + Material 3, Room, OkHttp, Jsoup, Coil).
Black-and-gold "Imperial" theme, Mihon layout: **Library · Updates · History · Browse · More**.

## Features

| Area | What you get |
|---|---|
| Library | Cover grid, unread / downloaded / Local badges, search, include / exclude / off filters for unread, downloaded, and bookmarked entries, sort, pull-to-refresh library update, automatic library update toggle with startup or periodic frequencies, automatic cover refresh, bulk mark-as-read and optional download deletion on removal, 2–5 columns |
| Auto global search | Searching the library with no match + pressing search opens **Global search** (library first, then sources with bounded concurrent search, title/genre matching, and relevance ranking) |
| Updates / History | New-chapter feed, read-history with resume, clear/remove |
| Browse | **Sources** (Popular / Latest / title and genre search, infinite scroll, add any site by URL) and **Extensions** = Local library manager plus EPUB / web-novel catalogs (Project Gutenberg, Internet Archive, Royal Road, Tapas, NovelArrow, Ranobes, and more); catalog search has a separate title/genre toggle |
| Built-in sources | MangaDex (official API), Tapas Comics with full paginated episode lists, imhentai.xxx (18+), and a list of Madara-theme sites (see "Sources" below) |
| Details | Cover/info header, add to library, chapter list with read / bookmark / download state, Start / Resume / Read again |
| Reader | Left-to-right, right-to-left, vertical webtoon, tap zones, seek bar, prev/next chapter, volume keys, progress + history saving, fullscreen, keep-screen-on |
| Local library | Import a manga folder from **Browse → Extensions → Local**, or use **More → Scan local library**. Reads image folders, CBZ, ZIP, CBR/RAR, PDF, and EPUB chapters. Refresh detects added, renamed, and removed chapter files in the configured `local/` folder. |
| EPUB / web novels | Light-novel reading controls, left-to-right page turns by tap or swipe, NCX/EPUB3 contents titles, section seek/progress, font size, dark/sepia/light themes, and text-to-speech |
| PDF | Local PDFs open in a paged reader with light/dark mode, page seeking, previous/next controls, and persisted page progress |
| Downloads | Foreground-service queue, 3 parallel pages, stored in `downloads/<Source (LANG)>/<Manga>/<Chapter>/001.jpg` |
| Cloudflare | In-app WebView shares cookies with the HTTP client: open a catalog/source in the browser, solve the challenge, then retry |

## Build it on GitHub (no PC tooling needed)

1. Create an empty repository on github.com (e.g. `imperial-reader`).
2. Upload the contents of this folder (keep `.github/workflows/build.yml`).
   From a terminal (works in Termux too):
   ```bash
   cd ImperialReader
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<you>/imperial-reader.git
   git push -u origin main
   ```
3. Open the **Actions** tab → *Build APK* runs automatically (or press **Run workflow**).
4. When it finishes (~5–8 min), open the run → **Artifacts → ImperialReader-debug-apk** → download, unzip, install `app-debug.apk`.
5. To publish a release APK on the Releases page: `git tag v1.0.0 && git push origin v1.0.0`.

The workflow installs JDK 17 and Gradle 8.2 itself, so the missing `gradle-wrapper.jar` is not a problem.

## Build locally with Android Studio

1. Install **Android Studio Hedgehog (2023.1.1) or newer** (it bundles JDK 17).
2. *File → Open* → select the `ImperialReader` folder. Let Gradle sync (it downloads Gradle 8.2, Android Gradle Plugin 8.2.2, Kotlin 1.9.22 and the dependencies).
3. If Studio says the Android SDK is missing, install **SDK Platform 34** from *Tools → SDK Manager*.
4. Plug in your phone (USB debugging on) and press **Run ▶**, or build an APK with
   *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
   Output: `app/build/outputs/apk/debug/app-debug.apk`.
5. If Studio complains about the Gradle wrapper, either set *Settings → Build Tools → Gradle → Use Gradle from: specified location* (Gradle 8.2), or run once in a terminal: `gradle wrapper --gradle-version 8.2`.

## Build from a terminal (Linux / macOS / Termux-proot)

```bash
# needs: JDK 17, Android SDK (platform 34 + build-tools 34.0.0), Gradle 8.2
export ANDROID_HOME=$HOME/Android/Sdk          # path to your SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleDebug --stacktrace
```

## Using the local library

```
<your storage folder>/
  downloads/              (created automatically)
  local/
    Berserk/
      cover.jpg           (optional)
      Chapter 001.cbz
      Chapter 002.pdf
    Some Novel/
      Volume 1.epub
    Loose Pages Manga/
      Chapter 1/          (folder of images = one chapter)
        01.jpg  02.jpg …
  autobackup/             (created automatically, reserved for backups)
```
More → Storage folder → pick the folder → More → Scan local library. Alternatively, use Browse → Extensions → Local → Add local folder to copy a manga folder into `local/`; use **Refresh local entries** after changing files outside the app. Long-press a local entry to remove it from the library or permanently delete its local files. Local entries are added to the Library with a "Local" badge. CBR/RAR is not supported (convert to CBZ).

## Sources

Built in:

* **KingOfShojo** (https://kingofshojo.com) uses the MangaThemesia engine directly (site structure confirmed live; it also carries some mature titles).
* **Dedicated scrapers:** MangaDex (official API, most reliable), **Thunderscans** (catalog search, comics browsing, unlocked chapters, and embedded reader images confirmed against the live site), **Manhwa18** (live chapter images and listings), **DemonicScans**, **MangaFire** (filter search, AJAX chapters and page lists), **NightScan** (public catalog API, paginated chapters, and validation against actual page records rather than its stale page counts), **WitchToons** (paginated catalog, title search, chapter lists, and reader images), **Hentara**, **Manga18.ME**, **AsuraScans**, **MangaKatana** (URL patterns confirmed against the live site), **MangaPill** and **MangaBuddy** (written from the sites' known layouts, not verified live).
* **StoneScape** uses its public catalog API for series listings, search, chapters, and reader pages.
* **WordPress sites (auto-detecting):** each one looks at the site's home page the first time you use it and picks the **Madara**, **MangaThemesia**, or generic site engine automatically. Toonily, LuminousScans, PhiliaScans (philiascans.org), RavenScans, Cocomics, RokariComics, ManhwaRead, Manga18.club, TritiniaScans, VortexScans.

Scanlation domains change or die constantly and several sit behind Cloudflare, so expect some to fail. Long-press a source in *Browse → Sources* to disable it, or use **Add custom source** to add any WordPress manga site by URL (Madara or MangaThemesia is detected for you). Custom sources are included in manual and automatic backups and are queried by Global search just like built-in sources. A site on any other engine needs its own small class: copy `MangaKatanaSource.kt` as the template.

## Newer features

* **Combined titles:** Global search groups the same title from every source into ONE row with all chapters merged (most up-to-date count, newest first). Reading picks the first source that has the chapter and falls back to the next automatically. Untick "Combine…" for per-source rows; "Show only sources with results" hides empty sources.
* **EPUBs and novels in search:** Global search searches EPUB catalogs and web-novel sources, including NovelArrow and Ranobes. Genre terms are searched alongside titles; per-source requests are bounded so a slow source cannot hold the entire search indefinitely.
* **Web novels (QuickNovel-style):** Browse → Extensions lists the novel catalogs, including NovelPing, NovelArrow, LunarX, Ranobes, and Tapas Novels. Tap a novel → *Download as EPUB* builds an EPUB from readable chapters. Newly published chapters are included in library refreshes and appear in Updates after the initial chapter sync. LunarX results and chapter lists use its public novel API; titles without published English chapters are reported as unavailable rather than represented by empty chapters.
* **EPUB reader:** section seek/progress, contents list, font size, left-to-right paged reading, dark / sepia / light themes, **read-aloud (text-to-speech)** that continues into the next section.
* **Library:** categories, filters, sorts, mark whole entry read / unread, badge toggles, and automatic updates that can run at app start or every 30 minutes, hour, 12 hours, 24 hours, or 7 days.
* **Backup and restore:** More → Backup and restore saves/loads a JSON file (library, categories, read progress, bookmarks, history, app preferences, custom sources added by URL, and reader preferences including per-title modes and EPUB theme). Automatic backups include the same data. Restore merges library data without losing read state and restores backed settings. Device-specific storage access and automatic-backup status are not transferred.
* **Local library:** CBZ/ZIP, **CBR/RAR (RAR 2–4)**, PDF, EPUB and image folders. Covers are extracted (cover.jpg → first page → PDF/EPUB cover) and refresh automatically when the files change; switch off in Settings → Local library.
* **Duplicates:** same entry through another URL form = one row (newer data overwrites). Adding a title you already have from another source asks first. Adding the same site twice replaces the older custom entry.
* **Look:** gold star icon, slide/fade transitions, and a little spinning star while searching or updating.

## 18+ content

* Off by default. Turn on **Settings → Browse → Show 18+ sources and extensions** (an age confirmation appears).
* When on, 18+ sources appear in Browse and Global search (marked "18+"): Toonily, NightScan, ManhuaUS, Manhwa18, Hentara, Manga18.club, Manga18.ME — and **MangaDex also starts returning its erotica / pornographic-rated titles** (official API, the most reliable adult source here).
* Turning it off hides them again; entries already in your library keep working.

### Sites not available as built-in scrapers

* **MangaFox / FanFox** — page images are delivered as packed, per-page JavaScript with keys; same problem.
* **MangaDemon** — custom layout not confirmed; guessing selectors would just give you a broken source.
They exist as Mihon extensions in the Extensions tab, but this app cannot run extension code (see below). If you paste the site URL, I can inspect it and write a proper scraper class for it.

## Known limitations (be aware before relying on it)

* **Mihon/Keiyoushi APK extensions are not supported.** The Extensions tab now lists EPUB / web-novel catalogs instead. Imperial Reader does not execute Tachiyomi extension code.
* **RAR5** archives (newer WinRAR default) can't be read; the app tells you to re-save as CBZ. RAR 2–4 works.
* **ManhwaRead**, **Manga18.club**, **DemonicScans**, and **MangaFire** may require a Cloudflare check. If a request is blocked, open the source site in the in-app browser and complete its challenge before retrying.
* **Novel site addresses** in `BookSources.kt` are from memory and several use the generic HTML scraper. Sites with custom layouts or anti-bot challenges may require opening them in WebView; Ranobes is behind a Cloudflare/browser check in some environments. Paginated chapter text is followed only when the source exposes same-chapter page links, and unreadable or blocked pages are reported instead of returned as empty content. Anna's Archive was left out: it is an index of pirated downloads, not a readable source.
* No pinch-zoom in the paged reader yet (fit-to-screen only).
* Download queue is in memory: if Android kills the app mid-queue, re-queue the remaining chapters.
* Source scrapers cannot be unit-tested offline; the first run on a real device is the real test.

## Project layout

```
app/src/main/java/com/imperialreader/
  App.kt                      manual DI container + Coil image loader
  data/    db/ (Room)  source/ (Source API, Madara, MangaDex)  repo/  storage/  download/  extension/  network/
  ui/      main/ library/ updates/ history/ browse/ details/ reader/ more/ webview/ common/
  util/Utils.kt
```
