package com.hashim.notepad.export

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

internal const val BUNDLE_MANIFEST_ENTRY = "manifest.json"
internal const val BUNDLE_FORMAT = "notepad-bundle"

enum class ImportFileKind {
    PDF,
    NOTEPAD_BUNDLE,
    ZIP,
    OTHER,
}

object BundleFileClassifier {
    fun classify(fileName: String?, input: InputStream): ImportFileKind {
        val stream = PushbackInputStream(input.buffered(), 5)
        val header = ByteArray(5)
        val headerSize = stream.read(header)
        if (headerSize > 0) stream.unread(header, 0, headerSize)

        val isPdf = headerSize >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-"
        if (isPdf || fileName.orEmpty().endsWith(".pdf", ignoreCase = true)) return ImportFileKind.PDF

        val zipSignature = headerSize >= 4 &&
            header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() &&
            (header[2] == 3.toByte() && header[3] == 4.toByte() ||
                header[2] == 5.toByte() && header[3] == 6.toByte() ||
                header[2] == 7.toByte() && header[3] == 8.toByte())
        val bundleName = fileName.orEmpty().endsWith(".notepad.zip", ignoreCase = true)
        if (!zipSignature) return if (bundleName) ImportFileKind.NOTEPAD_BUNDLE else ImportFileKind.OTHER

        if (hasBundleManifest(stream)) return ImportFileKind.NOTEPAD_BUNDLE
        return if (bundleName) ImportFileKind.NOTEPAD_BUNDLE else ImportFileKind.ZIP
    }

    private fun hasBundleManifest(input: InputStream): Boolean {
        ZipInputStream(input).use { zip ->
            var scannedBytes = 0
            val buffer = ByteArray(4096)
            while (true) {
                val entry = zip.nextEntry ?: return false
                if (entry.name == BUNDLE_MANIFEST_ENTRY && !entry.isDirectory) {
                    val manifest = ByteArrayOutputStream()
                    while (true) {
                        val count = zip.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES + 1 - manifest.size()))
                        if (count < 0) break
                        if (manifest.size() + count > MAX_MANIFEST_BYTES) return false
                        manifest.write(buffer, 0, count)
                    }
                    return BUNDLE_FORMAT_PATTERN.containsMatchIn(manifest.toString(Charsets.UTF_8.name()))
                }
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    scannedBytes += count
                    if (scannedBytes > MAX_SCAN_BYTES) return false
                }
            }
        }
    }

    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_SCAN_BYTES = 1024 * 1024
    private val BUNDLE_FORMAT_PATTERN = Regex(""""format"\s*:\s*"notepad-bundle"""")
}
