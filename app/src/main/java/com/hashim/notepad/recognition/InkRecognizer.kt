package com.hashim.notepad.recognition

import androidx.ink.strokes.Stroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.tasks.await

/**
 * On-device handwriting recognition over our vector strokes via ML Kit Digital Ink.
 * Language tags: "ar", "ur", "en" (model ~20 MB each, downloaded once on demand).
 */
class InkRecognizer {
    private val recognizers = HashMap<String, DigitalInkRecognizer>()

    suspend fun ensureModel(language: String): Boolean {
        val model = modelFor(language) ?: return false
        val manager = RemoteModelManager.getInstance()
        if (manager.isModelDownloaded(model).await()) return true
        manager.download(model, DownloadConditions.Builder().build()).await()
        return true
    }

    suspend fun isModelDownloaded(language: String): Boolean {
        val model = modelFor(language) ?: return false
        return RemoteModelManager.getInstance().isModelDownloaded(model).await()
    }

    /** Recognises all strokes of a page as one block. Coordinates are page points; [pageWidth]/[pageHeight] help line segmentation. */
    suspend fun recognize(strokes: List<Stroke>, language: String, pageWidth: Float, pageHeight: Float, preContext: String = ""): String {
        if (strokes.isEmpty()) return ""
        val model = modelFor(language) ?: return ""
        val recognizer = recognizers.getOrPut(language) {
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        }
        val ink = Ink.builder().apply {
            for (s in strokes) {
                val b = Ink.Stroke.builder()
                val inputs = s.inputs
                for (i in 0 until inputs.size) {
                    val p = inputs[i]
                    b.addPoint(Ink.Point.create(p.x, p.y, p.elapsedTimeMillis))
                }
                addStroke(b.build())
            }
        }.build()
        val ctx = RecognitionContext.builder().setPreContext(preContext.takeLast(20)).setWritingArea(WritingArea(pageWidth, pageHeight)).build()
        val result = recognizer.recognize(ink, ctx).await()
        return result.candidates.firstOrNull()?.text ?: ""
    }

    private fun modelFor(language: String): DigitalInkRecognitionModel? {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(language) ?: return null
        return DigitalInkRecognitionModel.builder(id).build()
    }

    companion object { val LANGUAGES = listOf("ar" to "العربية", "ur" to "اردو", "en" to "English") }
}
