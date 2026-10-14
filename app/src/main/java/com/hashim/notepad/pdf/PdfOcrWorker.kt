package com.hashim.notepad.pdf

import android.content.Context
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
        val notebookId = inputData.getString(KEY_NOTEBOOK_ID) ?: return Result.failure()
        val repo = NotePadRepository.get(applicationContext)
        val notebook = repo.notebooks().first().firstOrNull { it.id == notebookId } ?: return Result.failure()
        val pages = repo.pages(notebookId).first()
        val requestedPageId = inputData.getString(KEY_PAGE_ID)
        val forceRecognition = inputData.getBoolean(KEY_FORCE_RECOGNITION, false)
        val pdfPages = pages.filter {
            it.kind.db == "pdf" && it.assetId != null && (requestedPageId == null || it.id == requestedPageId)
        }
        if (pdfPages.isEmpty()) return Result.success()
        PdfOcrEngine(applicationContext).use { engine ->
            pdfPages.forEachIndexed { pageOrder, page ->
                if (isStopped) return Result.failure()
                val asset = repo.asset(page.assetId!!) ?: return Result.failure()
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
                    setProgress(Data.Builder().putInt(KEY_COMPLETED, pageOrder + 1).putInt(KEY_TOTAL, pdfPages.size).build())
                    return@forEachIndexed
                }
                val targetWidth = (page.widthPt * 300f / 72f).roundToInt().coerceIn(256, 4096)
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
                    return Result.failure()
                }
                if (words.isNotEmpty()) repo.replaceOcrWords(page.id, words, notebook.language)
                setProgress(Data.Builder().putInt(KEY_COMPLETED, pageOrder + 1).putInt(KEY_TOTAL, pdfPages.size).build())
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_NOTEBOOK_ID = "notebook_id"
        const val KEY_PAGE_ID = "page_id"
        const val KEY_FORCE_RECOGNITION = "force_recognition"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
    }
}
