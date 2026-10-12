package com.hashim.notepad.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import java.util.Random

object PaperTexture {
    private const val SIZE = 256
    private val grain: Bitmap by lazy {
        val random = Random(0x50415045524CL)
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val luminance = (250 + random.nextInt(-5, 6)).coerceIn(0, 255)
                pixels[y * SIZE + x] = Color.rgb(luminance, luminance, luminance)
            }
        }
        repeat(96) {
            val x = random.nextInt(SIZE)
            val y = random.nextInt(SIZE)
            val length = random.nextInt(3, 9)
            val delta = if (random.nextBoolean()) 2 else -2
            for (step in 0 until length) {
                val px = (x + step) % SIZE
                val source = pixels[y * SIZE + px]
                val value = (Color.red(source) + delta).coerceIn(0, 255)
                pixels[y * SIZE + px] = Color.rgb(value, value, value)
            }
        }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    fun bitmap(): Bitmap = grain

    fun shader(): BitmapShader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}
