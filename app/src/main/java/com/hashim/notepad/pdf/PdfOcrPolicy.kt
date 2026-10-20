package com.hashim.notepad.pdf

internal object PdfOcrPolicy {
    private const val MIN_ARABIC_WORDS_FOR_RETRY = 3

    /** 300 dpi for every script: on the bench Arabic at 300 dpi + preprocessing scored higher than 200 dpi, and Nastaliq needs the x-height. */
    fun renderDpi(language: String): Int = 300

    /**
     * Automatic passes only touch scanned pages that have no OCR rows yet. An explicit
     * "Recognize" request always runs, even when the PDF carries an embedded text layer:
     * Arabic PDFs frequently embed broken or presentation-form text that is useless for search.
     */
    fun shouldSkipPage(isScannedPage: Boolean, hasOcrWords: Boolean, forceRecognition: Boolean): Boolean =
        !forceRecognition && (!isScannedPage || hasOcrWords)

    fun shouldFailWhenNoWords(requestedPage: Boolean, wordCount: Int): Boolean =
        requestedPage && wordCount == 0

    fun shouldRetryArabicWithSingleBlock(language: String, words: List<OcrBox>): Boolean =
        language.split('+').any { it == "ara" } && arabicWordCount(words) < MIN_ARABIC_WORDS_FOR_RETRY

    /**
     * Urdu books are usually filed as Arabic-script ("ar") notebooks, and the Arabic model turns Nastaliq into noise
     * with low confidence. When the installed Urdu model is available and the Arabic pass is weak, try urd+eng too.
     */
    fun shouldRetryWithUrdu(language: String, words: List<OcrBox>, urduModelAvailable: Boolean): Boolean {
        if (!urduModelAvailable) return false
        if (!language.split('+').any { it == "ara" }) return false
        val arabic = words.filter { word -> word.text.any { it in '\u0600'..'\u06ff' } }
        if (arabic.isEmpty()) return true
        return arabic.map { it.confidence }.average() < MIN_CONFIDENCE_TO_TRUST_ARABIC
    }

    fun preferUrdu(arabicPass: List<OcrBox>, urduPass: List<OcrBox>): Boolean {
        fun score(words: List<OcrBox>) = words.filter { w -> w.text.any { it in '\u0600'..'\u06ff' } }.sumOf { it.confidence.toDouble() }
        return score(urduPass) > score(arabicPass)
    }

    fun preferSingleBlock(automatic: List<OcrBox>, singleBlock: List<OcrBox>): Boolean =
        arabicWordCount(singleBlock) > arabicWordCount(automatic)

    private const val MIN_CONFIDENCE_TO_TRUST_ARABIC = 65.0

    private fun arabicWordCount(words: List<OcrBox>): Int =
        words.count { word -> word.text.any { it in '\u0600'..'\u06ff' } }
}
