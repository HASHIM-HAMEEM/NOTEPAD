package com.hashim.notepad.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleFileClassifierTest {
    @Test
    fun identifiesBundleFromItsManifestEntry() {
        val bytes = zipOf(BUNDLE_MANIFEST_ENTRY to """{"format":"$BUNDLE_FORMAT","version":1}""")

        assertEquals(ImportFileKind.NOTEPAD_BUNDLE, classify("export.zip", bytes))
    }

    @Test
    fun keepsOrdinaryZipAsAnAttachmentCandidate() {
        val bytes = zipOf("readme.txt" to "hello")

        assertEquals(ImportFileKind.ZIP, classify("archive.zip", bytes))
    }

    @Test
    fun identifiesPdfByItsHeader() {
        val bytes = "%PDF-1.7\n".toByteArray()

        assertEquals(ImportFileKind.PDF, classify("document.bin", bytes))
    }

    @Test
    fun usesTheBundleFilenameAsASecondaryHint() {
        val bytes = zipOf("readme.txt" to "hello")

        assertEquals(ImportFileKind.NOTEPAD_BUNDLE, classify("study.notepad.zip", bytes))
    }

    private fun classify(name: String, bytes: ByteArray) =
        BundleFileClassifier.classify(name, ByteArrayInputStream(bytes))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
