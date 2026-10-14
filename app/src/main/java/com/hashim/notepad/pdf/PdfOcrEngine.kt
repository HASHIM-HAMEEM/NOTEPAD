package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import java.io.File

class PdfOcrEngine(private val context: Context) : AutoCloseable {
    private val api = TessBaseAPI()
    private var initializedLanguage: String? = null

    fun recognize(bitmap: Bitmap, language: String): List<OcrBox> {
        installModels()
        if (initializedLanguage != language) {
            check(api.init(context.filesDir.absolutePath, language)) { "Could not initialize OCR model: $language" }
            initializedLanguage = language
        }
        val grayscale = grayscale(bitmap)
        try {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(grayscale)
            api.getUTF8Text()
            val iterator = api.resultIterator ?: return emptyList()
            try {
                val words = mutableListOf<OcrBox>()
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                    val box = iterator.getBoundingBox(PageIteratorLevel.RIL_WORD)
                    if (text.isNotEmpty() && box != null && box.size >= 4) {
                        words += OcrBox(
                            text,
                            box[0],
                            box[1],
                            box[2],
                            box[3],
                            iterator.confidence(PageIteratorLevel.RIL_WORD).coerceIn(0f, 100f),
                        )
                    }
                } while (iterator.next(PageIteratorLevel.RIL_WORD))
                return words
            } finally {
                iterator.delete()
            }
        } finally {
            grayscale.recycle()
            api.clear()
        }
    }

    override fun close() {
        api.recycle()
    }

    private fun installModels() {
        val directory = File(context.filesDir, "tessdata")
        if (!directory.exists()) directory.mkdirs()
        listOf("eng", "ara").forEach { language ->
            val file = File(directory, "$language.traineddata")
            if (!file.exists()) {
                context.assets.open("tessdata/$language.traineddata").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun grayscale(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }
}

fun ocrLanguageForNotebook(language: String): String = when (language.lowercase().substringBefore('-')) {
    "ar" -> "ara+eng"
    "fa" -> "fas+eng"
    "ur" -> "urd+eng"
    else -> "eng"
}
