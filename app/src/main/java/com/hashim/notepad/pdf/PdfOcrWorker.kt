package com.hashim.notepad.pdf

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.hashim.notepad.data.OcrWord
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class PdfOcrWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notebookId = inputData.getString(KEY_NOTEBOOK_ID) ?: return failure("Missing notebook id")
        val repo = NotePadRepository.get(applicationContext)
        val notebook = repo.notebooks().first().firstOrNull { it.id == notebookId }
            ?: return failure("Notebook not found: $notebookId")
        val pages = repo.pages(notebookId).first()
        val requestedPageId = inputData.getString(KEY_PAGE_ID)
        val forceRecognition = inputData.getBoolean(KEY_FORCE_RECOGNITION, false)
        val pdfPages = pages.filter {
            it.kind.db == "pdf" && it.assetId != null && (requestedPageId == null || it.id == requestedPageId)
        }
        if (pdfPages.isEmpty()) return failure("No PDF pages available for OCR in notebook=$notebookId")
        Log.i(TAG, "Starting OCR notebook=$notebookId language=${notebook.language} pages=${pdfPages.size} pageId=$requestedPageId attempt=$runAttemptCount abis=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        var recognizedWords = 0
        var recognizedPages = 0
        try {
            PdfOcrEngine(applicationContext).use { engine ->
            pdfPages.forEachIndexed { pageOrder, page ->
                if (isStopped) return failure("OCR stopped before page=${page.id}")
                val assetId = page.assetId ?: return failure("PDF page=${page.id} has no asset")
                val asset = repo.asset(assetId) ?: return failure("PDF asset not found id=$assetId page=${page.id}")
                val pageIndex = page.pdfPageIndex ?: pages.indexOf(page)
                val embedded = withContext(PdfiumDispatcher.dispatcher) {
                    PdfPageSource(
                        applicationContext,
                        File(asset.localPath),
                        PdfPasswordVault(applicationContext).read(asset.id),
                    ).use { it.text(pageIndex) }
                }
                val scannedPage = OcrGeometry.isScannedPage(embedded)
                val hasOcrWords = scannedPage && repo.ocrWords(page.id).isNotEmpty()
                if (PdfOcrPolicy.shouldSkipPage(scannedPage, hasOcrWords, forceRecognition)) {
                    val existingWords = repo.ocrWords(page.id)
                    recognizedWords += existingWords.size
                    if (existingWords.isNotEmpty()) recognizedPages++
                    setProgress(Data.Builder().putInt(KEY_COMPLETED, pageOrder + 1).putInt(KEY_TOTAL, pdfPages.size).build())
                    return@forEachIndexed
                }
                val targetDpi = PdfOcrPolicy.renderDpi(notebook.language)
                val targetWidth = (page.widthPt * targetDpi / 72f).roundToInt().coerceIn(256, 4096)
                Log.i(TAG, "Rendering OCR page=${page.id} width=$targetWidth dpi=$targetDpi")
                val bitmap = withContext(PdfiumDispatcher.dispatcher) {
                    PdfPageSource(
                        applicationContext,
                        File(asset.localPath),
                        PdfPasswordVault(applicationContext).read(asset.id),
                    ).use { it.render(pageIndex, targetWidth) }
                }
                val bitmapWidth = bitmap.width
                val bitmapHeight = bitmap.height
                val boxes = try {
                    withContext(Dispatchers.Default) { engine.recognize(bitmap, ocrLanguageForNotebook(notebook.language)) }
                } finally {
                    bitmap.recycle()
                }
                val words = boxes.mapIndexedNotNull { index, box ->
                    if (box.text.isBlank()) return@mapIndexedNotNull null
                    val rect = OcrGeometry.bitmapRectToPdf(
                        box.left, box.top, box.right, box.bottom, bitmapWidth, bitmapHeight,
                        page.widthPt, page.heightPt,
                    )
                    OcrWord(page.id, index, box.text, rect.left, rect.top, rect.right, rect.bottom, box.confidence)
                }
                if (PdfOcrPolicy.shouldFailWhenNoWords(requestedPageId != null, words.size)) {
                    return Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR, "No text found on the requested page")
                            .putInt(KEY_RECOGNIZED_WORDS, 0)
                            .build(),
                    )
                }
                if (words.isNotEmpty()) {
                    repo.replaceOcrWords(page.id, words, notebook.language)
                    recognizedWords += words.size
                    recognizedPages++
                }
                Log.i(TAG, "OCR page=${page.id} words=${words.size} language=${notebook.language}")
                setProgress(Data.Builder().putInt(KEY_COMPLETED, pageOrder + 1).putInt(KEY_TOTAL, pdfPages.size).build())
            }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "OCR failed notebook=$notebookId pageId=$requestedPageId language=${notebook.language}", error)
            return Result.failure(
                Data.Builder().putString(KEY_ERROR, error.message ?: error.javaClass.simpleName).build(),
            )
        }
        return Result.success(
            Data.Builder()
                .putInt(KEY_RECOGNIZED_WORDS, recognizedWords)
                .putInt(KEY_RECOGNIZED_PAGES, recognizedPages)
                .build(),
        )
    }

    companion object {
        private const val TAG = "PdfOcrWorker"
        const val KEY_NOTEBOOK_ID = "notebook_id"
        const val KEY_PAGE_ID = "page_id"
        const val KEY_FORCE_RECOGNITION = "force_recognition"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_RECOGNIZED_WORDS = "recognized_words"
        const val KEY_RECOGNIZED_PAGES = "recognized_pages"
    }

    private fun failure(message: String): Result {
        Log.e(TAG, message)
        return Result.failure(Data.Builder().putString(KEY_ERROR, message).build())
    }
}
