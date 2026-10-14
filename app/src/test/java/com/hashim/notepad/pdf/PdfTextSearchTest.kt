package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

class PdfTextSearchTest {
    @Test fun capturedArabicGeometryMatchesEveryNfcSourceLineAndCopiesTheWord() {
        val (source, boxes) = PdfGeometryTestFixtures.load("arabic")
        val layer = PdfGeometryText.order(source, boxes)
        val expected = listOf(
            "اختبار ترتيب العربية في ملف PDF",
            "لُغَةٌ عَرَبِيَّةٌ جَمِيلَةٌ",
        )

        assertEquals(
            layer.characters.joinToString(" | ") { "${it.sourceIndices}:${it.text}[${it.box.left},${it.box.right}]" },
            expected.map { Normalizer.normalize(it, Normalizer.Form.NFC) },
            layer.text.lines().map { Normalizer.normalize(it, Normalizer.Form.NFC) },
        )
        val walkthrough = listOf(
            listOf(63, 64) to "لُ",
            listOf(37, 38) to "غَ",
            listOf(39, 40) to "ةٌ",
            listOf(42, 43) to "عَ",
            listOf(44, 45) to "رَ",
            listOf(46, 47) to "بِ",
            listOf(48, 49, 50) to "يَّ",
            listOf(51, 52) to "ةٌ",
            listOf(54, 55) to "جَ",
            listOf(56, 57) to "مِ",
            listOf(58) to "ي",
            listOf(59, 60) to "لَ",
            listOf(33, 34) to "ةٌ",
        )
        walkthrough.forEach { (indices, text) ->
            val glyph = layer.characters.single { character -> indices.all { it in character.sourceIndices } }
            assertEquals(text, glyph.text)
            assertEquals(1, glyph.lineIndex)
        }
        listOf(27, 41, 53).forEach { sourceIndex ->
            val space = layer.characters.single { sourceIndex in it.sourceIndices }
            assertEquals(" ", space.text)
            assertEquals(listOf(sourceIndex), space.sourceIndices)
        }
        fun position(sourceIndex: Int, lineIndex: Int) =
            layer.characters.indexOfFirst { it.lineIndex == lineIndex && sourceIndex in it.sourceIndices }
        assertEquals(position(40, 1) + 1, position(41, 1))
        assertEquals(position(52, 1) + 1, position(53, 1))
        assertEquals(position(26, 0) + 1, position(27, 0))
        assertEquals(position(27, 0) + 1, position(28, 0))

        val plainHit = findPdfTextHits("arabic", 0, layer, "لغة").single()
        val markedHit = findPdfTextHits("arabic", 0, layer, "لُغَة").single()
        assertEquals(plainHit.matchStart, markedHit.matchStart)
        assertEquals(plainHit.matchEnd, markedHit.matchEnd)

        val word = PdfWordSnap.wordRange(layer.text, plainHit.matchStart, Locale("ar"))!!
        assertEquals(
            "لُغَةٌ",
            Normalizer.normalize(layer.text.substring(word.start, word.end), Normalizer.Form.NFC),
        )
    }

    @Test fun capturedUrduGeometrySearchAndCopyKeepTheWholeWord() {
        val (source, boxes) = PdfGeometryTestFixtures.load("urdu")
        val layer = PdfGeometryText.order(source, boxes)
        assertEquals("geometry: ${layer.text}", "اُردو کی کتاب ہے", layer.text)
        val hit = findPdfTextHits("urdu", 0, layer, "کتاب").single()
        val word = PdfWordSnap.wordRange(layer.text, hit.matchStart, Locale("ur"))!!

        assertEquals("کتاب", layer.text.substring(word.start, word.end))
    }

