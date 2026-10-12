package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hashim.notepad.ink.BrushSpec
import com.hashim.notepad.ink.InkSurfaceView
import com.hashim.notepad.ink.InkTool
import com.hashim.notepad.ink.PointerSample
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.pdf.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PALETTE = listOf(0xFF1A1A1A, 0xFF1E5EFF, 0xFFD62839, 0xFF1B8A5A, 0xFFF5B301).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(InkTool.PEN) }
    var color by remember { mutableIntStateOf(PALETTE[0]) }
    var size by remember { mutableFloatStateOf(3f) }
    var fingerDraws by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var pdf by remember { mutableStateOf<PdfPageSource?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var surfaceWidth by remember { mutableIntStateOf(0) }
    val strokeFile = remember { File(context.filesDir, "spike-strokes.bin") }

    fun renderPage() {
        val src = pdf ?: return
        val w = surfaceWidth.takeIf { it > 0 } ?: return
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { runCatching { src.render(pageIndex, w) }.getOrNull() }
            pageBitmap = bmp
            surface?.setPage(bmp)
        }
    }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { PdfPageSource(context, uri) }
            .onSuccess { pdf?.close(); pdf = it; pageIndex = 0; renderPage() }
            .onFailure { Toast.makeText(context, "Could not open PDF: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(surfaceWidth, pdf) { renderPage() }
    DisposableEffect(Unit) { onDispose { pdf?.close() } }

    val spec = BrushSpec(tool, color, size)
    LaunchedEffect(spec, fingerDraws) { surface?.let { it.brushSpec = spec; it.fingerDraws = fingerDraws } }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ToolButton(Icons.Default.Edit, "Pen", tool == InkTool.PEN) { tool = InkTool.PEN }
                    ToolButton(Icons.Default.Brush, "Highlighter", tool == InkTool.HIGHLIGHTER) { tool = InkTool.HIGHLIGHTER }
                    ToolButton(Icons.Default.Clear, "Eraser", tool == InkTool.ERASER) { tool = InkTool.ERASER }
                    VerticalDivider(Modifier.height(24.dp))
                    PALETTE.forEach { c -> ColorDot(c, c == color) { color = c } }
                    VerticalDivider(Modifier.height(24.dp))
                    Slider(value = size, onValueChange = { size = it }, valueRange = 1f..12f, modifier = Modifier.width(120.dp))
                    VerticalDivider(Modifier.height(24.dp))
                    IconButton(onClick = { surface?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { surface?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { surface?.clear() }) { Icon(Icons.Default.DeleteSweep, "Clear") }
                    VerticalDivider(Modifier.height(24.dp))
                    IconButton(onClick = { openPdf.launch(arrayOf("application/pdf")) }) { Icon(Icons.Default.PictureAsPdf, "Open PDF") }
                    pdf?.let { src ->
                        IconButton(enabled = pageIndex > 0, onClick = { pageIndex--; renderPage() }) { Icon(Icons.Default.ChevronLeft, "Previous page") }
                        Text("${pageIndex + 1}/${src.pageCount}", style = MaterialTheme.typography.labelMedium)
                        IconButton(enabled = pageIndex < src.pageCount - 1, onClick = { pageIndex++; renderPage() }) { Icon(Icons.Default.ChevronRight, "Next page") }
                    }
                    VerticalDivider(Modifier.height(24.dp))
                    IconButton(onClick = {
                        surface?.let { s -> runCatching { StrokeStore.save(strokeFile, s.strokes()) }
                            .onSuccess { Toast.makeText(context, "Saved ${s.strokes().size} strokes", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() } }
                    }) { Icon(Icons.Default.Save, "Save strokes") }
                    IconButton(onClick = {
                        surface?.let { s -> runCatching { StrokeStore.load(strokeFile) }
                            .onSuccess { list -> s.setStrokes(list); Toast.makeText(context, "Loaded ${list.size} strokes", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "Load failed: ${it.message}", Toast.LENGTH_LONG).show() } }
                    }) { Icon(Icons.Default.FolderOpen, "Load strokes") }
                    VerticalDivider(Modifier.height(24.dp))
                    Text("Finger", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = fingerDraws, onCheckedChange = { fingerDraws = it })
                }
                HorizontalDivider()
            }
        },
        bottomBar = {
            Text(
                text = "tool=${sample.toolType}  pressure=%.3f  tilt=%.0f°  orient=%.0f°  input→handler=%d ms  batched=%d  strokes=%d".format(
                    sample.pressure, sample.tilt, sample.orientation, sample.handlerLatencyMs, sample.historySize, sample.strokeCount
                ),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp).navigationBarsPadding(),
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.brushSpec = spec
                    v.fingerDraws = fingerDraws
                    v.onSample = { sample = it }
                    v.setPage(pageBitmap)
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    FilledIconToggleButton(checked = selected, onCheckedChange = { onClick() }) { Icon(icon, label) }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).padding(2.dp).clickable(onClick = onClick)
            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .padding(3.dp).background(Color(argb), CircleShape)
    )
}
