package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.hashim.notepad.ink.StylusButtonAction
import com.hashim.notepad.ink.StylusInputDiagnostics
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

private data class ToolBrushSettings(val color: Int, val size: Float, val opacity: Float)
private data class DrawingToolSelection(val tool: InkTool, val pen: PenType)

private fun PenType.defaultOpacity() = if (this == PenType.MARKER) 230f / 255f else 1f

private fun readStylusButtonAction(
    preferences: android.content.SharedPreferences,
    key: String,
    default: StylusButtonAction,
) = runCatching {
    StylusButtonAction.valueOf(preferences.getString(key, default.name) ?: default.name)
}.getOrDefault(default)

private fun readBrushSettings(
    preferences: android.content.SharedPreferences,
    key: String,
    defaults: ToolBrushSettings,
) = ToolBrushSettings(
    color = preferences.getInt("$key.color", defaults.color),
    size = preferences.getFloat("$key.size", defaults.size),
    opacity = preferences.getFloat("$key.opacity", defaults.opacity),
)

private fun writeBrushSettings(
    preferences: android.content.SharedPreferences,
    key: String,
    settings: ToolBrushSettings,
) {
    preferences.edit()
        .putInt("$key.color", settings.color)
        .putFloat("$key.size", settings.size)
        .putFloat("$key.opacity", settings.opacity)
        .apply()
}

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
    var penSettings by remember(preferences) {
        mutableStateOf(
            PenType.values().associateWith { type ->
                readBrushSettings(
                    preferences,
                    "pen.${type.name.lowercase()}",
                    ToolBrushSettings(0xFF000000.toInt(), 2.5f, type.defaultOpacity()),
                )
            },
        )
    }
    var highlighterSettings by remember(preferences) {
        mutableStateOf(
            readBrushSettings(
                preferences,
                "highlighter",
                ToolBrushSettings(0xFFFFD54F.toInt(), 3.25f, 0.45f),
            ),
        )
    }
    var alwaysStraight by remember(preferences) { mutableStateOf(preferences.getBoolean("highlighter.alwaysStraight", false)) }
    var recentColors by remember { mutableStateOf(emptyList<Int>()) }
    var fingerDraws by remember { mutableStateOf(false) }
    var zoomBox by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    val lastStylusEvent by StylusInputDiagnostics.lastEvent.collectAsState()
    var hasSelection by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var pixelEraser by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var primaryButtonAction by remember(preferences) {
        mutableStateOf(readStylusButtonAction(preferences, "stylus.primaryAction", StylusButtonAction.SWITCH_TO_ERASER))
    }
    var secondaryButtonAction by remember(preferences) {
        mutableStateOf(readStylusButtonAction(preferences, "stylus.secondaryAction", StylusButtonAction.PREVIOUS_PEN))
    }
    var previousDrawingSelection by remember { mutableStateOf(DrawingToolSelection(InkTool.PEN, pen)) }
    var eraserReturnSelection by remember { mutableStateOf<DrawingToolSelection?>(null) }
    var showPenButtonSettings by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var rulerVisible by remember { mutableStateOf(false) }
    val darkTheme = MaterialTheme.colorScheme.isDark
    val toolbarColor = when {
        darkTheme -> Color(0xFF111111)
        LocalThemeMode.current == ThemeMode.SEPIA -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color(0xFFEDEDED)
    }
    val deskColor = toolbarColor
    val activeBrushSettings = if (tool == InkTool.HIGHLIGHTER) highlighterSettings else penSettings[pen] ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, pen.defaultOpacity())
    val color = activeBrushSettings.color
    val size = activeBrushSettings.size
    val exporter = remember(context, repo) { PdfExporter(context, repo) }
    fun updateActiveBrushSettings(update: (ToolBrushSettings) -> ToolBrushSettings) {
        val updated = update(activeBrushSettings)
        if (tool == InkTool.HIGHLIGHTER) {
            highlighterSettings = updated
            writeBrushSettings(preferences, "highlighter", updated)
        } else {
            penSettings = penSettings + (pen to updated)
            writeBrushSettings(preferences, "pen.${pen.name.lowercase()}", updated)
        }
    }
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

    val spec = BrushSpec(tool, color, size, pen, activeBrushSettings.opacity)
    LaunchedEffect(surface, zoomBox, language) { surface?.setZoomBox(zoomBox, language != "en") }
    LaunchedEffect(surface, rulerVisible) { surface?.setRulerVisible(rulerVisible) }
    LaunchedEffect(surface, darkTheme) { surface?.setRulerDarkTheme(darkTheme) }
    LaunchedEffect(surface, deskColor) { surface?.setDeskColor(deskColor.toArgb()) }
    LaunchedEffect(surface, alwaysStraight) { surface?.highlighterAlwaysStraight = alwaysStraight }
    LaunchedEffect(surface, primaryButtonAction, secondaryButtonAction) {
        surface?.primaryButtonAction = primaryButtonAction
        surface?.secondaryButtonAction = secondaryButtonAction
    }
    LaunchedEffect(primaryButtonAction) { preferences.edit().putString("stylus.primaryAction", primaryButtonAction.name).apply() }
    LaunchedEffect(secondaryButtonAction) { preferences.edit().putString("stylus.secondaryAction", secondaryButtonAction.name).apply() }
    LaunchedEffect(spec, fingerDraws, surface) { surface?.let { it.brushSpec = spec; it.fingerDraws = fingerDraws; if (spec.tool != InkTool.LASSO) it.clearSelection() } }
    DisposableEffect(surface) {
        val target = surface
        if (target == null) return@DisposableEffect onDispose { }
        val listener: (KeyEvent) -> Unit = target::handleStylusKeyEvent
        StylusInputDiagnostics.onStylusKeyEvent = listener
        onDispose {
            if (StylusInputDiagnostics.onStylusKeyEvent === listener) StylusInputDiagnostics.onStylusKeyEvent = null
        }
    }

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

    fun currentDrawingSelection() = DrawingToolSelection(tool, pen)
    fun isDrawingSelection(selection: DrawingToolSelection) =
        selection.tool == InkTool.PEN || selection.tool == InkTool.HIGHLIGHTER

    fun switchToEraser() {
        if (tool == InkTool.ERASER) {
            val restore = eraserReturnSelection ?: previousDrawingSelection
            tool = restore.tool
            pen = restore.pen
            previousDrawingSelection = restore
            eraserReturnSelection = null
        } else {
            val current = currentDrawingSelection()
            val restore = current.takeIf(::isDrawingSelection) ?: previousDrawingSelection
            if (isDrawingSelection(current)) previousDrawingSelection = current
            eraserReturnSelection = restore
            tool = InkTool.ERASER
        }
        penPopover = null
        highlighterPopover = false
        colorPop = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun switchToPreviousPen() {
        val current = currentDrawingSelection()
        val previous = previousDrawingSelection
        if (current != previous) {
            if (isDrawingSelection(current)) previousDrawingSelection = current
            tool = previous.tool
            pen = previous.pen
            eraserReturnSelection = null
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        penPopover = null
        highlighterPopover = false
        colorPop = false
    }

    fun handleStylusToolSwitch(action: StylusButtonAction) {
        when (action) {
            StylusButtonAction.SWITCH_TO_ERASER -> switchToEraser()
            StylusButtonAction.PREVIOUS_PEN -> switchToPreviousPen()
        }
    }

    fun selectTool(next: InkTool) {
        if (tool != next) {
            val current = currentDrawingSelection()
            if (isDrawingSelection(current)) previousDrawingSelection = current
            eraserReturnSelection = if (next == InkTool.ERASER) {
                current.takeIf(::isDrawingSelection) ?: previousDrawingSelection
            } else {
                null
            }
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
        val current = currentDrawingSelection()
        if (isDrawingSelection(current)) previousDrawingSelection = current
        eraserReturnSelection = null
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
        val current = currentDrawingSelection()
        if (isDrawingSelection(current)) previousDrawingSelection = current
        eraserReturnSelection = null
        tool = InkTool.HIGHLIGHTER
        penPopover = null
        highlighterPopover = false
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun chooseColor(next: Int) {
        if (next != color) {
            recentColors = (listOf(color) + recentColors.filterNot { it == color || it == next })
                .distinct().take(8)
            updateActiveBrushSettings { it.copy(color = next) }
        }
        colorPop = false
    }

    fun setAlwaysStraight(enabled: Boolean) {
        alwaysStraight = enabled
        preferences.edit().putBoolean("highlighter.alwaysStraight", enabled).apply()
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val toolbarContentColor = if (darkTheme) Color(0xFFEDEDED) else Color(0xFF333333)
    val displayedRecentColors = (recentColors + listOf(0xFFFFFFFF.toInt(), 0xFF1B2A4E.toInt()))
        .distinct()
        .take(2)
    Box(Modifier.fillMaxSize().background(toolbarColor)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = statusBarHeight + 76.dp, bottom = 12.dp),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.onSample = { sample = it }
                    v.onStylusToolSwitch = ::handleStylusToolSwitch
                    v.onSelectionChanged = { hasSelection = it }
                    v.onZoomChanged = { zoomPercent = it }
                    v.onUndoRedoChanged = { undo, redo -> canUndo = undo; canRedo = redo }
                    v.onTextTap = { x, y, block -> if (block != null) editingBlock = block else newBlockAt = x to y }
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )

        Row(
            Modifier.fillMaxWidth().background(toolbarColor).statusBarsPadding().height(76.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = toolbarContentColor)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).clipToBounds().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(PenType.BALLPOINT, PenType.PENCIL, PenType.FOUNTAIN, PenType.MARKER).forEach { type ->
                    val settings = penSettings[type] ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, type.defaultOpacity())
                    Box {
                        ShelfCanvasItem(
                            label = type.label,
                            style = type.toShelfStyle(),
                            selected = tool == InkTool.PEN && pen == type,
                            color = settings.color,
                            opacity = settings.opacity,
                            onClick = { selectPen(type) },
                        )
                        DropdownMenu(
                            expanded = penPopover == type,
                            onDismissRequest = { penPopover = null },
                            shape = RoundedCornerShape(22.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            BrushSettingsPopover(
                                title = type.label,
                                color = settings.color,
                                brushSize = settings.size,
                                previewStrokeSize = settings.size * type.previewScale(),
                                onSizeChange = { value -> updateActiveBrushSettings { it.copy(size = value) } },
                                opacity = settings.opacity,
                                onOpacityChange = { value -> updateActiveBrushSettings { it.copy(opacity = value) } },
                                showOpacity = true,
                            )
                        }
                    }
                }
                Box {
                    ShelfCanvasItem(
                        label = "Highlighter",
                        style = ShelfDrawing.HIGHLIGHTER,
                        selected = tool == InkTool.HIGHLIGHTER,
                        color = highlighterSettings.color,
                        opacity = highlighterSettings.opacity,
                        onClick = { selectHighlighter() },
                    )
                    DropdownMenu(
                        expanded = highlighterPopover,
                        onDismissRequest = { highlighterPopover = false },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        BrushSettingsPopover(
                            title = "Highlighter",
                            color = highlighterSettings.color,
                            brushSize = highlighterSettings.size,
                            previewStrokeSize = highlighterSettings.size * 4f,
                            onSizeChange = { value -> updateActiveBrushSettings { it.copy(size = value) } },
                            opacity = highlighterSettings.opacity,
                            onOpacityChange = { value -> updateActiveBrushSettings { it.copy(opacity = value) } },
                            showOpacity = true,
                            alwaysStraight = alwaysStraight,
                            onAlwaysStraightChange = ::setAlwaysStraight,
                        )
                    }
                }
                val calligraphySettings = penSettings[PenType.CALLIGRAPHY] ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, PenType.CALLIGRAPHY.defaultOpacity())
                Box {
                    ShelfCanvasItem(
                        label = PenType.CALLIGRAPHY.label,
                        style = ShelfDrawing.CALLIGRAPHY,
                        selected = tool == InkTool.PEN && pen == PenType.CALLIGRAPHY,
                        color = calligraphySettings.color,
                        opacity = calligraphySettings.opacity,
                        onClick = { selectPen(PenType.CALLIGRAPHY) },
                    )
                    DropdownMenu(
                        expanded = penPopover == PenType.CALLIGRAPHY,
                        onDismissRequest = { penPopover = null },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        BrushSettingsPopover(
                        title = PenType.CALLIGRAPHY.label,
                        color = calligraphySettings.color,
                        brushSize = calligraphySettings.size,
                        previewStrokeSize = calligraphySettings.size * PenType.CALLIGRAPHY.previewScale(),
                        onSizeChange = { value -> updateActiveBrushSettings { it.copy(size = value) } },
                            opacity = calligraphySettings.opacity,
                            onOpacityChange = { value -> updateActiveBrushSettings { it.copy(opacity = value) } },
                        showOpacity = true,
                        )
                    }
                }
                ShelfCanvasItem("Eraser", ShelfDrawing.ERASER, tool == InkTool.ERASER, color, onClick = { selectTool(InkTool.ERASER) })
                ShelfCanvasItem("Lasso", ShelfDrawing.LASSO, tool == InkTool.LASSO, color, onClick = { selectTool(InkTool.LASSO) })
                ShelfCanvasItem("Text", ShelfDrawing.TEXT, tool == InkTool.TEXT, color, onClick = { selectTool(InkTool.TEXT) })
                ShelfCanvasItem("Ruler", ShelfDrawing.RULER, rulerVisible, color, onClick = {
                    rulerVisible = !rulerVisible
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                })
                    Box(Modifier.padding(start = 7.dp)) {
                        IconButton(onClick = { colorPop = true }, modifier = Modifier.size(44.dp)) {
                            ColorWheelButton(color)
                        }
                    DropdownMenu(
                        expanded = colorPop,
                        onDismissRequest = { colorPop = false },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(16.dp)) {
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
                displayedRecentColors.forEach { swatch ->
                    ColorDot(swatch, false, 44.dp) { chooseColor(swatch) }
                }
            }
            IconButton(enabled = canUndo, onClick = { surface?.undo() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", modifier = Modifier.size(28.dp), tint = toolbarContentColor.copy(alpha = if (canUndo) 1f else 0.38f))
            }
            IconButton(enabled = canRedo, onClick = { surface?.redo() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Redo", modifier = Modifier.size(28.dp), tint = toolbarContentColor.copy(alpha = if (canRedo) 1f else 0.38f))
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
                Icon(Icons.Outlined.IosShare, "Share notebook as PDF", modifier = Modifier.size(28.dp), tint = toolbarContentColor)
            }
            Box {
                IconButton(onClick = { morePop = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(28.dp), tint = toolbarContentColor)
                }
                DropdownMenu(
                    expanded = morePop,
                    onDismissRequest = { morePop = false },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
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
                    DropdownMenuItem(
                        text = { Text("Pen buttons") },
                        onClick = { morePop = false; showPenButtonSettings = true },
                    )
                    ToggleItem("Pixel eraser", "Erase pixels instead of whole strokes", pixelEraser) { pixelEraser = it }
                }
            }
        }

        if (pages.isNotEmpty()) Surface(
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 16.dp, bottom = if (showLab) 64.dp else 16.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            text = "tool=${sample.toolType}  pressure=%.3f  tilt=%.0f°  orient=%.0f°  input→handler=%d ms  batched=%d  strokes=%d\n$lastStylusEvent".format(
                sample.pressure, sample.tilt, sample.orientation, sample.handlerLatencyMs, sample.historySize, sample.strokeCount
            ),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.shapes.small).padding(8.dp),
        )
    }

    if (showPenButtonSettings) AlertDialog(
        onDismissRequest = { showPenButtonSettings = false },
        title = { Text("Pen buttons") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PenButtonActionChoices("Primary", primaryButtonAction) { primaryButtonAction = it }
                PenButtonActionChoices("Secondary", secondaryButtonAction) { secondaryButtonAction = it }
            }
        },
        confirmButton = { TextButton(onClick = { showPenButtonSettings = false }) { Text("Done") } },
    )

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
private fun PenButtonActionChoices(
    label: String,
    selected: StylusButtonAction,
    onSelected: (StylusButtonAction) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == StylusButtonAction.SWITCH_TO_ERASER, { onSelected(StylusButtonAction.SWITCH_TO_ERASER) })
            Text("Switch to eraser", modifier = Modifier.clickable { onSelected(StylusButtonAction.SWITCH_TO_ERASER) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == StylusButtonAction.PREVIOUS_PEN, { onSelected(StylusButtonAction.PREVIOUS_PEN) })
            Text("Previous pen", modifier = Modifier.clickable { onSelected(StylusButtonAction.PREVIOUS_PEN) })
        }
    }
}

