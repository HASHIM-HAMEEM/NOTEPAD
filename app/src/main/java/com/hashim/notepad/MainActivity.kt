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
import com.hashim.notepad.audio.AudioRecordingService
import com.hashim.notepad.ink.StylusInputDiagnostics
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class InboundShare(val uris: List<Uri>, val mimeType: String?)
data class InboundShareEvent(val id: String, val share: InboundShare)

class MainActivity : ComponentActivity() {
    private val launchUri = mutableStateOf<Uri?>(null)
    private val launchShare = mutableStateOf<InboundShareEvent?>(null)
    private var handledShareKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AudioRecordingService.cleanupInterruptedFiles(applicationContext)
        if (BuildConfig.DEBUG && intent?.action == "com.hashim.notepad.DUMP_PDF_TEXT") {
            val file = File(intent.getStringExtra("path").orEmpty())
            val page = intent.getIntExtra("page", 0)
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val (text, boxes) = withContext(PdfiumDispatcher.dispatcher) {
                    PdfPageSource(this@MainActivity, file).use { source ->
                        source.text(page) to source.textCharBoxes(page)
                    }
                }
                Log.i("PdfTextDump", "page=$page text=${text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }}")
                val codePoints = text.codePoints().toArray()
                boxes.forEachIndexed { index, box ->
                    val codePoint = codePoints.getOrNull(index) ?: return@forEachIndexed
                    Log.i(
                        "PdfTextDump",
                        "char[$index] U+%04X box=%.3f,%.3f,%.3f,%.3f".format(
                            codePoint, box.left, box.top, box.right, box.bottom,
                        ),
                    )
                }
                finish()
            }
            return
        }
        launchUri.value = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        handledShareKey = savedInstanceState?.getString(SHARE_HANDLED_KEY)
        val incomingShare = shareFrom(intent)
        if (incomingShare != null) {
            val shareKey = intent?.getStringExtra(SHARE_EVENT_KEY)
            if (savedInstanceState == null || shareKey != null) {
                val key = shareKey ?: UUID.randomUUID().toString()
                intent?.putExtra(SHARE_EVENT_KEY, key)
                if (key != handledShareKey) {
                    handledShareKey = key
                    launchShare.value = InboundShareEvent(key, incomingShare)
                }
            }
            setIntent(Intent())
        }
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode(this)) }
            var dynamic by remember { mutableStateOf(Settings.useDynamicColor(this)) }
            NotePadTheme(mode, dynamic) {
                val uri by launchUri
                val share by launchShare
                val authCallback = uri?.takeIf { it.scheme == "notepad" && it.host == "auth-callback" }
                val initialPdf = uri?.takeIf { authCallback == null }
                NotePadApp(
                    initialPdf,
                    authCallback,
                    themeMode = mode,
                    dynamicColor = dynamic,
                    initialShare = share,
                    onShareConsumed = { key -> if (launchShare.value?.id == key) launchShare.value = null },
                    onThemeChange = { mode = it; Settings.setThemeMode(this, it) },
                    onDynamicChange = { dynamic = it; Settings.setUseDynamicColor(this, it) })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchUri.value = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        val incomingShare = shareFrom(intent)
        if (incomingShare != null) {
            val key = intent.getStringExtra(SHARE_EVENT_KEY) ?: UUID.randomUUID().toString()
            intent.putExtra(SHARE_EVENT_KEY, key)
            if (key != handledShareKey) {
                handledShareKey = key
                launchShare.value = InboundShareEvent(key, incomingShare)
            }
            setIntent(Intent())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SHARE_HANDLED_KEY, handledShareKey)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StylusInputDiagnostics.isStylusKey(event) && StylusInputDiagnostics.recordKeyEvent(event)) {
            return true
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

    private companion object {
        const val SHARE_HANDLED_KEY = "inbound_share_handled_key"
        const val SHARE_EVENT_KEY = "com.hashim.notepad.extra.SHARE_EVENT_KEY"
    }
}
