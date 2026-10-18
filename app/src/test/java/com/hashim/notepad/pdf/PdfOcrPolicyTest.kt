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
    fun explicitRecognitionDoesNotReuseExistingOcrWords() {
        assertFalse(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = true, forceRecognition = true))
        assertTrue(PdfOcrPolicy.shouldSkipPage(isScannedPage = true, hasOcrWords = true, forceRecognition = false))
        assertTrue(PdfOcrPolicy.shouldSkipPage(isScannedPage = false, hasOcrWords = false, forceRecognition = true))
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
}