private enum class ShelfDrawing { BALLPOINT, PENCIL, FOUNTAIN, MARKER, HIGHLIGHTER, CALLIGRAPHY, ERASER, LASSO, TEXT, RULER }

private fun PenType.toShelfStyle(): ShelfDrawing = when (this) {
    PenType.BALLPOINT -> ShelfDrawing.BALLPOINT
    PenType.PENCIL -> ShelfDrawing.PENCIL
    PenType.FOUNTAIN -> ShelfDrawing.FOUNTAIN
    PenType.MARKER -> ShelfDrawing.MARKER
    PenType.CALLIGRAPHY -> ShelfDrawing.CALLIGRAPHY
}

private fun PenType.previewScale() = when (this) {
    PenType.BALLPOINT -> 0.8f
    PenType.FOUNTAIN -> 1f
    PenType.PENCIL -> 1.2f
    PenType.MARKER -> 2.2f
    PenType.CALLIGRAPHY -> 2.5f
}

@Composable
private fun ShelfCanvasItem(
    label: String,
    style: ShelfDrawing,
    selected: Boolean,
    color: Int,
    opacity: Float = 1f,
    onClick: () -> Unit,
) {
    val rise by animateDpAsState(
        targetValue = if (selected) (-14).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "$label-rise",
    )
    val dark = MaterialTheme.colorScheme.isDark
    val bodyWidthDp = if (style == ShelfDrawing.ERASER || style == ShelfDrawing.RULER) 48.dp else 24.dp
    val itemWidth = bodyWidthDp + 14.dp

    Box(
        Modifier.width(itemWidth).fillMaxHeight().clipToBounds().clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.requiredSize(itemWidth, 128.dp).offset(y = 23.dp + rise)) {
            val centerX = size.width / 2f
            val bodyTop = 26.dp.toPx()
            val bodyBottom = size.height
            val bodyWidth = bodyWidthDp.toPx()
            val bodyLeft = centerX - bodyWidth / 2f
            val bodyRight = bodyLeft + bodyWidth
            val ferruleTop = bodyTop - 8.dp.toPx()
            val ferruleBottom = bodyTop + 2.dp.toPx()
            val bodyCorner = CornerRadius(minOf(bodyWidth / 2f, 8.dp.toPx()))
            val inkColor = Color(color).copy(alpha = opacity.coerceIn(0f, 1f))
            val bodyEdge = if (dark) Color(0xFF1A1A1C) else Color(0xFFDADDE2)
            val bodyCenter = if (dark) Color(0xFF3A3A3E) else Color.White
            val bodyBrush = Brush.horizontalGradient(
                colors = listOf(bodyEdge, bodyCenter, bodyEdge),
                startX = bodyLeft,
                endX = bodyRight,
            )
            val bodyTopLeft = Offset(bodyLeft, bodyTop)
            val bodySize = Size(bodyWidth, bodyBottom - bodyTop)

            if (style == ShelfDrawing.RULER) {
                val rulerTop = bodyTop
                val rulerHeight = bodyBottom - rulerTop
                drawRoundRect(
                    brush = bodyBrush,
                    topLeft = bodyTopLeft,
                    size = Size(bodyWidth, rulerHeight),
                    cornerRadius = bodyCorner,
                    alpha = if (dark) 0.94f else 0.74f,
                )
                drawRoundRect(
                    color = if (dark) Color(0xFF55565B) else Color(0xFFBEC2C8),
                    topLeft = bodyTopLeft,
                    size = Size(bodyWidth, rulerHeight),
                    cornerRadius = bodyCorner,
                    style = DrawStroke(width = 0.8.dp.toPx()),
                )
                for (i in 1..7) {
                    val y = rulerTop + i * 9.dp.toPx()
                    val markColor = if (dark) Color(0xFFB7B9BE) else Color(0xFF62666D)
                    drawLine(
                        markColor.copy(alpha = 0.86f),
                        Offset(bodyLeft + 3.dp.toPx(), y),
                        Offset(bodyLeft + if (i % 2 == 0) bodyWidth - 3.dp.toPx() else bodyWidth * 0.58f, y),
                        1.dp.toPx(),
                    )
                }
                return@Canvas
            }

            if (style == ShelfDrawing.ERASER) {
                val markColor = if (dark) Color(0xFFC7C9CE) else Color(0xFFAAA8A7)
                val eraserHeight = 92.dp.toPx()
                drawRoundRect(
                    brush = bodyBrush,
                    topLeft = bodyTopLeft,
                    size = Size(bodyWidth, eraserHeight),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                drawRoundRect(
                    color = markColor.copy(alpha = if (dark) 0.45f else 0.64f),
                    topLeft = Offset(bodyLeft + 2.dp.toPx(), bodyTop + 19.dp.toPx()),
                    size = Size(bodyWidth - 4.dp.toPx(), 7.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = if (dark) Color(0xFF55565B) else Color(0xFFBEC2C8),
                    topLeft = bodyTopLeft,
                    size = Size(bodyWidth, eraserHeight),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = DrawStroke(width = 0.8.dp.toPx()),
                )
                for (row in 0..1) for (column in 0..2) {
                    drawRect(
                        color = if ((row + column) % 2 == 0) markColor else markColor.copy(alpha = 0.52f),
                        topLeft = Offset(bodyLeft + 11.dp.toPx() + column * 6.dp.toPx(), bodyTop + 37.dp.toPx() + row * 6.dp.toPx()),
                        size = Size(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
                val wave = Path().apply {
                    moveTo(bodyLeft + 10.dp.toPx(), bodyTop + 64.dp.toPx())
                    cubicTo(bodyLeft + 16.dp.toPx(), bodyTop + 58.dp.toPx(), bodyLeft + 21.dp.toPx(), bodyTop + 70.dp.toPx(), bodyLeft + 27.dp.toPx(), bodyTop + 64.dp.toPx())
                    cubicTo(bodyLeft + 33.dp.toPx(), bodyTop + 58.dp.toPx(), bodyLeft + 38.dp.toPx(), bodyTop + 70.dp.toPx(), bodyLeft + 42.dp.toPx(), bodyTop + 64.dp.toPx())
                }
                drawPath(wave, markColor, style = DrawStroke(width = 1.5.dp.toPx()))
                return@Canvas
            }

            if (style == ShelfDrawing.PENCIL) {
                val hexBody = Path().apply {
                    moveTo(centerX, bodyTop)
                    lineTo(bodyRight, bodyTop + 4.dp.toPx())
                    lineTo(bodyRight, bodyBottom)
                    lineTo(centerX, bodyBottom + 2.dp.toPx())
                    lineTo(bodyLeft, bodyBottom)
                    lineTo(bodyLeft, bodyTop + 4.dp.toPx())
                    close()
                }
                drawPath(hexBody, bodyBrush)
                drawPath(hexBody, bodyEdge.copy(alpha = 0.8f), style = DrawStroke(width = 0.8.dp.toPx()))
            } else {
                drawRoundRect(bodyBrush, bodyTopLeft, bodySize, bodyCorner)
                drawRoundRect(
                    color = bodyEdge.copy(alpha = 0.9f),
                    topLeft = bodyTopLeft,
                    size = bodySize,
                    cornerRadius = bodyCorner,
                    style = DrawStroke(width = 0.8.dp.toPx()),
                )
            }
            val ferruleWidthTop = bodyWidth * 0.56f
            val ferrule = Path().apply {
                moveTo(centerX - ferruleWidthTop / 2f, ferruleTop)
                lineTo(centerX + ferruleWidthTop / 2f, ferruleTop)
                lineTo(bodyRight, ferruleBottom)
                lineTo(bodyLeft, ferruleBottom)
                close()
            }
            val silverBrush = Brush.horizontalGradient(
                listOf(Color(0xFF9298A0), Color(0xFFF1F2F4), Color(0xFFA3A9B1)),
                startX = bodyLeft,
                endX = bodyRight,
            )
            if (style != ShelfDrawing.PENCIL) {
                drawPath(ferrule, silverBrush)
                drawLine(
                    Color(0xFF777D85),
                    Offset(bodyLeft + 1.dp.toPx(), ferruleBottom),
                    Offset(bodyRight - 1.dp.toPx(), ferruleBottom),
                    0.8.dp.toPx(),
                )
            }

            when (style) {
                ShelfDrawing.BALLPOINT -> {
                    val tip = Path().apply {
                        moveTo(centerX, 4.dp.toPx())
                        lineTo(centerX - bodyWidth * 0.28f, ferruleTop)
                        lineTo(centerX + bodyWidth * 0.28f, ferruleTop)
                        close()
                    }
                    drawPath(tip, bodyBrush)
                    drawPath(tip, bodyEdge.copy(alpha = 0.9f), style = DrawStroke(width = 0.7.dp.toPx()))
                    drawCircle(Color(color), 1.2.dp.toPx(), Offset(centerX, 4.dp.toPx()))
                    val clipColor = if (dark) Color(0xFF171719) else Color(0xFF6E7278)
                    drawLine(
                        clipColor,
                        Offset(bodyRight - 2.dp.toPx(), bodyTop + 7.dp.toPx()),
                        Offset(bodyRight - 2.dp.toPx(), bodyTop + 28.dp.toPx()),
                        1.dp.toPx(),
                    )
                }
                ShelfDrawing.PENCIL -> {
                    val wood = Path().apply {
                        moveTo(centerX, 4.dp.toPx())
                        lineTo(bodyLeft, bodyTop)
                        lineTo(bodyRight, bodyTop)
                        close()
                    }
                    drawPath(wood, Color(0xFFE8C9A0))
                    drawLine(Color(0xFFB48E62), Offset(centerX, 6.dp.toPx()), Offset(centerX, bodyTop - 1.dp.toPx()), 1.dp.toPx())
                    drawCircle(Color(color), 1.3.dp.toPx(), Offset(centerX, 4.dp.toPx()))
                }
                ShelfDrawing.FOUNTAIN -> {
                    val nib = Path().apply {
                        moveTo(centerX, 4.dp.toPx())
                        lineTo(centerX - bodyWidth * 0.42f, ferruleTop - 2.dp.toPx())
                        lineTo(centerX - bodyWidth * 0.25f, ferruleTop + 1.dp.toPx())
                        lineTo(centerX + bodyWidth * 0.25f, ferruleTop + 1.dp.toPx())
                        lineTo(centerX + bodyWidth * 0.42f, ferruleTop - 2.dp.toPx())
                        close()
                    }
                    val nibBrush = Brush.horizontalGradient(
                        listOf(Color(0xFF8E949C), Color(0xFFF5F6F7), Color(0xFF9DA3AA)),
                        startX = bodyLeft,
                        endX = bodyRight,
                    )
                    drawPath(nib, nibBrush)
                    drawPath(nib, Color(0xFF7C8289), style = DrawStroke(width = 0.7.dp.toPx()))
                    drawLine(Color(0xFF555B62), Offset(centerX, 6.dp.toPx()), Offset(centerX, ferruleTop - 1.dp.toPx()), 0.9.dp.toPx())
                    drawCircle(Color(0xFFC7A25B), 1.6.dp.toPx(), Offset(centerX, ferruleTop - 4.dp.toPx()))
                }
                ShelfDrawing.MARKER -> {
                    drawRoundRect(
                        color = inkColor,
                        topLeft = Offset(bodyLeft, 4.dp.toPx()),
                        size = Size(bodyWidth, ferruleTop - 4.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    drawLine(
                        if (dark) Color.White.copy(alpha = 0.36f) else Color.White.copy(alpha = 0.64f),
                        Offset(bodyLeft + 2.dp.toPx(), 7.dp.toPx()),
                        Offset(bodyRight - 2.dp.toPx(), 7.dp.toPx()),
                        1.dp.toPx(),
                    )
                }
                ShelfDrawing.HIGHLIGHTER -> {
                    val chisel = Path().apply {
                        moveTo(bodyLeft, 5.dp.toPx())
                        lineTo(bodyRight, 10.dp.toPx())
                        lineTo(bodyRight, ferruleTop)
                        lineTo(bodyLeft, ferruleTop)
                        close()
                    }
                    drawPath(chisel, inkColor)
                    drawLine(
                        Color.White.copy(alpha = if (dark) 0.2f else 0.4f),
                        Offset(bodyLeft + 2.dp.toPx(), 7.dp.toPx()),
                        Offset(bodyRight - 3.dp.toPx(), 11.dp.toPx()),
                        0.8.dp.toPx(),
                    )
                }
                ShelfDrawing.CALLIGRAPHY -> {
                    val flame = Path().apply {
                        moveTo(centerX - 5.dp.toPx(), ferruleTop)
                        cubicTo(centerX - 15.dp.toPx(), ferruleTop - 7.dp.toPx(), centerX - 5.dp.toPx(), 3.dp.toPx(), centerX, 4.dp.toPx())
                        cubicTo(centerX + 11.dp.toPx(), 6.dp.toPx(), centerX + 9.dp.toPx(), ferruleTop - 7.dp.toPx(), centerX + 5.dp.toPx(), ferruleTop)
                        close()
                    }
                    drawPath(flame, inkColor)
                    drawLine(
                        Color.White.copy(alpha = if (dark) 0.4f else 0.58f),
                        Offset(centerX - 1.dp.toPx(), 8.dp.toPx()),
                        Offset(centerX + 1.dp.toPx(), ferruleTop - 2.dp.toPx()),
                        0.8.dp.toPx(),
                    )
                }
                ShelfDrawing.LASSO -> {
                    val loop = Path().apply {
                        moveTo(centerX - 8.dp.toPx(), bodyTop - 2.dp.toPx())
                        cubicTo(centerX - 15.dp.toPx(), 8.dp.toPx(), centerX + 12.dp.toPx(), 4.dp.toPx(), centerX + 8.dp.toPx(), bodyTop - 3.dp.toPx())
                        cubicTo(centerX + 2.dp.toPx(), bodyTop + 1.dp.toPx(), centerX - 10.dp.toPx(), bodyTop + 1.dp.toPx(), centerX - 8.dp.toPx(), bodyTop - 2.dp.toPx())
                    }
                    drawPath(
                        loop,
                        if (dark) Color(0xFFDFDFE2) else Color(0xFF56585C),
                        style = DrawStroke(width = 2.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                ShelfDrawing.TEXT -> {
                    val glyph = if (dark) Color(0xFFDFDFE2) else Color(0xFF56585C)
                    drawLine(glyph, Offset(centerX - 7.dp.toPx(), 7.dp.toPx()), Offset(centerX + 7.dp.toPx(), 7.dp.toPx()), 2.dp.toPx())
                    drawLine(glyph, Offset(centerX, 7.dp.toPx()), Offset(centerX, bodyTop - 2.dp.toPx()), 2.dp.toPx())
                }
                ShelfDrawing.ERASER, ShelfDrawing.RULER -> Unit
            }
        }
    }
}

@Composable
private fun BrushSettingsPopover(
    title: String,
    color: Int,
    brushSize: Float,
    previewStrokeSize: Float = brushSize,
    onSizeChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showOpacity: Boolean,
    alwaysStraight: Boolean? = null,
    onAlwaysStraightChange: ((Boolean) -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.width(264.dp).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            drawLine(
                Color(color).copy(alpha = if (showOpacity) opacity else 1f),
                Offset(12.dp.toPx(), size.height / 2f),
                Offset(size.width - 12.dp.toPx(), size.height / 2f),
                strokeWidth = previewStrokeSize.dp.toPx().coerceAtLeast(1.dp.toPx()),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Text("Stroke width", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = brushSize, onValueChange = onSizeChange, valueRange = 1f..10f)
        if (showOpacity) {
            Text("Opacity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = opacity, onValueChange = onOpacityChange, valueRange = 0.1f..1f)
        }
        onAlwaysStraightChange?.let { onChange ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Always straight", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = alwaysStraight == true, onCheckedChange = onChange)
            }
        }
        }
    }
}

@Composable
private fun ColorWheelButton(color: Int) {
    Canvas(Modifier.size(44.dp)) {
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
    val hairline = !selected && android.graphics.Color.luminance(argb) > 0.78f
    Box(
        Modifier.size(dotSize).clip(CircleShape).clickable(onClick = onClick)
            .border(
                if (selected) (2 * ring).dp else if (hairline) 0.75.dp else 0.dp,
                if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = ring) else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .padding(dotSize * 0.14f).background(Color(argb), CircleShape)
    )
}
