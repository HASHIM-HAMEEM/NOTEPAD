package com.hashim.notepad.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.hashim.notepad.data.Asset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedPageImage(val asset: Asset, val widthPx: Int, val heightPx: Int)

class PageImageImporter(private val context: Context) {
    suspend fun import(uri: Uri): ImportedPageImage = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read $uri")
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Cannot decode image")
        val alpha = bitmap.hasAlpha()
        val id = UUID.randomUUID().toString()
        val extension = if (alpha) "png" else "jpg"
        val dir = File(context.filesDir, "assets").apply { mkdirs() }
        val temporary = File(dir, "$id.$extension.part")
        val target = File(dir, "$id.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            temporary.outputStream().use { raw ->
                DigestOutputStream(raw, digest).use { output ->
                    val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    check(bitmap.compress(format, if (alpha) 100 else 90, output)) { "Could not encode image" }
                }
            }
            check(temporary.renameTo(target)) { "Could not finalize image import" }
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Image"
        ImportedPageImage(
            Asset(id, "image", target.absolutePath, target.length(), sha, null, name),
            options.outWidth.takeIf { it > 0 } ?: (bounds.outWidth / sample),
            options.outHeight.takeIf { it > 0 } ?: (bounds.outHeight / sample),
        )
    }

    companion object {
        private const val MAX_SIDE = 2048

        fun fitToA4(widthPx: Int, heightPx: Int): FloatArray {
            val scale = minOf(595f / widthPx.coerceAtLeast(1), 842f / heightPx.coerceAtLeast(1))
            val width = widthPx * scale
            val height = heightPx * scale
            return floatArrayOf((595f - width) / 2f, (842f - height) / 2f, width, height)
        }
    }

}
