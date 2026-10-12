package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.ink.BrushSpec
import com.hashim.notepad.ink.InkSurfaceView
import com.hashim.notepad.ink.InkTool
import com.hashim.notepad.ink.PenType
import com.hashim.notepad.ink.PointerSample
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.recognition.InkRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

private val PALETTE = listOf(0xFF1A1A1A, 0xFF1E5EFF, 0xFFD62839, 0xFF1B8A5A, 0xFFF5B301).map { it.toInt() }

@Composable
fun EditorScreen(repo: NotePadRepository, notebookId: String, pageId: String, recognizer: InkRecognizer, onBack: () -> Unit, onNavigateToPage: (String) -> Unit) {
    val context = LocalContext.current
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val notebook = notebooks.firstOrNull { it.id == notebookId }
    val language = notebook?.language ?: "ar"
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    val page = pages.firstOrNull { it.id == pageId }
    val index = pages.indexOfFirst { it.id == pageId }

    var tool by rememberSaveableTool()
    val preferences = remember(context) { context.getSharedPreferences("notepad", 0) }
    var pen by rememberSaveable {
        mutableStateOf(
            runCatching {
                PenType.valueOf(preferences.getString("pen", PenType.FOUNTAIN.name) ?: PenType.FOUNTAIN.name)
            }.getOrDefault(PenType.FOUNTAIN)
        )
    }
    LaunchedEffect(pen) { preferences.edit().putString("pen", pen.name).apply() }
    var color by remember { mutableIntStateOf(PALETTE[0]) }
    var size by remember { mutableFloatStateOf(2.5f) }
    var fingerDraws by remember { mutableStateOf(false) }
    var zoomBox by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    var hasSelection by remember { mutableStateOf(false) }
    var pixelEraser by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    LaunchedEffect(pixelEraser, surface) { surface?.pixelEraser = pixelEraser }
    LaunchedEffect(notebook?.coverColor, surface) {
        val hex = notebook?.coverColor ?: return@LaunchedEffect
        runCatching { android.graphics.Color.parseColor(hex) }.onSuccess { surface?.setPaperColor(it) }
    }
    var surfaceWidth by remember { mutableIntStateOf(0) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var dirtyStrokes by remember { mutableStateOf<List<Stroke>?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val textBlocks by repo.textBlocks(pageId).collectAsState(initial = emptyList())
    var editingBlock by remember { mutableStateOf<TextBlock?>(null) }
    var newBlockAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(textBlocks, surface) { surface?.textBlocks = textBlocks }

    val spec = BrushSpec(tool, color, size, pen)
    LaunchedEffect(surface, zoomBox, language) { surface?.setZoomBox(zoomBox, language != "en") }
    LaunchedEffect(spec, fingerDraws, surface) { surface?.let { it.brushSpec = spec; it.fingerDraws = fingerDraws; if (spec.tool != InkTool.LASSO) it.clearSelection() } }

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
        delay(2500)
        if (runCatching { recognizer.isModelDownloaded(language) }.getOrDefault(false)) {
            runCatching { recognizer.recognize(strokes, language, p.widthPt, p.heightPt) }
                .onSuccess { repo.saveRecognition(p.id, "ink", language, it) }
                .onFailure { Log.w("NotePad", "recognition", it) }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val strokes = dirtyStrokes; val p = page
            if (strokes != null && p != null) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.saveStrokes(p.id, StrokeStore.encode(strokes), strokes.size) }
            }
        }
    }

    var chrome by rememberSaveable { mutableStateOf(true) }
    var colorPop by remember { mutableStateOf(false) }
    var sizePop by remember { mutableStateOf(false) }
    var penPop by remember { mutableStateOf(false) }
    var morePop by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun selectTool(next: InkTool) {
        if (tool != next) {
            tool = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.onSample = { sample = it }
                    v.onSelectionChanged = { hasSelection = it }
                    v.onTextTap = { x, y, block -> if (block != null) editingBlock = block else newBlockAt = x to y }
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )

        // ---- floating toolbar
        androidx.compose.animation.AnimatedVisibility(
            visible = chrome,
            enter = androidx.compose.animation.slideInVertically(tween(Motion.medium, easing = Motion.emphasizedDecelerate)) { -it } + fadeIn(tween(Motion.medium)),
            exit = androidx.compose.animation.slideOutVertically(tween(Motion.short, easing = Motion.emphasizedAccelerate)) { -it } + fadeOut(tween(Motion.short)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp, start = 12.dp, end = 12.dp),
        ) {
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    PillDivider()
                    Box {
                        ToolButton(
                            Icons.Default.Edit,
                            "Pen",
                            tool == InkTool.PEN,
                            onLongClick = { selectTool(InkTool.PEN); penPop = true },
                        ) {
                            if (tool == InkTool.PEN) penPop = true else selectTool(InkTool.PEN)
                        }
                        DropdownMenu(expanded = penPop, onDismissRequest = { penPop = false }, shape = MaterialTheme.shapes.large) {
                            PenType.values().forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            PenTypePreview(type, color)
                                            Text(type.label)
                                        }
                                    },
                                    trailingIcon = { if (pen == type) Icon(Icons.Default.Check, "Selected") },
                                    onClick = {
                                        if (pen != type) {
                                            pen = type
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        penPop = false
                                    },
                                )
                            }
                        }
                    }
                    ToolButton(Icons.Default.Brush, "Highlighter", tool == InkTool.HIGHLIGHTER) { selectTool(InkTool.HIGHLIGHTER) }
                    ToolButton(Icons.Default.Clear, "Eraser", tool == InkTool.ERASER) { if (tool == InkTool.ERASER) pixelEraser = !pixelEraser else selectTool(InkTool.ERASER) }
                    androidx.compose.animation.AnimatedVisibility(tool == InkTool.ERASER) {
                        Text(if (pixelEraser) "pixel" else "stroke", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                    }
                    ToolButton(Icons.Default.TextFields, "Text", tool == InkTool.TEXT) { selectTool(InkTool.TEXT) }
                    ToolButton(Icons.Default.HighlightAlt, "Lasso", tool == InkTool.LASSO) { selectTool(InkTool.LASSO) }
                    androidx.compose.animation.AnimatedVisibility(hasSelection) {
                        IconButton(onClick = { surface?.deleteSelection() }) { Icon(Icons.Default.DeleteOutline, "Delete selection", tint = MaterialTheme.colorScheme.error) }
                    }
                    PillDivider()
                    Box {
                        IconButton(onClick = { colorPop = true; sizePop = false }) {
                            Box(Modifier.size(22.dp).background(Color(color), CircleShape).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        }
                        DropdownMenu(expanded = colorPop, onDismissRequest = { colorPop = false }, shape = MaterialTheme.shapes.large) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PALETTE.forEach { c -> ColorDot(c, c == color) { color = c; colorPop = false } }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { sizePop = true; colorPop = false }) {
                            Box(Modifier.size((6 + size * 1.6f).dp.coerceAtMost(22.dp)).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                        }
                        DropdownMenu(expanded = sizePop, onDismissRequest = { sizePop = false }, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(220.dp)) {
                                Text("Stroke width", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxWidth(0.8f).height((size * 1.6f).dp).background(Color(color), CircleShape))
                                }
                                Slider(value = size, onValueChange = { size = it }, valueRange = 1f..10f)
                            }
                        }
                    }
                    PillDivider()
                    IconButton(onClick = { surface?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { surface?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    PillDivider()
                    Box {
                        IconButton(onClick = { morePop = true }) { Icon(Icons.Default.MoreHoriz, "More") }
                        DropdownMenu(expanded = morePop, onDismissRequest = { morePop = false }, shape = MaterialTheme.shapes.large) {
                            ToggleItem("Zoom box", "Magnified line for small writing", zoomBox) { zoomBox = it }
                            ToggleItem("Finger draws", "Otherwise finger pans and zooms", fingerDraws) { fingerDraws = it }
                            ToggleItem("Pen diagnostics", "Pressure, tilt and latency", showLab) { showLab = it }
                        }
                    }
                    IconButton(onClick = { chrome = false }) { Icon(Icons.Default.KeyboardArrowUp, "Hide toolbar") }
                }
            }
        }
        // collapsed handle
        androidx.compose.animation.AnimatedVisibility(
            visible = !chrome, enter = fadeIn(tween(Motion.medium)), exit = fadeOut(tween(Motion.short)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp),
        ) {
            Surface(onClick = { chrome = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shadowElevation = 4.dp) {
                Icon(Icons.Default.KeyboardArrowDown, "Show toolbar", Modifier.padding(horizontal = 18.dp, vertical = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- page pill
        androidx.compose.animation.AnimatedVisibility(
            visible = chrome && pages.isNotEmpty(), enter = fadeIn(tween(Motion.medium)), exit = fadeOut(tween(Motion.short)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (zoomBox) 64.dp else 16.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(enabled = index > 0, onClick = { onNavigateToPage(pages[index - 1].id) }) { Icon(Icons.Default.ChevronLeft, "Previous page") }
                    Text("${index + 1} / ${pages.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
                    IconButton(enabled = index in 0 until pages.size - 1, onClick = { onNavigateToPage(pages[index + 1].id) }) { Icon(Icons.Default.ChevronRight, "Next page") }
                }
            }
        }

        if (zoomBox) Row(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { surface?.zoomShift(-1) }) { Icon(Icons.Default.ChevronLeft, "Back") }
            IconButton(onClick = { surface?.zoomShift(1) }) { Icon(Icons.Default.ChevronRight, "Forward") }
            IconButton(onClick = { surface?.zoomNewLine() }) { Icon(Icons.Default.KeyboardReturn, "New line") }
        }

        if (showLab) Text(
            text = "tool=${sample.toolType}  pressure=%.3f  tilt=%.0f°  orient=%.0f°  input→handler=%d ms  batched=%d  strokes=%d".format(
                sample.pressure, sample.tilt, sample.orientation, sample.handlerLatencyMs, sample.historySize, sample.strokeCount
            ),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.shapes.small).padding(8.dp),
        )
    }

    newBlockAt?.let { (x, y) ->
        TextBlockDialog(initial = "", onDismiss = { newBlockAt = null }, onDelete = null) { content ->
            val p = page
            if (p != null) scope.launch { repo.addTextBlock(p.id, (x - 10f).coerceIn(0f, p.widthPt - 120f), y, (p.widthPt - x).coerceIn(120f, p.widthPt - 24f), content) }
            newBlockAt = null
        }
    }
    editingBlock?.let { b ->
        TextBlockDialog(initial = b.content, onDismiss = { editingBlock = null },
            onDelete = { scope.launch { repo.deleteTextBlock(b.id, b.pageId) }; editingBlock = null }) { content ->
            scope.launch { repo.updateTextBlock(b.id, b.pageId, content = content) }
            editingBlock = null
        }
    }
}

@Composable
private fun TextBlockDialog(initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "New text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 3, maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("اكتب هنا · لکھیں · Type here") },
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun rememberSaveableTool() = remember { mutableStateOf(InkTool.PEN) }

@Composable
private fun PillDivider() { VerticalDivider(Modifier.height(22.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant) }

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(Motion.short), label = "toolBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, tween(Motion.short), label = "toolFg")
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = fg) }
}

@Composable
private fun PenTypePreview(pen: PenType, argb: Int) {
    Canvas(Modifier.size(32.dp, 22.dp)) {
        val path = Path().apply {
            moveTo(2.dp.toPx(), size.height * 0.78f)
            cubicTo(size.width * 0.24f, size.height * 0.04f, size.width * 0.68f, size.height * 0.98f, size.width - 2.dp.toPx(), size.height * 0.22f)
        }
        val (width, alpha) = when (pen) {
            PenType.BALLPOINT -> 1.3f to 1f
            PenType.FOUNTAIN -> 2f to 1f
            PenType.PENCIL -> 1.5f to 0.58f
            PenType.MARKER -> 4f to 0.9f
            PenType.CALLIGRAPHY -> 3.5f to 1f
        }
        drawPath(
            path,
            Color(argb).copy(alpha = alpha),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = width, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun ToggleItem(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Column { Text(title); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingIcon = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(Motion.short), label = "ring")
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick)
            .border((2 * ring).dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ring), CircleShape)
            .padding(4.dp).background(Color(argb), CircleShape)
    )
}
