package com.hashim.notepad.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hashim.notepad.data.Asset
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/** Copies a PDF into app storage and creates a notebook with one page per PDF page. */
class PdfImporter(private val context: Context, private val repo: NotePadRepository) {

    suspend fun importAsNotebook(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val assetId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "assets").apply { mkdirs() }
        val target = File(dir, "$assetId.pdf")
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { raw ->
            DigestInputStream(raw, digest).use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
        } ?: throw IllegalStateException("Cannot read $uri")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val sizes = PdfPageSource(context, target).use { it.pageSizesPt() }
        val notebookId = repo.createNotebook(name.removeSuffix(".pdf").removeSuffix(".PDF"), null)
        repo.addPdfPages(
            notebookId,
            Asset(assetId, "pdf", target.absolutePath, target.length(), sha, sizes.size, name),
            sizes,
        )
        indexTextLayer(notebookId, target)
        notebookId
    }

    /** Makes the PDF's own text layer searchable (source = "pdf"); scanned pages simply yield nothing. */
    private suspend fun indexTextLayer(notebookId: String, file: File) {
        val pages = repo.pages(notebookId).first()
        PdfPageSource(context, file).use { src ->
            for (p in pages) {
                val idx = p.pdfPageIndex ?: continue
                val text = runCatching { src.text(idx) }.getOrDefault("").trim()
                if (text.isNotEmpty()) repo.saveRecognition(p.id, "pdf", null, text)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported PDF"
    }
}
