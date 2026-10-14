package com.hashim.notepad.pdf

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hashim.notepad.data.Asset
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.OcrWord
import com.hashim.notepad.search.TextNormalizer
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfOcrInstrumentedTest {
    @Test
    fun offlineOcrFindsEnglishAndArabicWordsOnRasterPages() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val fixtures = listOf(
            Fixture(
                "scanned-english.pdf",
                "eng",
                "hello world",
                listOf("The", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "Search", "target", "hello", "world"),
            ),
            Fixture(
                "scanned-arabic.pdf",
                "ara+eng",
                "العربية",
                listOf("اختبار", "ترتيب", "العربية", "في", "ملف", "لغة", "عربية", "جميلة"),
            ),
        )

        PdfOcrEngine(context).use { engine ->
            fixtures.forEach { fixture ->
                val file = File(context.cacheDir, fixture.assetName)
                testContext.assets.open(fixture.assetName).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                PdfPageSource(context, file).use { source ->
                    assertTrue("fixture must be image-only", source.text(0).isBlank())
                    val (pageWidth, pageHeight) = source.pageSizesPt().first()
                    val targetWidth = (pageWidth * 300f / 72f).roundToInt()
                    val startedAt = SystemClock.elapsedRealtime()
                    val bitmap = source.render(0, targetWidth)
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height
                    val boxes = try {
                        engine.recognize(bitmap, fixture.language)
                    } finally {
                        bitmap.recycle()
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    val words = boxes.mapIndexed { index, box ->
                        val rect = OcrGeometry.bitmapRectToPdf(
                            box.left, box.top, box.right, box.bottom,
                            bitmapWidth, bitmapHeight,
                            pageWidth, pageHeight,
                        )
                        OcrWord("fixture-page", index, box.text, rect.left, rect.top, rect.right, rect.bottom, box.confidence)
                    }
                    val layer = OcrGeometry.toTextLayer(words)
                    val normalizedText = TextNormalizer.normalize(layer.text).text
                    val matchedWords = fixture.expectedWords.count { word ->
                        TextNormalizer.normalize(word).text in normalizedText
                    }
                    val hitRate = matchedWords.toFloat() / fixture.expectedWords.size
                    Log.i(
                        "Batch9OCR",
                        "${fixture.assetName}: words=${boxes.size}, hitRate=${"%.2f".format(hitRate)}, elapsedMs=$elapsedMs",
                    )
                    assertTrue("${fixture.assetName} produced no OCR words", boxes.isNotEmpty())
                    assertTrue(
                        "${fixture.assetName} did not find ${fixture.query}; OCR was: ${layer.text}",
                        findPdfTextHits("fixture-page", 0, layer, fixture.query).isNotEmpty(),
                    )
                    assertTrue("${fixture.assetName} hit rate was ${(hitRate * 100).toInt()}%", hitRate >= 0.5f)
                    assertTrue(words.all { it.left >= 0f && it.top >= 0f && it.right <= pageWidth && it.bottom <= pageHeight })
                    runBlocking {
                        val databaseName = "ocr-search-${fixture.assetName}.db"
                        context.deleteDatabase(databaseName)
                        val repo = NotePadRepository(context, databaseName)
                        try {
                            val pageSizes = source.pageSizesPt()
                            val asset = Asset(
                                id = UUID.randomUUID().toString(),
                                kind = "pdf",
                                localPath = file.absolutePath,
                                byteSize = file.length(),
                                sha256 = "fixture-${fixture.assetName}",
                                pageCount = pageSizes.size,
                                name = fixture.assetName,
                            )
                            val notebookId = repo.createPdfNotebook(fixture.assetName, asset, pageSizes, null)
                            val page = repo.pages(notebookId).first().first()
                            val persistedWords = words.map { it.copy(pageId = page.id) }
                            repo.replaceOcrWords(page.id, persistedWords, fixture.language)
                            val libraryHits = repo.search(fixture.query)
                            assertTrue(
                                "${fixture.assetName} was not found in Library search",
                                libraryHits.any { it.pageId == page.id && it.source == "ocr" },
                            )
                        } finally {
                            repo.helper.close()
                            context.deleteDatabase(databaseName)
                        }
                    }
                }
                file.delete()
            }
        }
    }

    private data class Fixture(
        val assetName: String,
        val language: String,
        val query: String,
        val expectedWords: List<String>,
    )
}
