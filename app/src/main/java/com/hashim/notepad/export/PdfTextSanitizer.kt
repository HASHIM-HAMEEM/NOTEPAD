package com.hashim.notepad.export

object PdfTextSanitizer {
    fun sanitize(text: String, hasGlyph: (Int) -> Boolean): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isBidiControl(codePoint)) continue
            if (hasGlyph(codePoint)) appendCodePoint(codePoint)
        }
    }

    private fun isBidiControl(codePoint: Int): Boolean =
        codePoint == 0x200E ||
            codePoint == 0x200F ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2066..0x2069 ||
            codePoint == 0xFEFF
}
