package com.hashim.notepad.ink

import android.graphics.Bitmap
import android.graphics.Color
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore
import java.util.Random

object InkTextures {
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private val pencilBackground: Bitmap by lazy {
        val random = Random(0x50454E43494CL)
        val pixels = IntArray(TEXTURE_SIZE * TEXTURE_SIZE) {
            val shade = random.nextInt(128, 224)
            Color.argb(random.nextInt(140, 256), shade, shade, shade)
        }
        Bitmap.createBitmap(pixels, TEXTURE_SIZE, TEXTURE_SIZE, Bitmap.Config.ARGB_8888)
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    val textureStore = TextureBitmapStore { id ->
        if (id == StockBrushes.pencilUnstableBackgroundTextureId) pencilBackground else null
    }

    private const val TEXTURE_SIZE = 64
}
