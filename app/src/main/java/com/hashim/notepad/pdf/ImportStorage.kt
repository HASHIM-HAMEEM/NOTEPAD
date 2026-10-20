package com.hashim.notepad.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hashim.notepad.data.Asset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportProgress(val filename: String, val percent: Int)

internal class StagedAsset(
    val id: String,
    val kind: String,
    val name: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val temporaryFile: File,
    val finalFile: File,
) {
    fun promote(): Asset {
        check(temporaryFile.renameTo(finalFile)) { "Could not finalize imported file" }
        return asset()
    }

    fun asset(): Asset = Asset(id, kind, finalFile.absolutePath, byteSize, sha256, null, name)

    fun discard() {
        temporaryFile.delete()
        finalFile.delete()
    }
}

internal class ImportStorage(private val context: Context) {
    suspend fun copy(
        uri: Uri,
        kind: String,
        fallbackName: String,
        progress: (ImportProgress) -> Unit = {},
    ): StagedAsset = withContext(Dispatchers.IO) {
        val name = displayName(uri).ifBlank { fallbackName }
        val mime = context.contentResolver.getType(uri) ?: mimeFromName(name)
        val extension = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(12)
            .ifBlank { if (kind == "pdf") "pdf" else "bin" }
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "assets").apply { mkdirs() }
        val temp = File(dir, "$id.$extension.part")
        val target = File(dir, "$id.$extension")
        val size = size(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read $uri")
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        if (size > 0) progress(ImportProgress(name, (copied * 55 / size).toInt().coerceIn(1, 55)))
                    }
                    sink.fd.sync()
                }
            }
            require(copied > 0) { "The selected file is empty" }
            progress(ImportProgress(name, 60))
            StagedAsset(
                id = id,
                kind = kind,
                name = name,
                mimeType = mime,
                byteSize = copied,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                temporaryFile = temp,
                finalFile = target,
            )
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        }
    }

    fun displayName(uri: Uri): String = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""

    private fun size(uri: Uri): Long = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.SIZE), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L } ?: -1L

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls", "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    companion object {
        fun cleanOrphans(context: Context, referencedPaths: Set<String>) {
            File(context.filesDir, "assets").listFiles().orEmpty().forEach { file ->
                if (file.name.endsWith(".part") || file.absolutePath !in referencedPaths) file.delete()
            }
        }
    }
}
