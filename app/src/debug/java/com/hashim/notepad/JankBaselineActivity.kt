package com.hashim.notepad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

class JankBaselineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 36f }
            repeat(24) { line -> canvas.drawText("Static bitmap baseline $line", 48f, 90f + line * 44f, paint) }
        }.asImageBitmap()
        setContent {
            LazyColumn(Modifier.fillMaxSize()) {
                items((0 until 300).toList(), key = { it }) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize().height(720.dp))
                }
            }
        }
    }
}
