package com.hashim.notepad

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.hashim.notepad.ui.NotePadApp
import com.hashim.notepad.ui.NotePadTheme
import com.hashim.notepad.ui.Settings
import com.hashim.notepad.ink.StylusInputDiagnostics

class MainActivity : ComponentActivity() {
    private val launchUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchUri.value = intent?.data
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode(this)) }
            var dynamic by remember { mutableStateOf(Settings.useDynamicColor(this)) }
            NotePadTheme(mode, dynamic) {
                val uri by launchUri
                val authCallback = uri?.takeIf { it.scheme == "notepad" && it.host == "auth-callback" }
                val initialPdf = uri?.takeIf { authCallback == null }
                NotePadApp(initialPdf, authCallback, themeMode = mode, dynamicColor = dynamic,
                    onThemeChange = { mode = it; Settings.setThemeMode(this, it) },
                    onDynamicChange = { dynamic = it; Settings.setUseDynamicColor(this, it) })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchUri.value = intent.data
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StylusInputDiagnostics.isStylusKey(event)) {
            StylusInputDiagnostics.recordKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }
}
