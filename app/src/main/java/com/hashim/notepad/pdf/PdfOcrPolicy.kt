package com.hashim.notepad.pdf

internal object PdfOcrPolicy {
    private const val MIN_ARABIC_WORDS_FOR_RETRY = 3

    fun renderDpi(language: String): Int =
        if (language.lowercase().substringBefore('-') == "ar") 200 else 300

    fun shouldSkipPage(isScannedPage: Boolean, hasOcrWords: Boolean, forceRecognition: Boolean): Boolean =
        !isScannedPage || (hasOcrWords && !forceRecognition)

    fun shouldFailWhenNoWords(requestedPage: Boolean, wordCount: Int): Boolean =
        requestedPage && wordCount == 0

    fun shouldRetryArabicWithSingleBlock(language: String, words: List<OcrBox>): Boolean =
        language.split('+').any { it == "ara" } && arabicWordCount(words) < MIN_ARABIC_WORDS_FOR_RETRY

    fun preferSingleBlock(automatic: List<OcrBox>, singleBlock: List<OcrBox>): Boolean =
        arabicWordCount(singleBlock) > arabicWordCount(automatic)

    private fun arabicWordCount(words: List<OcrBox>): Int =
        words.count { word -> word.text.any { it in '\u0600'..'\u06ff' } }
}
