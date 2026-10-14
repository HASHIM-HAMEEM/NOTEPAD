package com.hashim.notepad.export

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
        val files = listOf(
            "n03-share-output.pdf",
            "n03-stored-import.pdf",
        )
        files.forEach { assetName ->
            instrumentation.context.assets.open(assetName).use { input ->
                PDDocument.load(input).use { document ->
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
                    assertEquals(
                        setOf("عَرَبِيَّةٌ", "PDF"),
                        annotations.mapNotNull { it.contents }.toSet(),
                    )
                }
            }
        }
    }
}
