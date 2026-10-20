package com.hashim.notepad.export

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextSanitizerTest {
    @Test
    fun stripsBidiControlsWithoutQueryingFontCoverage() {
        val controls = "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF"
        val checkedCodePoints = mutableListOf<Int>()

        val sanitized = PdfTextSanitizer.sanitize("A${controls}ب") {
            checkedCodePoints += it
            true
        }

        assertEquals("Aب", sanitized)
        assertEquals(listOf('A'.code, 'ب'.code), checkedCodePoints)
    }

    @Test
    fun dropsUnsupportedCodePointsAndAppliesCoverageRuleToJoiners() {
        val sanitized = PdfTextSanitizer.sanitize("A\u2603ب\u200C\u200Dب") {
            it != 0x2603 && it != 0x200C
        }

        assertEquals("Aب\u200Dب", sanitized)
    }

    @Test
    fun keepsSupportedArabicAndLatinText() {
        val text = "Note لغة"

        assertEquals(text, PdfTextSanitizer.sanitize(text) { true })
    }

    @Test
    fun keepsEncodableArabicAndStripsBidiControls() {
        val text = "Release \u200Fلغة"

        assertEquals("Release لغة", PdfTextSanitizer.sanitize(text) { true })
    }

    @Test
    fun processesSurrogatePairsAsSingleCodePoints() {
        val text = "A\uD83D\uDE00ب"
        val checkedCodePoints = mutableListOf<Int>()

        val sanitized = PdfTextSanitizer.sanitize(text) {
            checkedCodePoints += it
            true
        }
        val withoutEmoji = PdfTextSanitizer.sanitize(text) { it != 0x1F600 }

        assertEquals(text, sanitized)
        assertEquals(listOf('A'.code, 0x1F600, 'ب'.code), checkedCodePoints)
        assertEquals("Aب", withoutEmoji)
    }
}
