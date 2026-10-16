package com.imperialreader.ui.books

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.network.await
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.data.source.SManga
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.sanitize
import com.imperialreader.util.toast
import com.imperialreader.ui.common.LibraryActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** Shared "what do you want to do with this book" flow used by Browse → Books and Global search. */
object BookActions {

    fun show(activity: AppCompatActivity, item: BookItem) {
        activity.lifecycleScope.launch {
            val detailed = try {
                BookSources.get(item.sourceId)?.details(item) ?: item
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                item
            }
            showDetails(activity, detailed)
        }
    }

    private fun showDetails(activity: AppCompatActivity, item: BookItem) {
        val details = buildString {
            append(item.author ?: "Unknown author")
            item.artist?.takeIf { it.isNotBlank() }?.let { append("\nArtist: ").append(it) }
            item.genre?.takeIf { it.isNotBlank() }?.let { append("\nGenres/tags: ").append(it) }
            append("\nSource: ").append(item.sourceName)
            item.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(500)) }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(item.title)
            .setMessage(details)
            .apply {
                val source = BookSources.get(item.sourceId)
                if (source?.isWebNovel == true) {
                    setPositiveButton("Add to library") { _, _ -> addNovelToLibrary(activity, item) }
                } else {
                    setPositiveButton("Download & add to library") { _, _ -> addToLibrary(activity, item) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNovelToLibrary(activity: AppCompatActivity, item: BookItem) {
        activity.toast("Adding “${item.title}” to your library…")
        activity.lifecycleScope.launch {
            try {
                val app = activity.applicationContext.app
                val manga = app.repo.getOrCreate(
                    item.sourceId,
                    SManga(
                        url = item.id,
                        title = item.title,
                        thumbnailUrl = item.coverUrl,
                        author = item.author,
                        artist = item.artist,
                        description = item.description,
                        genre = item.genre
                    )
                )
                app.db.mangaDao().update(
                    manga.copy(
                        author = item.author ?: manga.author,
                        artist = item.artist ?: manga.artist,
                        description = item.description ?: manga.description,
                        genre = item.genre ?: manga.genre,
                        coverUrl = item.coverUrl ?: manga.coverUrl
                    )
                )
                if (!manga.favorite && !LibraryActions.addToLibrary(activity, manga)) return@launch
                app.repo.refresh(manga)
                activity.startActivity(MangaDetailsActivity.intent(activity, manga.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast("Could not add novel: ${e.message}")
            }
        }
    }

    private fun saveCover(ctx: Context, item: BookItem, dir: androidx.documentfile.provider.DocumentFile) {
        val cover = item.coverUrl ?: return
        if (dir.findFile("cover.jpg") != null) return
        try {
            ctx.app.network.client.newCall(Request.Builder().url(cover).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val c = dir.createFile("image/jpeg", "cover.jpg")
                    if (c != null) ctx.contentResolver.openOutputStream(c.uri, "w")?.use { resp.body!!.byteStream().copyTo(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun addToLibrary(activity: AppCompatActivity, item: BookItem) {
        val app = activity.applicationContext.app
        val queueKey = "epub:${item.sourceId}:${item.key}"
        activity.lifecycleScope.launch {
            try {
                val candidate = MangaEntity(source = item.sourceId, url = item.id, title = item.title)
                if (!LibraryActions.confirmLibraryEntry(activity, candidate)) return@launch
                activity.toast("Downloading “${item.title}”…")
                app.downloads.reportExternalDownload(queueKey, item.title, "Downloading EPUB")
                val id = withContext(Dispatchers.IO) { download(activity.applicationContext, item) }
                activity.startActivity(MangaDetailsActivity.intent(activity, id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast("Download failed: ${e.message}")
            } finally {
                app.downloads.removeExternalDownload(queueKey)
            }
        }
    }

    /** Saves the EPUB as local/<Title>/<Title>.epub, rescans, and returns the library entry id. */
    private suspend fun download(ctx: Context, item: BookItem): Long {
        val app = ctx.app
        val local = app.storage.localDir() ?: throw IOException("Storage is not available")
        val folderName = item.title.sanitize().take(100)
        val dir = app.storage.findOrCreateDir(local, folderName) ?: throw IOException("Cannot create folder")

        val url = (BookSources.get(item.sourceId) ?: throw IOException("Unknown catalog")).resolveEpubUrl(item)
        val fileName = "$folderName.epub"
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("application/epub+zip", fileName) ?: throw IOException("Cannot create file")

        app.network.client.await(Request.Builder().url(url).build()).use { resp ->
            if (!resp.isSuccessful) {
                file.delete()
                throw IOException("HTTP ${resp.code}")
            }
            val out = ctx.contentResolver.openOutputStream(file.uri, "w") ?: throw IOException("Cannot write file")
            out.use { resp.body!!.byteStream().copyTo(it) }
        }
        if (file.length() < 1024) {
            file.delete()
            throw IOException("The download was empty")
        }

        saveCover(ctx, item, dir)

        LocalScanner(ctx).scan()
        return app.db.mangaDao().find(LOCAL_SOURCE_ID, folderName)?.id
            ?: throw IOException("Downloaded, but the library could not import it")
    }
}
