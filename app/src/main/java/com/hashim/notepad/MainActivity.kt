package com.hashim.notepad

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Parcelable
import android.view.KeyEvent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.hashim.notepad.ui.NotePadApp
import com.hashim.notepad.ui.NotePadTheme
import com.hashim.notepad.ui.Settings
import com.hashim.notepad.ink.StylusInputDiagnostics
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class InboundShare(val uris: List<Uri>, val mimeType: String?)

class MainActivity : ComponentActivity() {
    private val launchUri = mutableStateOf<Uri?>(null)
    private val launchShare = mutableStateOf<InboundShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG && intent?.action == "com.hashim.notepad.DUMP_PDF_TEXT") {
            val file = File(intent.getStringExtra("path").orEmpty())
            val page = intent.getIntExtra("page", 0)
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val text = withContext(PdfiumDispatcher.dispatcher) {
                    PdfPageSource(this@MainActivity, file).use { it.text(page) }
                }
                Log.i("PdfTextDump", "page=$page text=${text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }}")
                finish()
            }
            return
        }
        launchUri.value = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        launchShare.value = shareFrom(intent)
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode(this)) }
            var dynamic by remember { mutableStateOf(Settings.useDynamicColor(this)) }
            NotePadTheme(mode, dynamic) {
                val uri by launchUri
                val share by launchShare
                val authCallback = uri?.takeIf { it.scheme == "notepad" && it.host == "auth-callback" }
                val initialPdf = uri?.takeIf { authCallback == null }
                NotePadApp(initialPdf, authCallback, themeMode = mode, dynamicColor = dynamic, initialShare = share,
                    onThemeChange = { mode = it; Settings.setThemeMode(this, it) },
                    onDynamicChange = { dynamic = it; Settings.setUseDynamicColor(this, it) })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchUri.value = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        launchShare.value = shareFrom(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StylusInputDiagnostics.isStylusKey(event)) {
            StylusInputDiagnostics.recordKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun shareFrom(intent: Intent?): InboundShare? {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return null
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val multiple = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                multiple?.let(::addAll)
            } else {
                val single = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
                }
                single?.let(::add)
            }
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
            }
        }.distinct()
        return uris.takeIf { it.isNotEmpty() }?.let { InboundShare(it, intent.type) }
    }
}
