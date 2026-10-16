package com.imperialreader.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.util.app
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Dialog flows shared by Details, Global search and Library. */
object LibraryActions {

    /**
     * Adds [manga] to the library: warns if the same title is already there (from any source),
     * then asks for a category when categories exist. Returns false if the user backed out.
     */
    suspend fun addToLibrary(ctx: Context, manga: MangaEntity): Boolean {
        val app = ctx.app
        if (!confirmLibraryEntry(ctx, manga)) return false
        val hasCategories = app.db.categoryDao().getAll().isNotEmpty()
        val category = if (hasCategories) (chooseCategory(ctx, 0L) ?: return false) else 0L
        app.repo.toggleFavorite(manga)
        app.db.mangaDao().setCategory(manga.id, category)
        return true
    }

    /** true = go ahead (no duplicate, or the user chose "Add anyway"). */
    suspend fun confirmLibraryEntry(ctx: Context, manga: MangaEntity): Boolean {
        val duplicates = ctx.app.repo.findDuplicates(manga)
        if (duplicates.isEmpty()) return true
        val lines = duplicates.joinToString("\n") {
            "• ${it.title} (${ctx.app.sources.get(it.source)?.name ?: it.source})"
        }
        return suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Already in your library")
                .setMessage("This title is already saved:\n\n$lines\n\nAdd this copy as well?")
                .setPositiveButton("Add anyway") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }

    /** Returns the chosen category id (0 = Default) or null if dismissed. */
    suspend fun chooseCategory(ctx: Context, currentId: Long): Long? {
        val categories = ctx.app.db.categoryDao().getAll()
        if (categories.isEmpty()) return 0L
        val names = arrayOf("Default") + categories.map { it.name }
        val ids = listOf(0L) + categories.map { it.id }
        return suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Category")
                .setSingleChoiceItems(names, ids.indexOf(currentId).coerceAtLeast(0)) { d, which ->
                    d.dismiss()
                    if (cont.isActive) cont.resume(ids[which])
                }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            dialog.show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }
    }
}
