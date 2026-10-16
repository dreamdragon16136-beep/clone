package com.imperialreader.data.repo

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.HistoryEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.util.app
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MangaRepository(private val ctx: Context) {

    private val db get() = ctx.app.db

    /** Returns the stored row for a source entry, creating a (non-library) row if needed. */
    suspend fun getOrCreate(sourceId: String, s: SManga): MangaEntity {
        val dao = db.mangaDao()
        // Same entry reached through a slightly different URL (http/https, www, trailing slash) = same row.
        val wanted = canonicalUrl(s.url)
        val existing = dao.find(sourceId, s.url)
            ?: dao.getBySource(sourceId).firstOrNull { canonicalUrl(it.url) == wanted }
        if (existing != null) {
            // newer data overwrites the old row instead of creating a second copy
            val updated = existing.copy(
                url = s.url,
                title = s.title.ifBlank { existing.title },
                coverUrl = s.thumbnailUrl ?: existing.coverUrl
            )
            if (updated != existing) dao.update(updated)
            return updated
        }
        dao.insert(
            MangaEntity(
                source = sourceId, url = s.url, title = s.title, author = s.author, artist = s.artist,
                description = s.description, genre = s.genre, status = s.status, coverUrl = s.thumbnailUrl
            )
        )
        return dao.find(sourceId, s.url)!!
    }

    /**
     * One library entry that merges the same title from several sources. Identity = normalised title,
     * so later searches that find extra sources simply add them to the existing entry.
     */
    suspend fun getOrCreateCombined(title: String, members: List<CombinedSource.Member>, coverUrl: String?): MangaEntity {
        val dao = db.mangaDao()
        val key = normalizeTitle(title)
        val existing = dao.getCombined().firstOrNull { normalizeTitle(it.title) == key }
        if (existing != null) {
            val union = CombinedSource.decode(existing.url) + members
            val updated = existing.copy(url = CombinedSource.encode(union), coverUrl = existing.coverUrl ?: coverUrl)
            if (updated != existing) dao.update(updated)
            return updated
        }
        dao.insert(MangaEntity(source = CombinedSource.ID, url = CombinedSource.encode(members), title = title, coverUrl = coverUrl))
        return dao.getCombined().first { normalizeTitle(it.title) == key }
    }

    /** Library entries with the same title coming from a different source/row. */
    suspend fun findDuplicates(manga: MangaEntity): List<MangaEntity> {
        val key = normalizeTitle(manga.title)
        if (key.isBlank()) return emptyList()
        return db.mangaDao().getFavoritesList().filter { it.id != manga.id && normalizeTitle(it.title) == key }
    }

    suspend fun toggleFavorite(manga: MangaEntity): Boolean {
        val newState = !manga.favorite
        db.mangaDao().setFavorite(manga.id, newState, if (newState) System.currentTimeMillis() else 0L)
        return newState
    }

    /** Refreshes only an entry's cover, leaving chapter state untouched. */
    suspend fun refreshCover(manga: MangaEntity): Boolean {
        if (manga.source == LOCAL_SOURCE_ID || BookSources.get(manga.source) != null) return false
        val source = ctx.app.sources.get(manga.source)
            ?: throw IOException("Source '${manga.source}' is not available")
        val details = source.getMangaDetails(
            SManga(
                url = manga.url,
                title = manga.title,
                thumbnailUrl = manga.coverUrl,
                author = manga.author,
                artist = manga.artist,
                description = manga.description,
                genre = manga.genre,
                status = manga.status
            )
        )
        val coverUrl = details.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
        if (coverUrl == manga.coverUrl) return false
        db.mangaDao().update(manga.copy(coverUrl = coverUrl))
        return true
    }

    /** Re-fetches details + chapter list from the source. Returns the number of NEW chapters. */
    suspend fun refresh(manga: MangaEntity): Int {
        if (manga.source == LOCAL_SOURCE_ID) return 0
        val bookSource = BookSources.get(manga.source)
        if (bookSource?.isWebNovel == true) {
            val item = BookSources.itemFor(manga) ?: throw IOException("Book entry is unavailable")
            val details = bookSource.details(item)
            val updatedManga = manga.copy(
                author = details.author ?: manga.author,
                artist = details.artist ?: manga.artist,
                description = details.description ?: manga.description,
                genre = details.genre ?: manga.genre,
                coverUrl = details.coverUrl ?: manga.coverUrl,
                initialized = true,
                lastUpdate = System.currentTimeMillis()
            )
            db.mangaDao().update(updatedManga)
            val sourceChapters = bookSource.chapters(details)
            if (sourceChapters.isEmpty()) {
                throw IOException("${bookSource.name} returned no chapters; the existing chapter list was kept")
            }
            if (sourceChapters.any { it.title.isBlank() || it.url.isBlank() }) {
                throw IOException("${bookSource.name} returned invalid chapter entries; the existing chapter list was kept")
            }
            val chapters = sourceChapters
                .asReversed()
                .mapIndexed { index, chapter ->
                    SChapter(
                        url = chapter.url,
                        name = chapter.title,
                        chapterNumber = index + 1f
                    )
                }
            return syncChapters(updatedManga.id, chapters, trackUpdates = true)
        }
        val source = ctx.app.sources.get(manga.source)
            ?: throw IOException("Source '${manga.source}' is not available")
        val input = SManga(
            url = manga.url, title = manga.title, thumbnailUrl = manga.coverUrl, author = manga.author,
            artist = manga.artist, description = manga.description, genre = manga.genre, status = manga.status
        )
        val details = source.getMangaDetails(input)
        db.mangaDao().update(
            manga.copy(
                title = details.title.ifBlank { manga.title },
                author = details.author ?: manga.author,
                artist = details.artist ?: manga.artist,
                description = details.description ?: manga.description,
                genre = details.genre ?: manga.genre,
                status = details.status,
                coverUrl = details.thumbnailUrl ?: manga.coverUrl,
                initialized = true,
                lastUpdate = System.currentTimeMillis()
            )
        )
        val chapters = source.getChapters(details)
        if (chapters.isEmpty()) {
            throw IOException("${source.name} returned no chapters; the existing chapter list was kept")
        }
        if (chapters.any { it.name.isBlank() || it.url.isBlank() }) {
            throw IOException("${source.name} returned invalid chapter entries; the existing chapter list was kept")
        }
        return syncChapters(manga.id, chapters, trackUpdates = true)
    }

    /**
     * Merges a fresh chapter list into the database while keeping read/bookmark/download state.
     * [list] must be ordered newest first.
     */
    suspend fun syncChapters(mangaId: Long, list: List<SChapter>, trackUpdates: Boolean): Int {
        val dao = db.chapterDao()
        val existing = dao.getAll(mangaId)
        val byUrl = existing.associateBy { it.url }
        val firstSync = existing.isEmpty()
        val now = System.currentTimeMillis()
        val toInsert = mutableListOf<ChapterEntity>()
        val toUpdate = mutableListOf<ChapterEntity>()
        list.distinctBy { it.url }.forEachIndexed { index, c ->
            val number = if (c.chapterNumber >= 0f) c.chapterNumber else parseChapterNumber(c.name)
            val old = byUrl[c.url]
            if (old == null) {
                toInsert.add(
                    ChapterEntity(
                        mangaId = mangaId, url = c.url, name = c.name, scanlator = c.scanlator,
                        dateUpload = c.dateUpload,
                        dateFetch = if (firstSync || !trackUpdates) 0L else now,
                        chapterNumber = number, sourceOrder = index
                    )
                )
            } else {
                toUpdate.add(
                    old.copy(
                        sourceOrder = index,
                        name = c.name,
                        dateUpload = c.dateUpload,
                        chapterNumber = number,
                        scanlator = c.scanlator
                    )
                )
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        if (toUpdate.isNotEmpty()) dao.updateAll(toUpdate)
        if (list.isNotEmpty() && db.mangaDao().getById(mangaId)?.source == LOCAL_SOURCE_ID) {
            dao.deleteMissingForManga(mangaId, list.map { it.url }.distinct())
        }
        return if (firstSync) 0 else toInsert.size
    }

    /** Refreshes every library entry (4 at a time). Returns the total number of new chapters. */
    suspend fun updateLibrary(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFailure: (manga: MangaEntity, error: Exception) -> Unit = { manga, error ->
            Log.w(TAG, "Could not update '${manga.title}'", error)
        }
    ): Int = coroutineScope {
        LocalScanner(ctx).scan()
        val favorites = db.mangaDao().getFavorites().filter { it.source != LOCAL_SOURCE_ID }
        val sem = Semaphore(4)
        val done = AtomicInteger(0)
        favorites.map { m ->
            async {
                sem.withPermit {
                    val n = try {
                        refresh(m)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailure(m, e)
                        0
                    }
                    onProgress(done.incrementAndGet(), favorites.size)
                    n
                }
            }
        }.awaitAll().sum()
    }

    suspend fun recordHistory(chapterId: Long) {
        if (ctx.app.prefs.incognito) return
        db.withTransaction {
            val mangaId = db.chapterDao().getById(chapterId)?.mangaId ?: return@withTransaction
            db.historyDao().deleteForManga(mangaId)
            db.historyDao().upsert(HistoryEntity(chapterId = chapterId, lastRead = System.currentTimeMillis()))
        }
    }

    private fun canonicalUrl(url: String): String =
        url.lowercase().substringAfter("://").removePrefix("www.").trimEnd('/')

    private companion object {
        const val TAG = "MangaRepository"
    }
}
