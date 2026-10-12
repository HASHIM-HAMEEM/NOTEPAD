package com.hashim.notepad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.hashim.notepad.ui.NotePadApp
import com.hashim.notepad.ui.NotePadTheme
import com.hashim.notepad.ui.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialPdf = intent?.data
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode(this)) }
            var dynamic by remember { mutableStateOf(Settings.useDynamicColor(this)) }
            NotePadTheme(mode, dynamic) {
                NotePadApp(initialPdf, themeMode = mode, dynamicColor = dynamic,
                    onThemeChange = { mode = it; Settings.setThemeMode(this, it) },
                    onDynamicChange = { dynamic = it; Settings.setUseDynamicColor(this, it) })
            }
        }
    }
}