    @Test fun returnsOriginalCharacterRangeAndShortSnippet() {
        val text = "Intro ".repeat(10) + "اَلْعَرَبِي " + "end ".repeat(10)
        val hit = findPdfTextHits("page", 4, text, "العربي").single()
        assertEquals(text.indexOf("اَلْعَرَبِي"), hit.matchStart)
        assertEquals(text.indexOf("اَلْعَرَبِي") + "اَلْعَرَبِي".length, hit.matchEnd)
        assertEquals(text.substring(hit.matchStart, hit.matchEnd), hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
        assertEquals("page", hit.pageId)
    }

    @Test fun matchesPdfiumTextWithDetachedTashkeelFromCapturedCodepoints() {
        val extracted = "\u064Eع\u064Eر\u0650ب\u064E\u0651ي\u064Cة"
        val hit = findPdfTextHits("page", 0, extracted, "عربية").single()
        assertEquals(1, hit.matchStart)
        assertEquals(extracted.length, hit.matchEnd)
    }

    @Test fun geometryPreservesEnglishPdfiumSpacesWithoutUsableBoxes() {
        val source = "Release101 text survives"
        var x = 0f
        val boxes = source.map { character ->
            if (character.isWhitespace()) {
                x += 10f
                PdfTextRect(0f, 0f, 0f, 0f)
            } else {
                PdfTextRect(x, 0f, x + 5f, 10f).also { x += 5f }
            }
        }

        val layer = PdfGeometryText.order(source, boxes)

        assertEquals(source, layer.text)
        assertEquals(1, findPdfTextHits("page", 0, layer, "text survives").size)
    }

    @Test fun geometryFallbackDropsCarriageReturnsAndLineFeeds() {
        assertEquals("firstsecond", PdfGeometryText.order("first\r\nsecond", emptyList()).text)
    }

    @Test fun geometryKeepsArabicSourceSpacesBetweenRtlWords() {
        val source = "مرحبا بالعالم"
        var x = 120f
        val boxes = source.map { character ->
            if (character.isWhitespace()) {
                x -= 10f
                PdfTextRect(0f, 0f, 0f, 0f)
            } else {
                PdfTextRect(x - 5f, 0f, x, 10f).also { x -= 6f }
            }
        }

        assertEquals(source, PdfGeometryText.order(source, boxes).text)
    }

    @Test fun geometrySynthesizesSpacesAtLargeGlyphGapsForMultiwordSearch() {
        val source = "Release101textsurvives"
        var x = 0f
        val boxes = source.mapIndexed { index, _ ->
            if (index == 10 || index == 14) x += 10f
            PdfTextRect(x, 0f, x + 5f, 10f).also { x += 5f }
        }

        val layer = PdfGeometryText.order(source, boxes)
        val hit = findPdfTextHits("page", 0, layer, "text survives")

        assertEquals("Release101 text survives", layer.text)
        assertEquals(1, hit.size)
        assertEquals("text survives", layer.text.substring(hit.single().matchStart, hit.single().matchEnd))
    }

    @Test fun rtlSnippetsPreserveWordAndGraphemeBoundaries() {
        val text = "مقدمة ".repeat(10) + "لُغَةٌ" + " خاتمة".repeat(10)
        val hit = findPdfTextHits("page", 0, text, "لغة").single()
        val snippetStart = hit.matchStart - hit.snippetMatchStart
        val snippetEnd = snippetStart + hit.snippet.length
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }

        assertTrue(words.isBoundary(snippetStart))
        assertTrue(words.isBoundary(snippetEnd))
        assertTrue(graphemes.isBoundary(snippetStart))
        assertTrue(graphemes.isBoundary(snippetEnd))
        assertEquals(text.substring(hit.matchStart, hit.matchEnd), hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
    }

    @Test fun duplicateNormalizedMatchesAreDeduplicatedBySourceRange() {
        val hit = findPdfTextHits("page", 0, "\u2161", "i")

        assertEquals(1, hit.size)
        assertEquals(0, hit.single().matchStart)
        assertEquals(1, hit.single().matchEnd)
    }

    @Test fun textAndMultipleMarksHaveDistinctNamespacedResultKeys() {
        val textHit = findPdfTextHits("page", 0, "hello", "hello").single()
        val markHits = listOf(textHit.copy(resultKey = PdfSearchResultKey.mark("mark-one")),
            textHit.copy(resultKey = PdfSearchResultKey.mark("mark-two")))

        assertEquals("pdf-text-hit:page:0:5", textHit.resultKey)
        assertEquals("pdf-mark-hit:mark-one", markHits.first().resultKey)
        assertEquals(3, (listOf(textHit) + markHits).map { it.resultKey }.distinct().size)
    }
}
