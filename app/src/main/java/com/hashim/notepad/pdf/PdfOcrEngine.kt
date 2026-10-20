package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import java.io.File

class PdfOcrEngine(private val context: Context) : AutoCloseable {
    private val api = TessBaseAPI()
    private var initializedLanguage: String? = null

    fun recognize(bitmap: Bitmap, language: String): List<OcrBox> {
        installModels()
        val grayscale = preprocess(grayscale(bitmap))
        try {
            val arabicPass = recognizeBest(grayscale, language)
            val urduAvailable = File(File(context.filesDir, "tessdata"), "urd.traineddata").isFile
            if (!PdfOcrPolicy.shouldRetryWithUrdu(language, arabicPass, urduAvailable)) return arabicPass
            val urduPass = runCatching { recognizeBest(grayscale, "urd+eng") }.getOrElse { error ->
                Log.w(TAG, "Urdu fallback failed", error); return arabicPass
            }
            Log.i(TAG, "Arabic pass weak (${arabicPass.size} words); Urdu pass returned ${urduPass.size} words")
            return if (PdfOcrPolicy.preferUrdu(arabicPass, urduPass)) urduPass else arabicPass
        } finally {
            grayscale.recycle()
            api.clear()
        }
    }

    private fun ensureLanguage(language: String) {
        if (initializedLanguage != language) {
            val initialized = try {
                api.init(context.filesDir.absolutePath, language)
            } catch (error: Throwable) {
                Log.e(TAG, "Tesseract initialization threw for language=$language, abis=${Build.SUPPORTED_ABIS.joinToString()}", error)
                throw IllegalStateException("Could not initialize OCR model: $language", error)
            }
            if (!initialized) {
                val error = IllegalStateException("Tesseract returned false while initializing language=$language")
                Log.e(TAG, "Tesseract initialization failed for abis=${Build.SUPPORTED_ABIS.joinToString()}", error)
                throw error
            }
            initializedLanguage = language
            Log.i(TAG, "Initialized Tesseract language=$language, abis=${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun recognizeBest(grayscale: Bitmap, language: String): List<OcrBox> {
        ensureLanguage(language)
        val automatic = recognizeWithMode(grayscale, TessBaseAPI.PageSegMode.PSM_AUTO)
        if (!PdfOcrPolicy.shouldRetryArabicWithSingleBlock(language, automatic)) return automatic
        val singleBlock = recognizeWithMode(grayscale, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        Log.i(TAG, "Arabic OCR PSM_AUTO returned too few words; PSM_SINGLE_BLOCK returned ${singleBlock.size} words")
        return if (PdfOcrPolicy.preferSingleBlock(automatic, singleBlock)) singleBlock else automatic
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
                Log.i(TAG, "Installing OCR model language=$language from app assets")
                context.assets.open("tessdata/$language.traineddata").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Log.i(TAG, "OCR model language=$language bytes=${file.length()} exists=${file.isFile}")
        }
    }

    private fun recognizeWithMode(bitmap: Bitmap, mode: Int): List<OcrBox> {
        api.setPageSegMode(mode)
        api.setImage(bitmap)
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
    }

    /** Deskew (always) and Otsu-binarise (scan-like pages only); see [OcrPreprocess]. Recycles [source] when it returns a new bitmap. */
    private fun preprocess(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val lum = IntArray(pixels.size) { pixels[it] and 0xFF }
        val plan = OcrPreprocess.plan(lum, width, height)
        Log.i(TAG, "OCR preprocess skew=${plan.skewDegrees} binarize=${plan.binarize} threshold=${plan.threshold}")
        if (plan.binarize) {
            for (i in pixels.indices) pixels[i] = if (lum[i] < plan.threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            source.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        if (plan.skewDegrees == 0f) return source
        val rotated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.rotate(-plan.skewDegrees, width / 2f, height / 2f)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return rotated
    }

    private fun grayscale(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private companion object {
        const val TAG = "PdfOcrEngine"
    }
}

fun ocrLanguageForNotebook(language: String): String = when (language.lowercase().substringBefore('-')) {
    "ar" -> "ara+eng"
    "fa" -> "fas+eng"
    "ur" -> "urd+eng"
    else -> "eng"
}
