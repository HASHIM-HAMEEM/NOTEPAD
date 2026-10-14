package com.hashim.notepad.pdf

internal object PdfOcrPolicy {
    fun shouldSkipPage(isScannedPage: Boolean, hasOcrWords: Boolean, forceRecognition: Boolean): Boolean =
        !isScannedPage || (hasOcrWords && !forceRecognition)

    fun shouldFailWhenNoWords(requestedPage: Boolean, wordCount: Int): Boolean =
        requestedPage && wordCount == 0
}
