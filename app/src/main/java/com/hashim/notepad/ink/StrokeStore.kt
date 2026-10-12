package com.hashim.notepad.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Minimal on-disk format for the spike: a count, then per stroke
 * [familyTag, colorArgb, size, epsilon, inputBytesLength, inputBytes].
 * Inputs use the Ink library's own gzip-compressed protobuf encoding.
 */
object StrokeStore {
    private const val TAG_PEN = 0
    private const val TAG_HIGHLIGHTER = 1

    fun save(file: File, strokes: List<Stroke>) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(if (s.brush.family == StockBrushes.highlighter()) TAG_HIGHLIGHTER else TAG_PEN)
                out.writeInt(s.brush.colorIntArgb)
                out.writeFloat(s.brush.size)
                out.writeFloat(s.brush.epsilon)
                val bytes = ByteArrayOutputStream().also { s.inputs.encode(it) }.toByteArray()
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
    }

    fun load(file: File): List<Stroke> {
        if (!file.exists()) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { inp ->
            val n = inp.readInt()
            return List(n) {
                val tag = inp.readInt()
                val color = inp.readInt()
                val size = inp.readFloat()
                val eps = inp.readFloat()
                val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
                val family = if (tag == TAG_HIGHLIGHTER) StockBrushes.highlighter() else StockBrushes.pressurePen()
                val brush = Brush.createWithColorIntArgb(family, color, size, eps)
                Stroke(brush, StrokeInputBatch.decode(bytes.inputStream()))
            }
        }
    }
}
