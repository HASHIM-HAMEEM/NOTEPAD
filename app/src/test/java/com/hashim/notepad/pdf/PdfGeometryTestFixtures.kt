package com.hashim.notepad.pdf

internal object PdfGeometryTestFixtures {
    fun load(name: String): Pair<String, List<PdfTextRect>> {
        val text = StringBuilder()
        val boxes = mutableListOf<PdfTextRect>()
        val stream = PdfGeometryTestFixtures::class.java.getResourceAsStream("/pdf-geometry/$name.tsv")
            ?: error("Missing PDF geometry fixture: $name")
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split('\t')
                text.appendCodePoint(fields[1].toInt(16))
                boxes += PdfTextRect(
                    fields[2].toFloat(),
                    fields[3].toFloat(),
                    fields[4].toFloat(),
                    fields[5].toFloat(),
                )
            }
        }
        return text.toString() to boxes
    }
}
