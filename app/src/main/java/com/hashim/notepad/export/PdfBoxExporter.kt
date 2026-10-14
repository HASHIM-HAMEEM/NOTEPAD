package com.hashim.notepad.export

import android.content.Context
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vector-first PDF exporter. Imported source pages are copied as PDF page objects and marks
 * remain real text-markup annotations; the legacy canvas exporter is used by the caller only
 * when PdfBox cannot parse a source document.
 */
class PdfBoxExporter(private val context: Context, private val repo: NotePadRepository) {
    suspend fun exportNotebook(notebookId: String, title: String): File = withContext(Dispatchers.IO) {
        val pages = repo.pages(notebookId).first()
        val marks = repo.pdfMarks(notebookId).first()
        val sources = HashMap<String, PDDocument>()
        val output = PDDocument()
        try {
            pages.forEach { page ->
                val target: PDPage
                if (page.kind == PageKind.PDF && page.assetId != null) {
                    val asset = repo.asset(page.assetId) ?: error("Missing PDF asset")
                    val source = sources.getOrPut(asset.id) { PDDocument.load(File(asset.localPath)) }
                    target = output.importPage(source.getPage(page.pdfPageIndex ?: 0))
                } else {
                    target = PDPage(PDRectangle(page.widthPt, page.heightPt))
                    output.addPage(target)
                }
                target.rotation = page.rotation
                val pageMarks = marks.filter { it.pageId == page.id }
                if (pageMarks.isNotEmpty()) {
                    val annotations = target.annotations
                    pageMarks.forEach { mark ->
                        val quads = PdfMarkGeometry.rotatedQuads(mark.quads, page.widthPt, page.heightPt, page.rotation)
                        val bounds = PdfMarkGeometry.quads(quads).let { boxes ->
                            if (boxes.isEmpty()) null else com.tom_roush.pdfbox.pdmodel.common.PDRectangle(
                                boxes.minOf { it.left }, boxes.minOf { it.top },
                                boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
                            )
                        }
                        val annotation = PDAnnotationTextMarkup(PdfMarkGeometry.toMarkupSubtype(mark.kind))
                        annotation.quadPoints = quads.toFloatArray()
                        if (bounds != null) annotation.rectangle = bounds
                        annotation.color = PDColor(
                            floatArrayOf(
                                android.graphics.Color.red(mark.colorArgb) / 255f,
                                android.graphics.Color.green(mark.colorArgb) / 255f,
                                android.graphics.Color.blue(mark.colorArgb) / 255f,
                            ),
                            com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE,
                        )
                        annotation.contents = mark.note ?: mark.text
                        annotations.add(annotation)
                    }
                }
            }
            val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" }
            val result = File(outDir, "$safe.pdf")
            val temporary = File(outDir, "$safe.pdf.part")
            output.save(temporary)
            check(temporary.renameTo(result)) { "Could not commit export" }
            result
        } finally {
            output.close()
            sources.values.forEach { it.close() }
        }
    }
}
