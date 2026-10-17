package com.imperialreader.data.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.imperialreader.util.NaturalOrder
import com.imperialreader.util.isImageName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** CBZ/ZIP, CBT/TAR and CBR/RAR (RAR 2.x–4.x) reading. */
object Archives {

    enum class Kind { ZIP, TAR, RAR, UNKNOWN }

    fun kind(ctx: Context, doc: DocumentFile): Kind {
        val head = ByteArray(8)
        val read = runCatching {
            ctx.contentResolver.openInputStream(doc.uri)?.use { it.read(head) } ?: -1
        }.getOrDefault(-1)
        if (read >= 4) {
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP
            if (head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte()) {
                if (read >= 7 && head[6] == 1.toByte()) {
                    throw IOException("This is a RAR5 archive, which can't be read yet. Re-save it as CBZ (or RAR4) and rescan.")
                }
                return Kind.RAR
            }
        }
        val tarMarker = ByteArray(5)
        runCatching {
            ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                if (input.skip(257) == 257L) input.read(tarMarker)
            }
        }
        if (String(tarMarker, Charsets.US_ASCII) == "ustar") return Kind.TAR
        val n = doc.name.orEmpty().lowercase()
        return when {
            n.endsWith(".cbz") || n.endsWith(".zip") -> Kind.ZIP
            n.endsWith(".cbt") || n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".cbr") || n.endsWith(".rar") -> Kind.RAR
            else -> Kind.UNKNOWN
        }
    }

    private fun flat(name: String) = name.replace('/', '_').replace('\\', '_').replace("..", "_")

    private fun skip(name: String) = !isImageName(name) || name.contains("__MACOSX") || name.substringAfterLast('/').substringAfterLast('\\').startsWith(".")

    /** Extracts every page image into [dir] (names flattened; sort naturally afterwards). */
    fun extractImages(ctx: Context, doc: DocumentFile, dir: File) {
        when (kind(ctx, doc)) {
            Kind.ZIP -> openStream(ctx, doc).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !skip(entry.name)) File(dir, flat(entry.name)).outputStream().use { zip.copyTo(it) }
                        entry = zip.nextEntry
                    }
                }
            }
            Kind.RAR -> withRar(ctx, doc) { archive ->
                for (h in imageHeaders(archive)) File(dir, flat(h.fileName)).outputStream().use { archive.extractFile(h, it) }
            }
            Kind.TAR -> extractTar(ctx, doc, dir)
            Kind.UNKNOWN -> throw IOException("Unsupported archive type")
        }
    }

    /** Writes the first page image to [target]. Returns false if none was found. */
    fun extractFirstImage(ctx: Context, doc: DocumentFile, target: File): Boolean {
        var found = false
        when (kind(ctx, doc)) {
            Kind.ZIP -> openStream(ctx, doc).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !found) {
                        if (!entry.isDirectory && !skip(entry.name)) {
                            target.outputStream().use { zip.copyTo(it) }
                            found = true
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            Kind.RAR -> withRar(ctx, doc) { archive ->
                imageHeaders(archive).firstOrNull()?.let {
                    target.outputStream().use { out -> archive.extractFile(it, out) }
                    found = true
                }
            }
            Kind.TAR -> return extractTar(ctx, doc, target.parentFile ?: return false, target)
            Kind.UNKNOWN -> {}
        }
        return found
    }

    private fun extractTar(ctx: Context, doc: DocumentFile, dir: File, target: File? = null): Boolean {
        val input = ctx.contentResolver.openInputStream(doc.uri) ?: throw IOException("Cannot open ${doc.name}")
        input.use { tar ->
            val header = ByteArray(TAR_BLOCK_SIZE)
            var found = false
            while (readBlock(tar, header)) {
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).toString(Charsets.UTF_8).substringBefore('\u0000')
                val prefix = header.copyOfRange(345, 500).toString(Charsets.UTF_8).substringBefore('\u0000')
                val entryName = if (prefix.isBlank()) name else "$prefix/$name"
                val size = parseOctal(header, 124, 12)
                if (size < 0L) throw IOException("Invalid TAR entry size")
                val type = header[156].toInt().toChar()
                val regularFile = type == '\u0000' || type == '0'
                val shouldExtract = regularFile && isImageName(entryName) &&
                    !entryName.contains("__MACOSX") &&
                    !entryName.substringAfterLast('/').substringAfterLast('\\').startsWith(".")
                if (shouldExtract) {
                    val output = target ?: File(dir, flat(entryName))
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { copyExactly(tar, it, size) }
                    found = true
                    if (target != null) return true
                } else {
                    skipExactly(tar, size)
                }
                val padding = (TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE
                skipExactly(tar, padding)
            }
            return found
        }
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw IOException("Truncated TAR header")
            }
            offset += read
        }
        return true
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = header.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            .trim('\u0000', ' ')
        if (value.isBlank()) return 0L
        return value.toLongOrNull(8) ?: -1L
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Truncated TAR entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Truncated TAR archive")
                remaining -= read
            }
        }
    }

    private fun imageHeaders(archive: Archive): List<FileHeader> =
        archive.fileHeaders.filter { !it.isDirectory && !skip(it.fileName) }
            .sortedWith(compareBy(NaturalOrder) { it.fileName })

    private fun openStream(ctx: Context, doc: DocumentFile) =
        ctx.contentResolver.openInputStream(doc.uri) ?: throw IOException("Cannot open ${doc.name}")

    private fun withRar(ctx: Context, doc: DocumentFile, block: (Archive) -> Unit) {
        val staged = createStagingFile(ctx, doc)
        try {
            // Junrar's InputStream adapter retains read blocks for random access. Stage to disk and
            // use its file-backed reader so large CBR files do not grow with the archive in heap.
            openStream(ctx, doc).use { input ->
                staged.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
            }
            Archive(staged).use(block)
        } catch (e: com.github.junrar.exception.RarException) {
            throw IOException("Can't read this RAR file (${e.message}). Re-save it as CBZ and rescan.")
        } finally {
            staged.delete()
        }
    }

    private fun createStagingFile(ctx: Context, doc: DocumentFile): File {
        var lastFailure: Exception? = null
        for (cacheRoot in listOfNotNull(ctx.externalCacheDir, ctx.cacheDir).distinct()) {
            try {
                val stagingDir = File(cacheRoot, "rar-staging")
                if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                    throw IOException("Cannot create temporary directory")
                }
                return File.createTempFile("archive-", ".rar", stagingDir)
            } catch (e: Exception) {
                lastFailure = e
            }

        }
        throw IOException("Cannot create temporary space for ${doc.name}", lastFailure)
    }

    private const val TAR_BLOCK_SIZE = 512
}
