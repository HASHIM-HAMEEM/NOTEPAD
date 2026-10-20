package com.hashim.notepad.pdf

import android.content.Context
import android.net.Uri
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class PdfPasswordRequiredException : IllegalArgumentException("This PDF requires a password")
class PdfPasswordIncorrectException : IllegalArgumentException("Incorrect PDF password")
class InvalidPdfException(message: String) : IllegalArgumentException(message)

/** Copies and validates a PDF before atomically creating its notebook, asset and page rows. */
class PdfImporter(private val context: Context, private val repo: NotePadRepository) {
    suspend fun importAsNotebook(
        uri: Uri,
        folderId: String? = null,
        title: String? = null,
        password: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val staged = ImportStorage(context).copy(uri, "pdf", "Imported PDF", onProgress)
        var committed = false
        try {
            validatePdf(staged.temporaryFile)
            val sizes = try {
                withContext(PdfiumDispatcher.dispatcher) {
                    PdfPageSource(context, staged.temporaryFile, password).use { it.pageSizesPt() }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (password.isNullOrEmpty() && hasEncryptionDictionary(staged.temporaryFile)) throw PdfPasswordRequiredException()
                if (!password.isNullOrEmpty()) throw PdfPasswordIncorrectException()
                throw InvalidPdfException("This PDF is damaged or unsupported")
            }
            if (sizes.isEmpty()) throw InvalidPdfException("This PDF has no readable pages")
            currentCoroutineContext().ensureActive()
            val asset = staged.promote().copy(pageCount = sizes.size)
            val notebookId = withContext(NonCancellable) {
                if (!password.isNullOrEmpty()) PdfPasswordVault(context).save(asset.id, password)
                repo.createPdfNotebook(
                    title?.trim()?.takeIf(String::isNotEmpty) ?: staged.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    asset, sizes, folderId,
                ).also { committed = true }
            }
            onProgress(ImportProgress(staged.name, 70))
            indexTextLayer(notebookId, File(asset.localPath), staged.name, onProgress)
            onProgress(ImportProgress(staged.name, 100))
            notebookId
        } catch (error: Throwable) {
            if (!committed) staged.discard()
            throw error
        }
    }

    private fun validatePdf(file: File) {
        if (file.length() < 16) throw InvalidPdfException("This file is too small to be a valid PDF")
        val head = ByteArray(5)
        file.inputStream().use {
            if (it.read(head) != head.size || String(head, Charsets.US_ASCII) != "%PDF-") {
                throw InvalidPdfException("This file is not a PDF")
            }
        }
        val tail = readTail(file, 64 * 1024)
        if (!String(tail, Charsets.ISO_8859_1).contains("%%EOF")) throw InvalidPdfException("This PDF appears incomplete or damaged")
    }

    private fun hasEncryptionDictionary(file: File) = String(readTail(file, 128 * 1024), Charsets.ISO_8859_1).contains("/Encrypt")

    private fun readTail(file: File, maximum: Int): ByteArray {
        val bytes = ByteArray(minOf(file.length(), maximum.toLong()).toInt())
        file.inputStream().use { input ->
            input.skip((file.length() - bytes.size).coerceAtLeast(0))
            var at = 0
            while (at < bytes.size) {
                val count = input.read(bytes, at, bytes.size - at)
                if (count < 0) break
                at += count
            }
        }
        return bytes
    }

    private suspend fun indexTextLayer(notebookId: String, file: File, filename: String, progress: (ImportProgress) -> Unit) {
        val pages = repo.pages(notebookId).first()
        val annotations = runCatching { PdfAnnotations.scan(file) }.getOrDefault(emptyMap())
        withContext(PdfiumDispatcher.dispatcher) {
            PdfPageSource(context, file, PdfPasswordVault(context).read(file.name.substringBefore('.'))).use { source ->
                for ((index, page) in pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val sourceIndex = page.pdfPageIndex ?: continue
                    val text = runCatching { source.text(sourceIndex) }.getOrDefault("").trim()
                    if (text.isNotEmpty()) repo.saveRecognition(page.id, "pdf", null, text)
                    val notes = annotations[sourceIndex]?.joinToString("\n").orEmpty()
                    if (notes.isNotEmpty()) repo.saveRecognition(page.id, "pdf_annot", null, notes)
                    progress(ImportProgress(filename, 70 + (index + 1) * 29 / pages.size.coerceAtLeast(1)))
                }
            }
        }
    }
}
