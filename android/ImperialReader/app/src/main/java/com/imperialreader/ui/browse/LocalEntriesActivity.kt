package com.imperialreader.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.R
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.ui.common.CardItem
import com.imperialreader.ui.common.MangaCardAdapter
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Browser and manager for entries stored under the configured local/ directory. */
class LocalEntriesActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, LocalEntriesActivity::class.java)
    }

    private lateinit var adapter: MangaCardAdapter
    private lateinit var emptyText: View

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        val source = DocumentFile.fromTreeUri(this@LocalEntriesActivity, uri)
                            ?: throw IOException("Could not open the selected folder")
                        val folderName = source.name?.trim().orEmpty()
                        if (folderName.isBlank()) throw IOException("The selected folder has no name")
                        val local = app.storage.localDir() ?: throw IOException("Local storage is unavailable")
                        if (local.findFile(folderName) != null) {
                            throw IOException("An entry named \"$folderName\" already exists")
                        }
                        val destination = local.createDirectory(folderName)
                            ?: throw IOException("Could not create the local entry folder")
                        try {
                            copyFolder(source, destination)
                        } catch (e: Exception) {
                            destination.delete()
                            throw e
                        }
                        LocalScanner(this@LocalEntriesActivity).scan()
                        folderName
                    }
                    reload()
                    toast("Added \"$name\"")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Could not add local entry: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_entries)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "Local"
            setNavigationOnClickListener { finish() }
        }
        emptyText = findViewById(R.id.emptyText)
        adapter = MangaCardAdapter(
            onClick = { item -> startActivity(MangaDetailsActivity.intent(this, item.tag as Long)) },
            onLongClick = { item ->
                (item.tag as? Long)?.let { id ->
                    lifecycleScope.launch {
                        app.db.mangaDao().getById(id)?.let(::showEntryActions)
                    }
                }
            }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = GridLayoutManager(this@LocalEntriesActivity, app.prefs.libraryColumns)
            adapter = this@LocalEntriesActivity.adapter
        }
        findViewById<View>(R.id.addEntry).setOnClickListener { pickFolder.launch(null) }
        findViewById<View>(R.id.scanEntries).setOnClickListener { scanLocalEntries() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                app.db.mangaDao().getBySource(LOCAL_SOURCE_ID)
            }
            adapter.submitList(entries.map { manga ->
                CardItem(
                    key = "local-${manga.id}",
                    title = manga.title,
                    cover = manga.coverUrl,
                    badge2 = if (manga.favorite) "In library" else "Local",
                    tag = manga.id
                )
            })
            emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun scanLocalEntries() {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { LocalScanner(this@LocalEntriesActivity).scan() }
                reload()
                toast("Refreshed $count local entr${if (count == 1) "y" else "ies"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Could not refresh local entries: ${e.message}")
            }
        }
    }

    private fun showEntryActions(manga: MangaEntity) {
        val actions = buildList {
            add(if (manga.favorite) "Remove from library" else "Add to library")
            add("Delete local files and entry")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(manga.title)
            .setItems(actions.toTypedArray()) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        val current = app.db.mangaDao().getById(manga.id) ?: return@launch
                        app.repo.toggleFavorite(current)
                        reload()
                    }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Delete “${manga.title}” and its files?")
                        .setMessage("This permanently deletes the entry folder under local/.")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val folder = app.storage.localDir()?.findFile(manga.url)
                                        if (folder != null && !folder.delete()) {
                                            throw IOException("Could not delete the local folder")
                                        }
                                        app.db.mangaDao().delete(manga.id)
                                    }
                                    reload()
                                    toast("Deleted local entry")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    toast("Could not delete local entry: ${e.message}")
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun copyFolder(source: DocumentFile, destination: DocumentFile) {
        source.listFiles().forEach { child ->
            val name = child.name ?: throw IOException("A selected file has no name")
            if (child.isDirectory) {
                val target = destination.createDirectory(name)
                    ?: throw IOException("Could not create folder \"$name\"")
                copyFolder(child, target)
            } else {
                val target = destination.createFile("application/octet-stream", name)
                    ?: throw IOException("Could not create file \"$name\"")
                val input = contentResolver.openInputStream(child.uri)
                    ?: throw IOException("Could not read \"$name\"")
                val output = contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Could not write \"$name\"")
                input.use { sourceStream -> output.use { targetStream -> sourceStream.copyTo(targetStream) } }
            }
        }
    }
}
