package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.export.PdfExporter
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PALETTE = listOf(0xFF1A1A1A, 0xFF1E5EFF, 0xFFD62839, 0xFF1B8A5A, 0xFFF5B301).map { it.toInt() }
private val EXTRA_PALETTE = listOf(
    0xFF3449B5, 0xFF008D9D, 0xFF2E7D32, 0xFFFFB300,
    0xFFEF6C00, 0xFFD32F2F, 0xFF7E57C2, 0xFF80868B,
).map { it.toInt() }

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
    var markerOpacity by remember { mutableFloatStateOf(230f / 255f) }
    var highlighterOpacity by remember { mutableFloatStateOf(0.45f) }
    var recentColors by remember { mutableStateOf(emptyList<Int>()) }
    var fingerDraws by remember { mutableStateOf(false) }
    var zoomBox by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    var hasSelection by remember { mutableStateOf(false) }
    var pixelEraser by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    val exporter = remember(context, repo) { PdfExporter(context, repo) }
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

    val opacity = if (tool == InkTool.HIGHLIGHTER) highlighterOpacity else markerOpacity
    val spec = BrushSpec(tool, color, size, pen, opacity)
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

    var colorPop by remember { mutableStateOf(false) }
    var penPopover by remember { mutableStateOf<PenType?>(null) }
    var highlighterPopover by remember { mutableStateOf(false) }
    var morePop by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun selectTool(next: InkTool) {
        if (tool != next) {
            tool = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        penPopover = null
        highlighterPopover = false
    }

    fun selectPen(next: PenType) {
        if (tool == InkTool.PEN && pen == next) {
            penPopover = next
            return
        }
        tool = InkTool.PEN
        pen = next
        penPopover = null
        highlighterPopover = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun selectHighlighter() {
        if (tool == InkTool.HIGHLIGHTER) {
            highlighterPopover = true
            return
        }
        tool = InkTool.HIGHLIGHTER
        penPopover = null
        highlighterPopover = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun chooseColor(next: Int) {
        if (next != color) {
            recentColors = (listOf(color) + recentColors.filterNot { it == color || it == next })
                .distinct().take(8)
            color = next
        }
        colorPop = false
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = statusBarHeight + 76.dp),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.onSample = { sample = it }
                    v.onSelectionChanged = { hasSelection = it }
                    v.onZoomChanged = { zoomPercent = it }
                    v.onTextTap = { x, y, block -> if (block != null) editingBlock = block else newBlockAt = x to y }
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 1.dp,
        ) {
            Column {
                Spacer(Modifier.height(statusBarHeight))
                Row(
                    Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    listOf(PenType.BALLPOINT, PenType.PENCIL, PenType.FOUNTAIN, PenType.MARKER).forEach { type ->
                        Box {
                            ShelfCanvasItem(
                                label = type.label,
                                style = type.toShelfStyle(),
                                selected = tool == InkTool.PEN && pen == type,
                                color = color,
                                onClick = { selectPen(type) },
                            )
                            DropdownMenu(
                                expanded = penPopover == type,
                                onDismissRequest = { penPopover = null },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                BrushSettingsPopover(
                                    title = type.label,
                                    color = color,
                                    brushSize = size,
                                    onSizeChange = { size = it },
                                    opacity = markerOpacity,
                                    onOpacityChange = { markerOpacity = it },
                                    showOpacity = type == PenType.MARKER,
                                )
                            }
                        }
                    }
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 18.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Box {
                        ShelfCanvasItem(
                            label = "Highlighter",
                            style = ShelfDrawing.HIGHLIGHTER,
                            selected = tool == InkTool.HIGHLIGHTER,
                            color = color,
                            onClick = { selectHighlighter() },
                        )
                        DropdownMenu(
                            expanded = highlighterPopover,
                            onDismissRequest = { highlighterPopover = false },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            BrushSettingsPopover(
                                title = "Highlighter",
                                color = color,
                                    brushSize = size,
                                onSizeChange = { size = it },
                                opacity = highlighterOpacity,
                                onOpacityChange = { highlighterOpacity = it },
                                showOpacity = true,
                            )
                        }
                    }
                    Box {
                        ShelfCanvasItem(
                            label = PenType.CALLIGRAPHY.label,
                            style = ShelfDrawing.CALLIGRAPHY,
                            selected = tool == InkTool.PEN && pen == PenType.CALLIGRAPHY,
                            color = color,
                            onClick = { selectPen(PenType.CALLIGRAPHY) },
                        )
                        DropdownMenu(
                            expanded = penPopover == PenType.CALLIGRAPHY,
                            onDismissRequest = { penPopover = null },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            BrushSettingsPopover(
                                title = PenType.CALLIGRAPHY.label,
                                color = color,
                                brushSize = size,
                                onSizeChange = { size = it },
                                opacity = markerOpacity,
                                onOpacityChange = { markerOpacity = it },
                                showOpacity = false,
                            )
                        }
                    }
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 18.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ShelfCanvasItem("Eraser", ShelfDrawing.ERASER, tool == InkTool.ERASER, color) { selectTool(InkTool.ERASER) }
                    ShelfCanvasItem("Lasso", ShelfDrawing.LASSO, tool == InkTool.LASSO, color) { selectTool(InkTool.LASSO) }
                    ShelfCanvasItem("Text", ShelfDrawing.TEXT, tool == InkTool.TEXT, color) { selectTool(InkTool.TEXT) }
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 18.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Box {
                        IconButton(onClick = { colorPop = true }, modifier = Modifier.size(40.dp)) {
                            ColorWheelButton(color)
                        }
                        DropdownMenu(expanded = colorPop, onDismissRequest = { colorPop = false }, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(8.dp)) {
                                (PALETTE + EXTRA_PALETTE).chunked(4).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        row.forEach { swatch ->
                                            ColorDot(swatch, swatch == color, 32.dp) { chooseColor(swatch) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    recentColors.filterNot { it == color }.take(2).forEach { swatch ->
                        ColorDot(swatch, false, 26.dp) { chooseColor(swatch) }
                    }
                }
                IconButton(onClick = { surface?.undo() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                IconButton(onClick = { surface?.redo() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            runCatching { exporter.exportNotebook(notebookId, notebook?.title ?: "Notebook") }
                                .onSuccess { exporter.share(it) }
                                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Outlined.IosShare, "Share notebook as PDF")
                }
                    Box {
                        IconButton(onClick = { morePop = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = morePop, onDismissRequest = { morePop = false }, shape = MaterialTheme.shapes.large) {
                            if (hasSelection) {
                                DropdownMenuItem(
                                    text = { Text("Delete selection") },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { surface?.deleteSelection(); morePop = false },
                                )
                            }
                            ToggleItem("Zoom box", "Magnified line for small writing", zoomBox) { zoomBox = it }
                            ToggleItem("Finger draws", "Otherwise finger pans and zooms", fingerDraws) { fingerDraws = it }
                            ToggleItem("Pen diagnostics", "Pressure, tilt and latency", showLab) { showLab = it }
                            ToggleItem("Pixel eraser", "Erase pixels instead of whole strokes", pixelEraser) { pixelEraser = it }
                        }
                    }
                }
            }
        }

        if (pages.isNotEmpty()) Row(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 16.dp, bottom = if (showLab) 64.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1} / ${pages.size}    ${zoomPercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pages.size > 1) {
                IconButton(enabled = index > 0, onClick = { if (index > 0) onNavigateToPage(pages[index - 1].id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ChevronLeft, "Previous page", modifier = Modifier.size(18.dp))
                }
                IconButton(enabled = index in 0 until pages.size - 1, onClick = { if (index < pages.lastIndex) onNavigateToPage(pages[index + 1].id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ChevronRight, "Next page", modifier = Modifier.size(18.dp))
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

private enum class ShelfDrawing { BALLPOINT, PENCIL, FOUNTAIN, MARKER, HIGHLIGHTER, CALLIGRAPHY, ERASER, LASSO, TEXT }

private fun PenType.toShelfStyle(): ShelfDrawing = when (this) {
    PenType.BALLPOINT -> ShelfDrawing.BALLPOINT
    PenType.PENCIL -> ShelfDrawing.PENCIL
    PenType.FOUNTAIN -> ShelfDrawing.FOUNTAIN
    PenType.MARKER -> ShelfDrawing.MARKER
    PenType.CALLIGRAPHY -> ShelfDrawing.CALLIGRAPHY
}

@Composable
private fun ShelfCanvasItem(label: String, style: ShelfDrawing, selected: Boolean, color: Int, onClick: () -> Unit) {
    val rise by animateDpAsState(
        targetValue = if (selected) (-12).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "$label-rise",
    )
    val dark = MaterialTheme.colorScheme.isDark
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        Modifier.width(34.dp).height(76.dp).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.size(28.dp, 64.dp).offset(y = rise).clipToBounds()) {
            val centerX = size.width / 2f
            val bodyTop = 16.dp.toPx()
            val bodyBottom = size.height + 8.dp.toPx()
            val bodyLeft = 6.dp.toPx()
            val bodyWidth = size.width - 12.dp.toPx()
            val bodyCorner = CornerRadius(5.dp.toPx())
            val penColor = Color(color)
            val itemColor = if (style == ShelfDrawing.HIGHLIGHTER) penColor.copy(alpha = 0.45f) else penColor
            val bodyBrush = Brush.verticalGradient(
                colors = if (dark) listOf(Color(0xFF3A3B40), Color(0xFF2A2B2F)) else listOf(Color.White, Color(0xFFE6E8EC)),
                startY = bodyTop,
                endY = bodyBottom,
            )
            val bodyTopLeft = Offset(bodyLeft, bodyTop)
            val bodySize = Size(bodyWidth, bodyBottom - bodyTop)

            if (style == ShelfDrawing.ERASER) {
                val eraserTop = 18.dp.toPx()
                val eraserLeft = 4.dp.toPx()
                val eraserWidth = size.width - 8.dp.toPx()
                drawRoundRect(
                    color = if (dark) Color(0xFFE6E7EA) else Color.White,
                    topLeft = Offset(eraserLeft, eraserTop),
                    size = Size(eraserWidth, 31.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = Color(0xFFB8BDC5),
                    topLeft = Offset(eraserLeft, eraserTop + 12.dp.toPx()),
                    size = Size(eraserWidth, 6.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(eraserLeft, eraserTop),
                    size = Size(eraserWidth, 31.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = DrawStroke(width = 1f),
                )
                return@Canvas
            }

            drawRoundRect(bodyBrush, bodyTopLeft, bodySize, bodyCorner)
            drawRoundRect(outline, bodyTopLeft, bodySize, bodyCorner, style = DrawStroke(width = 1f))

            when (style) {
                ShelfDrawing.BALLPOINT -> {
                    val tip = Path().apply {
                        moveTo(centerX, 3.dp.toPx())
                        lineTo(centerX - 5.dp.toPx(), 17.dp.toPx())
                        lineTo(centerX + 5.dp.toPx(), 17.dp.toPx())
                        close()
                    }
                    drawPath(tip, itemColor)
                    drawCircle(itemColor, 1.4.dp.toPx(), Offset(centerX, 3.dp.toPx()))
                }
                ShelfDrawing.PENCIL -> {
                    val wood = Path().apply {
                        moveTo(centerX, 3.dp.toPx())
                        lineTo(centerX - 6.dp.toPx(), 18.dp.toPx())
                        lineTo(centerX + 6.dp.toPx(), 18.dp.toPx())
                        close()
                    }
                    val lead = Path().apply {
                        moveTo(centerX, 3.dp.toPx())
                        lineTo(centerX - 1.8.dp.toPx(), 8.dp.toPx())
                        lineTo(centerX + 1.8.dp.toPx(), 8.dp.toPx())
                        close()
                    }
                    drawPath(wood, Color(0xFFE9C89A))
                    drawPath(lead, itemColor)
                }
                ShelfDrawing.FOUNTAIN -> {
                    val nib = Path().apply {
                        moveTo(centerX, 3.dp.toPx())
                        lineTo(centerX - 7.dp.toPx(), 17.dp.toPx())
                        lineTo(centerX - 4.dp.toPx(), 23.dp.toPx())
                        lineTo(centerX + 4.dp.toPx(), 23.dp.toPx())
                        lineTo(centerX + 7.dp.toPx(), 17.dp.toPx())
                        close()
                    }
                    drawPath(nib, Color(0xFFB9BEC7))
                    drawLine(Color(0xFF656A72), Offset(centerX, 5.dp.toPx()), Offset(centerX, 21.dp.toPx()), 1.dp.toPx())
                    drawCircle(itemColor, 1.5.dp.toPx(), Offset(centerX, 15.dp.toPx()))
                }
                ShelfDrawing.MARKER -> {
                    drawRoundRect(
                        color = itemColor,
                        topLeft = Offset(3.dp.toPx(), 6.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), 13.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    drawRect(itemColor, Offset(bodyLeft, bodyTop), Size(bodyWidth, 5.dp.toPx()))
                }
                ShelfDrawing.HIGHLIGHTER -> {
                    drawRoundRect(
                        color = itemColor,
                        topLeft = Offset(3.dp.toPx(), 6.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), 14.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                    drawRect(itemColor, Offset(bodyLeft, bodyTop), Size(bodyWidth, 5.dp.toPx()))
                }
                ShelfDrawing.CALLIGRAPHY -> {
                    val nib = Path().apply {
                        moveTo(4.dp.toPx(), 15.dp.toPx())
                        lineTo(13.dp.toPx(), 3.dp.toPx())
                        lineTo(24.dp.toPx(), 8.dp.toPx())
                        lineTo(16.dp.toPx(), 21.dp.toPx())
                        lineTo(8.dp.toPx(), 19.dp.toPx())
                        close()
                    }
                    drawPath(nib, itemColor)
                    drawLine(outline, Offset(9.dp.toPx(), 18.dp.toPx()), Offset(20.dp.toPx(), 8.dp.toPx()), 1.dp.toPx())
                }
                ShelfDrawing.LASSO -> {
                    val loop = Path().apply {
                        moveTo(9.dp.toPx(), 39.dp.toPx())
                        cubicTo(3.dp.toPx(), 31.dp.toPx(), 9.dp.toPx(), 26.dp.toPx(), 15.dp.toPx(), 31.dp.toPx())
                        cubicTo(21.dp.toPx(), 36.dp.toPx(), 17.dp.toPx(), 42.dp.toPx(), 12.dp.toPx(), 40.dp.toPx())
                    }
                    drawPath(
                        loop,
                        onSurface,
                        style = DrawStroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                    )
                }
                ShelfDrawing.TEXT -> {
                    drawLine(onSurface, Offset(9.dp.toPx(), 31.dp.toPx()), Offset(19.dp.toPx(), 31.dp.toPx()), 2.dp.toPx())
                    drawLine(onSurface, Offset(14.dp.toPx(), 31.dp.toPx()), Offset(14.dp.toPx(), 42.dp.toPx()), 2.dp.toPx())
                }
                ShelfDrawing.ERASER -> Unit
            }
        }
    }
}

@Composable
private fun BrushSettingsPopover(
    title: String,
    color: Int,
    brushSize: Float,
    onSizeChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showOpacity: Boolean,
) {
    Column(Modifier.width(248.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            drawLine(
                Color(color).copy(alpha = if (showOpacity) opacity else 1f),
                Offset(12.dp.toPx(), size.height / 2f),
                Offset(size.width - 12.dp.toPx(), size.height / 2f),
                strokeWidth = (brushSize * 1.25f).dp.toPx().coerceAtLeast(1.dp.toPx()),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Text("Stroke width", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = brushSize, onValueChange = onSizeChange, valueRange = 1f..10f)
        if (showOpacity) {
            Text("Opacity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0.1f..1f)
        }
    }
}

@Composable
private fun ColorWheelButton(color: Int) {
    Canvas(Modifier.size(32.dp)) {
        val radius = size.minDimension / 2f
        val ringWidth = 3.dp.toPx()
        drawCircle(Color(color), radius = radius - ringWidth)
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            ),
            radius = radius - ringWidth / 2f,
            style = DrawStroke(width = ringWidth),
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
private fun ColorDot(argb: Int, selected: Boolean, dotSize: Dp = 32.dp, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    Box(
        Modifier.size(dotSize).clip(CircleShape).clickable(onClick = onClick)
            .border((2 * ring).dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ring), CircleShape)
            .padding(dotSize * 0.14f).background(Color(argb), CircleShape)
    )
}
