package com.hashim.notepad.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import java.util.Random
import kotlin.math.roundToInt

object PaperTexture {
    private const val SIZE = 256
    private val grain: Bitmap by lazy {
        val random = Random(0x50415045524CL)
        val coarse = FloatArray(16 * 16) { random.nextFloat() * 2f - 1f }
        val fine = FloatArray(64 * 64) { random.nextFloat() * 2f - 1f }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val noise = valueNoise(coarse, x, y, 16) * 0.72f + valueNoise(fine, x, y, 4) * 0.28f
                val luminance = (255f + noise * 10f).roundToInt().coerceIn(0, 255)
                pixels[y * SIZE + x] = Color.rgb(luminance, luminance, luminance)
            }
        }
        repeat(44) {
            val x = random.nextInt(SIZE)
            val y = random.nextInt(SIZE)
            val length = random.nextInt(3, 12)
            val delta = if (random.nextBoolean()) 7 else -7
            for (step in 0 until length) {
                val px = (x + step / 2) % SIZE
                val py = (y + step) % SIZE
                val source = pixels[py * SIZE + px]
                val value = (Color.red(source) + delta).coerceIn(0, 255)
                pixels[py * SIZE + px] = Color.rgb(value, value, value)
            }
        }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    fun bitmap(): Bitmap = grain

    fun shader(): BitmapShader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    private fun valueNoise(values: FloatArray, x: Int, y: Int, cellSize: Int): Float {
        val count = SIZE / cellSize
        val gx = x / cellSize
        val gy = y / cellSize
        val tx = smooth((x % cellSize).toFloat() / cellSize)
        val ty = smooth((y % cellSize).toFloat() / cellSize)
        val x0 = gx % count
        val x1 = (x0 + 1) % count
        val y0 = gy % count
        val y1 = (y0 + 1) % count
        val top = lerp(values[y0 * count + x0], values[y0 * count + x1], tx)
        val bottom = lerp(values[y1 * count + x0], values[y1 * count + x1], tx)
        return lerp(top, bottom, ty)
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
}
