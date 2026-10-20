package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOcrPolicyTest {
    @Test
    fun arabicNotebookUsesArabicAndEnglishModels() {
        assertEquals("ara+eng", ocrLanguageForNotebook("ar"))
        assertEquals("ara+eng", ocrLanguageForNotebook("ar-SA"))
    }

    @Test
    fun arabicPagesUseLowerRenderDpiWhileOtherLanguagesKeepStandardDpi() {
        assertEquals(300, PdfOcrPolicy.renderDpi("ar"))
        assertEquals(300, PdfOcrPolicy.renderDpi("ar-SA"))
        assertEquals(300, PdfOcrPolicy.renderDpi("en"))
    }

    @Test
    fun explicitRecognitionDoesNotReuseExistingOcrWords() {
        assertFalse(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = true, forceRecognition = true))
        assertTrue(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = true, forceRecognition = false))
        assertFalse(PdfOcrPolicy.shouldSkipPage(isScannedPage = false, hasOcrWords = false, forceRecognition = true))
    }

    @Test
    fun automaticPassSkipsPagesWithEmbeddedTextOrExistingWords() {
        assertTrue(PdfOcrPolicy.shouldSkipPage(isScannedPage = false, hasOcrWords = false, forceRecognition = false))
        assertTrue(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = true, forceRecognition = false))
        assertFalse(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = false, forceRecognition = false))
    }

    @Test
    fun explicitPageRecognitionFailsWhenNoWordsAreRecognized() {
        assertTrue(PdfOcrPolicy.shouldFailWhenNoWords(requestedPage = true, wordCount = 0))
        assertFalse(PdfOcrPolicy.shouldFailWhenNoWords(requestedPage = false, wordCount = 0))
        assertFalse(PdfOcrPolicy.shouldFailWhenNoWords(requestedPage = true, wordCount = 1))
    }

    @Test
    fun sparseArabicAutomaticPassRetriesAndPrefersBetterSingleBlockPass() {
        val automatic = listOf(OcrBox("لُغَةٌ", 0, 0, 10, 10, 70f))
        val singleBlock = listOf(
            OcrBox("لُغَةٌ", 0, 0, 10, 10, 80f),
            OcrBox("عَرَبِيَّةٌ", 11, 0, 30, 10, 85f),
            OcrBox("جَمِيلَةٌ", 31, 0, 50, 10, 90f),
        )

        assertTrue(PdfOcrPolicy.shouldRetryArabicWithSingleBlock("ara+eng", automatic))
        assertFalse(PdfOcrPolicy.shouldRetryArabicWithSingleBlock("eng", automatic))
        assertTrue(PdfOcrPolicy.preferSingleBlock(automatic, singleBlock))
        assertFalse(PdfOcrPolicy.preferSingleBlock(singleBlock, automatic))
    }

    @Test
    fun weakArabicPassRetriesWithUrduOnlyWhenModelInstalled() {
        val weak = listOf(OcrBox("عاضا", 0, 0, 10, 10, 31f), OcrBox("yh", 0, 0, 10, 10, 80f))
        val strong = listOf(OcrBox("المكتبة", 0, 0, 10, 10, 91f), OcrBox("الطلاب", 0, 0, 10, 10, 88f))
        assertTrue(PdfOcrPolicy.shouldRetryWithUrdu("ara+eng", weak, urduModelAvailable = true))
        assertFalse(PdfOcrPolicy.shouldRetryWithUrdu("ara+eng", weak, urduModelAvailable = false))
        assertFalse(PdfOcrPolicy.shouldRetryWithUrdu("ara+eng", strong, urduModelAvailable = true))
        assertFalse(PdfOcrPolicy.shouldRetryWithUrdu("eng", weak, urduModelAvailable = true))
        assertTrue(PdfOcrPolicy.preferUrdu(weak, strong))
        assertFalse(PdfOcrPolicy.preferUrdu(strong, weak))
    }
}
