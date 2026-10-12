package com.hashim.notepad.pdf

import java.io.File
import java.util.zip.Inflater

/**
 * Best-effort extraction of annotation comments (/Contents of FreeText, Text, Ink, Highlight, ... annotations)
 * so notes written in other PDF apps become searchable here. PDFium renders the annotations themselves
 * (renderAnnot = true); this only reads their text. Handles plain objects and FlateDecode object streams;
 * encrypted PDFs or exotic filters simply yield nothing.
 */
object PdfAnnotations {
    private val OBJ = Regex("""(\d+)\s+(\d+)\s+obj\b""")
    private val ANNOT_SUBTYPE = Regex("""/Subtype\s*/(FreeText|Text|Ink|Highlight|Underline|StrikeOut|Squiggly|Square|Circle|Polygon|PolyLine|Line|Stamp|Caret|FileAttachment)\b""")
    private val CONTENTS = Regex("""/Contents\s*(\((?:[^()\\]|\\.|\((?:[^()\\]|\\.)*\))*\)|<[0-9A-Fa-f\s]*>)""")
    private val REF = Regex("""(\d+)\s+0\s+R""")
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** page index (0-based) -> annotation texts, in document order. */
    fun scan(file: File): Map<Int, List<String>> {
        if (!file.exists() || file.length() > MAX_BYTES) return emptyMap()
        val bytes = file.readBytes()
        val raw = String(bytes, Charsets.ISO_8859_1)
        val objects = HashMap<Int, String>()
        collectObjects(raw, objects)
        // Expand object streams (PDF 1.5+) so pages/annots stored inside them are visible too.
        for ((_, body) in objects.toList()) {
            if (!body.contains("/ObjStm")) continue
            val inflated = inflateStream(body) ?: continue
            collectFromObjStm(body, inflated, objects)
        }
        val pageObjs = objects.filter { (_, b) -> b.contains("/Type") && Regex("""/Type\s*/Page\b""").containsMatchIn(b) }
        if (pageObjs.isEmpty()) return emptyMap()
        val ordered = orderPages(objects, pageObjs.keys)
        val result = HashMap<Int, MutableList<String>>()
        ordered.forEachIndexed { index, pageNum ->
            val body = objects[pageNum] ?: return@forEachIndexed
            val annots = annotRefs(body, objects)
            for (ref in annots) {
                val a = objects[ref] ?: continue
                if (!ANNOT_SUBTYPE.containsMatchIn(a)) continue
                val text = CONTENTS.find(a)?.groupValues?.get(1)?.let { decodeString(it) }?.trim().orEmpty()
                if (text.isNotEmpty()) result.getOrPut(index) { ArrayList() }.add(text)
            }
        }
        return result
    }

    private fun collectObjects(raw: String, out: MutableMap<Int, String>) {
        var m = OBJ.find(raw)
        while (m != null) {
            val start = m.range.last + 1
            val end = raw.indexOf("endobj", start).let { if (it < 0) raw.length else it }
            out.putIfAbsent(m.groupValues[1].toInt(), raw.substring(start, end))
            m = OBJ.find(raw, end)
        }
    }

    private fun collectFromObjStm(dict: String, data: ByteArray, out: MutableMap<Int, String>) {
        val n = Regex("""/N\s+(\d+)""").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val first = Regex("""/First\s+(\d+)""").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val text = String(data, Charsets.ISO_8859_1)
        val header = text.substring(0, minOf(first, text.length)).trim().split(Regex("\\s+"))
        val offsets = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 1 < header.size && offsets.size < n) {
            val num = header[i].toIntOrNull(); val off = header[i + 1].toIntOrNull()
            if (num != null && off != null) offsets.add(num to off)
            i += 2
        }
        for ((k, pair) in offsets.withIndex()) {
            val s = first + pair.second
            val e = if (k + 1 < offsets.size) first + offsets[k + 1].second else text.length
            if (s in 0..e && e <= text.length) out.putIfAbsent(pair.first, text.substring(s, e))
        }
    }

    private fun inflateStream(body: String): ByteArray? {
        if (!body.contains("/FlateDecode")) return null
        val s = body.indexOf("stream").takeIf { it >= 0 } ?: return null
        var dataStart = s + 6
        if (body.startsWith("\r\n", dataStart)) dataStart += 2 else if (body.startsWith("\n", dataStart)) dataStart += 1
        val e = body.lastIndexOf("endstream").takeIf { it > dataStart } ?: return null
        val input = body.substring(dataStart, e).toByteArray(Charsets.ISO_8859_1)
        return runCatching {
            val inf = Inflater(); inf.setInput(input)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val c = inf.inflate(buf); if (c == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, c) }
            inf.end(); out.toByteArray()
        }.getOrNull()
    }

    /** Pages in reading order by walking the /Pages tree; falls back to object-number order. */
    private fun orderPages(objects: Map<Int, String>, pageNums: Set<Int>): List<Int> {
        val root = objects.entries.firstOrNull { (_, b) -> Regex("""/Type\s*/Pages\b""").containsMatchIn(b) && !b.contains("/Parent") }?.key
        val out = ArrayList<Int>()
        fun walk(num: Int, depth: Int) {
            if (depth > 64 || out.size > 50_000) return
            val b = objects[num] ?: return
            if (num in pageNums) { out.add(num); return }
            val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(b)?.groupValues?.get(1) ?: return
            for (r in REF.findAll(kids)) walk(r.groupValues[1].toInt(), depth + 1)
        }
        if (root != null) walk(root, 0)
        return if (out.isNotEmpty()) out else pageNums.sorted()
    }

    private fun annotRefs(pageBody: String, objects: Map<Int, String>): List<Int> {
        val m = Regex("""/Annots\s*(\[[^\]]*]|\d+\s+0\s+R)""").find(pageBody) ?: return emptyList()
        var arr = m.groupValues[1]
        if (!arr.startsWith("[")) arr = objects[REF.find(arr)?.groupValues?.get(1)?.toInt() ?: return emptyList()] ?: return emptyList()
        return REF.findAll(arr).map { it.groupValues[1].toInt() }.toList()
    }

    /** Decodes a PDF literal or hex string (PDFDocEncoding / UTF-16BE with BOM). */
    internal fun decodeString(s: String): String {
        val bytes: ByteArray = if (s.startsWith("<")) {
            val hex = s.substring(1, s.length - 1).filter { !it.isWhitespace() }
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            val out = java.io.ByteArrayOutputStream()
            var i = 1
            val end = s.length - 1
            while (i < end) {
                val c = s[i]
                if (c == '\\' && i + 1 < end) {
                    val n = s[i + 1]
                    when {
                        n == 'n' -> { out.write('\n'.code); i += 2 }
                        n == 'r' -> { out.write('\r'.code); i += 2 }
                        n == 't' -> { out.write('\t'.code); i += 2 }
                        n == 'b' -> { out.write(8); i += 2 }
                        n == 'f' -> { out.write(12); i += 2 }
                        n == '\n' -> i += 2
                        n == '\r' -> { i += 2; if (i < end && s[i] == '\n') i++ }
                        n in '0'..'7' -> {
                            var j = i + 1; var v = 0; var k = 0
                            while (k < 3 && j < end && s[j] in '0'..'7') { v = v * 8 + (s[j] - '0'); j++; k++ }
                            out.write(v and 0xFF); i = j
                        }
                        else -> { out.write(n.code); i += 2 }
                    }
                } else { out.write(c.code and 0xFF); i++ }
            }
            out.toByteArray()
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else String(bytes, Charsets.ISO_8859_1)
    }
}
