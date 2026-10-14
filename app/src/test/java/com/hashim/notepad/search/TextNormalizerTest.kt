package com.hashim.notepad.search

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test fun removesArabicDiacriticsAndFoldsLetters() {
        assertEquals("العربی", TextNormalizer.normalize("اَلْعَرَبِيُّ").text)
        assertEquals("ی ک", TextNormalizer.normalize("ي ك").text)
    }

    @Test fun foldsUrduAndFarsiVariants() {
        assertEquals("ه ه", TextNormalizer.normalize("ہ ھ").text)
        assertEquals("میخواهم", TextNormalizer.normalize("می\u200Cخواهم").text)
    }

    @Test fun foldsIndicDigitsAndEnglishCase() {
        assertEquals("0123456789", TextNormalizer.normalize("٠١٢٣٤٥٦٧٨٩").text)
        assertEquals("012", TextNormalizer.normalize("۰۱۲").text)
        assertEquals("the quick fox", TextNormalizer.normalize("THE   Quick Fox").text)
    }

    @Test fun mapsEveryNormalizedCharacterBackToItsOriginalCharacter() {
        val source = "اَلْعَرَبِي"
        val normalized = TextNormalizer.normalize(source)
        assertEquals("العربی", normalized.text)
        assertEquals(normalized.text.length, normalized.indexMap.size)
        assertEquals("اَلْعَرَبِي".filter { it !in "َِْ" }, buildString {
            normalized.indexMap.forEach { append(source[it]) }
        })
    }

    @Test fun appliesCompatibilityNormalizationAcrossCombiningCharacters() {
        assertEquals("café", TextNormalizer.normalize("cafe\u0301").text)
    }
}
