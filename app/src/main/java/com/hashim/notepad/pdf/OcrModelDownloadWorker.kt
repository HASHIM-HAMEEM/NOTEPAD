package com.hashim.notepad.pdf

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrModelSpec(
    val code: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
)

object OcrModelCatalog {
    val optionalModels = mapOf(
        "fas" to OcrModelSpec(
            code = "fas",
            displayName = "Farsi",
            sizeBytes = 3_325_955,
            sha256 = "99e420969b5ddd2cb135b416316a7ed417c59c4faf9e0d28941348f6448114df",
            url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/fas.traineddata",
        ),
        "urd" to OcrModelSpec(
            code = "urd",
            displayName = "Urdu",
            sizeBytes = 7_994_323,
            sha256 = "65af3f6e50a3c3df15d7b405aab5d467e53433207745c8f5be5b8ff1a58a67c8",
            url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/urd.traineddata",
        ),
    )

    fun optionalCodeFor(language: String): String? =
        ocrLanguageForNotebook(language).substringBefore('+').takeIf { it in optionalModels }
}

class OcrModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val spec = OcrModelCatalog.optionalModels[inputData.getString(KEY_MODEL)] ?: return Result.failure()
        val directory = File(applicationContext.filesDir, "tessdata")
        if (!directory.exists() && !directory.mkdirs()) return Result.failure()
        val destination = File(directory, "${spec.code}.traineddata")
        if (destination.isFile && sha256(destination) == spec.sha256) return Result.success()

        val partial = File(directory, "${spec.code}.traineddata.part")
        return try {
            withContext(Dispatchers.IO) {
                download(spec, partial)
                if (partial.length() != spec.sizeBytes) return@withContext Result.retry()
                if (sha256(partial) != spec.sha256) {
                    partial.delete()
                    return@withContext Result.failure()
                }
                destination.delete()
                if (!partial.renameTo(destination)) return@withContext Result.failure()
                Result.success()
            }
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure()
        }
    }

    private suspend fun download(spec: OcrModelSpec, partial: File) {
        val existingBytes = partial.length()
        val connection = URL(spec.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Range", "bytes=$existingBytes-")
            val response = connection.responseCode
            if (response == 416 && existingBytes == spec.sizeBytes) return
            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                if (response == 429 || response >= 500) throw IOException("Retryable OCR model response: $response")
                throw IllegalStateException("OCR model request failed: $response")
            }
            val append = response == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0
            if (!append) partial.delete()
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = if (append) existingBytes else 0L
                    while (true) {
                        if (isStopped) throw IOException("OCR model download stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_SIZE to spec.sizeBytes))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_DOWNLOADED = "downloaded_bytes"
        const val KEY_SIZE = "size_bytes"
    }
}
