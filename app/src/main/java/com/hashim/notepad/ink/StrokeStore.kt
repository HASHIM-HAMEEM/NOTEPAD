package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Stroke batch format v1: magic, version, count, then per stroke
 * [familyTag, colorArgb, size, epsilon, inputBytesLength, inputBytes].
 * Inputs use the Ink library's own gzip-compressed protobuf encoding; coordinates are page points.
 */
object StrokeStore {
    private const val MAGIC = 0x4E50494B // "NPIK"
    private const val VERSION = 1
    private const val TAG_PEN = 0
    private const val TAG_HIGHLIGHTER = 1

    fun encode(strokes: List<Stroke>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(if (s.brush.family == StockBrushes.highlighter()) TAG_HIGHLIGHTER else TAG_PEN)
                out.writeInt(s.brush.colorIntArgb); out.writeFloat(s.brush.size); out.writeFloat(s.brush.epsilon)
                val bytes = ByteArrayOutputStream().also { s.inputs.encode(it) }.toByteArray()
                out.writeInt(bytes.size); out.write(bytes)
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Stroke> {
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            require(inp.readInt() == MAGIC) { "Not a NotePad stroke batch" }
            require(inp.readInt() == VERSION) { "Unsupported stroke batch version" }
            val n = inp.readInt()
            return List(n) {
                val tag = inp.readInt(); val color = inp.readInt(); val size = inp.readFloat(); val eps = inp.readFloat()
                val data = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val family = if (tag == TAG_HIGHLIGHTER) StockBrushes.highlighter() else StockBrushes.pressurePen()
                Stroke(Brush.createWithColorIntArgb(family, color, size, eps), StrokeInputBatch.decode(data.inputStream()))
            }
        }
    }
}
