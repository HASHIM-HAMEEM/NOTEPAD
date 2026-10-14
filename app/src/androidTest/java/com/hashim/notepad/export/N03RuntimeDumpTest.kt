package com.hashim.notepad.export

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class N03RuntimeDumpTest {
    @Test
    fun shareExportAndStoredImportKeepBothAnnotations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PDFBoxResourceLoader.init(instrumentation.targetContext)
        val runtimePaths = listOf(
            "share-output" to InstrumentationRegistry.getArguments().getString("sharePath"),
            "stored-import" to InstrumentationRegistry.getArguments().getString("importPath"),
        )
        val useRuntimePaths = runtimePaths.all { it.second != null }
        val files: List<Pair<String, File?>> = if (useRuntimePaths) {
            runtimePaths.map { (name, path) -> name to File(requireNotNull(path)) }
        } else {
            listOf("n03-share-output.pdf" to null, "n03-stored-import.pdf" to null)
        }
        val expectedContents = if (useRuntimePaths) {
            setOf("العربية", "جَمِيلَةٌ")
        } else {
            setOf("عَرَبِيَّةٌ", "PDF")
        }
        files.forEach { (assetName, file) ->
            val input = file?.inputStream() ?: instrumentation.context.assets.open(assetName)
            input.use {
                if (file != null) assertTrue(file.isFile)
                PDDocument.load(it).use { document ->
                    val annotations = document.getPage(0).annotations
                    Log.i(
                        "PdfAnnotsRuntime",
                        "$assetName pages=${document.numberOfPages} /Annots count=${annotations.size} " +
                            annotations.joinToString {
                                "${it.subtype}:${it.contents}:printed=${it.isPrinted()}:appearance=${it.normalAppearanceStream != null}"
                            },
                    )
                    assertEquals(2, annotations.size)
                    assertTrue(annotations.all { it.subtype == "Highlight" })
                    assertTrue(annotations.all { it.isPrinted() && it.normalAppearanceStream != null })
                    assertEquals(expectedContents, annotations.mapNotNull { annotation -> annotation.contents }.toSet())
                }
            }
        }
    }
}
