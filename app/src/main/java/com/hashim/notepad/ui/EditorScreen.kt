package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.ink.BrushSpec
import com.hashim.notepad.ink.InkSurfaceView
import com.hashim.notepad.ink.InkTool
import com.hashim.notepad.ink.PointerSample
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.pdf.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

private val PALETTE = listOf(0xFF1A1A1A, 0xFF1E5EFF, 0xFFD62839, 0xFF1B8A5A, 0xFFF5B301).map { it.toInt() }

@Composable
fun EditorScreen(repo: NotePadRepository, notebookId: String, pageId: String, onBack: () -> Unit, onNavigateToPage: (String) -> Unit) {
    val context = LocalContext.current
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    val page = pages.firstOrNull { it.id == pageId }
    val index = pages.indexOfFirst { it.id == pageId }

    var tool by rememberSaveableTool()
    var color by remember { mutableIntStateOf(PALETTE[0]) }
    var size by remember { mutableFloatStateOf(2.5f) }
    var fingerDraws by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var surfaceWidth by remember { mutableIntStateOf(0) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var dirtyStrokes by remember { mutableStateOf<List<Stroke>?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val spec = BrushSpec(tool, color, size)
    LaunchedEffect(spec, fingerDraws, surface) { surface?.let { it.brushSpec = spec; it.fingerDraws = fingerDraws } }

    // Load page geometry + strokes once the surface exists.
    LaunchedEffect(page?.id, surface) {
        val p = page ?: return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        if (loadedFor == p.id) return@LaunchedEffect
        s.setPageGeometry(p.widthPt, p.heightPt, p.kind)
        val bytes = repo.loadStrokes(p.id)
        val strokes = bytes?.let { b -> withContext(Dispatchers.Default) { runCatching { StrokeStore.decode(b) }.getOrElse { Log.e("NotePad", "decode", it); emptyList() } } } ?: emptyList()
        s.onStrokesChanged = null
        s.setStrokes(strokes)
        s.onStrokesChanged = { dirtyStrokes = it }
        loadedFor = p.id
    }

    // Render the PDF page bitmap for PDF pages.
    LaunchedEffect(page?.id, page?.assetId, surfaceWidth) {
        val p = page ?: return@LaunchedEffect
        if (p.kind != PageKind.PDF || p.assetId == null || surfaceWidth == 0) { pageBitmap = null; surface?.setPage(null); return@LaunchedEffect }
        val asset = repo.asset(p.assetId) ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) {
            runCatching { PdfPageSource(context, File(asset.localPath)).use { it.render(p.pdfPageIndex ?: 0, (surfaceWidth * 1.5f).toInt()) } }
                .onFailure { Log.e("NotePad", "render failed", it) }.getOrNull()
        }
        pageBitmap = bmp
        surface?.setPage(bmp)
    }

    // Debounced autosave.
    LaunchedEffect(dirtyStrokes) {
        val strokes = dirtyStrokes ?: return@LaunchedEffect
        val p = page ?: return@LaunchedEffect
        delay(600)
        val bytes = withContext(Dispatchers.Default) { StrokeStore.encode(strokes) }
        repo.saveStrokes(p.id, bytes, strokes.size)
    }
    DisposableEffect(Unit) {
        onDispose {
            val strokes = dirtyStrokes; val p = page
            if (strokes != null && p != null) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.saveStrokes(p.id, StrokeStore.encode(strokes), strokes.size) }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    ToolButton(Icons.Default.Edit, "Pen", tool == InkTool.PEN) { tool = InkTool.PEN }
                    ToolButton(Icons.Default.Brush, "Highlighter", tool == InkTool.HIGHLIGHTER) { tool = InkTool.HIGHLIGHTER }
                    ToolButton(Icons.Default.Clear, "Eraser", tool == InkTool.ERASER) { tool = InkTool.ERASER }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    PALETTE.forEach { c -> ColorDot(c, c == color) { color = c } }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    Slider(value = size, onValueChange = { size = it }, valueRange = 1f..10f, modifier = Modifier.width(110.dp))
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    IconButton(onClick = { surface?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { surface?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    IconButton(enabled = index > 0, onClick = { onNavigateToPage(pages[index - 1].id) }) { Icon(Icons.Default.ChevronLeft, "Previous page") }
                    Text(if (pages.isEmpty()) "" else "${index + 1}/${pages.size}", style = MaterialTheme.typography.labelMedium)
                    IconButton(enabled = index in 0 until pages.size - 1, onClick = { onNavigateToPage(pages[index + 1].id) }) { Icon(Icons.Default.ChevronRight, "Next page") }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                    FilterChip(selected = fingerDraws, onClick = { fingerDraws = !fingerDraws }, label = { Text("Finger draws") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = showLab, onClick = { showLab = !showLab }, label = { Text("Lab") })
                }
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (showLab) Text(
                text = "tool=${sample.toolType}  pressure=%.3f  tilt=%.0f°  orient=%.0f°  input→handler=%d ms  batched=%d  strokes=%d".format(
                    sample.pressure, sample.tilt, sample.orientation, sample.handlerLatencyMs, sample.historySize, sample.strokeCount
                ),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp).navigationBarsPadding(),
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.onSample = { sample = it }
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )
    }
}

@Composable
private fun rememberSaveableTool() = remember { mutableStateOf(InkTool.PEN) }

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
