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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
                HistoryArrow(redo = false, tint = toolbarContentColor.copy(alpha = if (canUndo) 1f else 0.38f))
            }
            IconButton(enabled = canRedo, onClick = { surface?.redo() }, modifier = Modifier.size(44.dp)) {
                HistoryArrow(redo = true, tint = toolbarContentColor.copy(alpha = if (canRedo) 1f else 0.38f))
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
    val lift by animateDpAsState(
        targetValue = if (selected) 0.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "$label-lift",
    )
    val dark = MaterialTheme.colorScheme.isDark
    val wide = style == ShelfDrawing.ERASER || style == ShelfDrawing.RULER
    Box(
        Modifier.width(if (wide) 50.dp else 40.dp).fillMaxHeight().clipToBounds()
            .clickable(interactionSource = null, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = Color(color)
            ShelfPainter(this, dark, ink, ink.copy(alpha = opacity.coerceIn(0.2f, 1f)))
                .draw(style, top = 7.dp.toPx() + lift.toPx())
        }
    }
}

private class ShelfPainter(val scope: DrawScope, val dark: Boolean, val ink: Color, val translucentInk: Color) {
    private val u = with(scope) { 1.dp.toPx() }
    private val cx = scope.size.width / 2f
    private val bottom = scope.size.height + 2f * u

    private val edge = if (dark) Color(0xFF141416) else Color(0xFFD6D9DE)
    private val mid = if (dark) Color(0xFF45464B) else Color.White
    private val shade = if (dark) Color(0xFF2C2D30) else Color(0xFFF2F3F5)
    private val outline = if (dark) Color.Black.copy(alpha = 0.6f) else Color(0xFFBCC1C7).copy(alpha = 0.75f)
    private val shadow = Color.Black.copy(alpha = if (dark) 0.35f else 0.07f)
    private val gloss = Color.White.copy(alpha = if (dark) 0.07f else 0.85f)
    private val metalLo = if (dark) Color(0xFF6E7177) else Color(0xFF9DA2A9)
    private val metalHi = if (dark) Color(0xFFDADCE0) else Color(0xFFF8F9FA)
    private val mark = if (dark) Color(0xFFB4B6BB) else Color(0xFF6C7077)
    private val gold = Brush.horizontalGradient(listOf(Color(0xFFA9822F), Color(0xFFF2D88C), Color(0xFFA27A2A)), cx - 8f * u, cx + 8f * u)

    private fun bodyBrush(halfWidth: Float) = Brush.horizontalGradient(
        0f to edge, 0.3f to mid, 0.62f to shade, 1f to edge,
        startX = cx - halfWidth, endX = cx + halfWidth,
    )

    private fun metalBrush(halfWidth: Float) = Brush.horizontalGradient(
        0f to metalLo, 0.38f to metalHi, 1f to metalLo,
        startX = cx - halfWidth, endX = cx + halfWidth,
    )

    private fun part(path: Path, brush: Brush, edgeColor: Color = outline, withShadow: Boolean = true) = with(scope) {
        if (withShadow) translate(left = 1.2f * u, top = 0.6f * u) { drawPath(path, shadow) }
        drawPath(path, brush)
        drawPath(path, edgeColor, style = DrawStroke(width = 0.8f * u))
    }

    private fun part(path: Path, color: Color, edgeColor: Color = outline) = part(path, SolidColor(color), edgeColor, withShadow = false)

    private fun barrel(halfWidth: Float, top: Float) = Path().apply { addRect(Rect(cx - halfWidth, top, cx + halfWidth, bottom)) }

    private fun taper(tipHalf: Float, tipY: Float, halfWidth: Float, shoulderY: Float, belly: Float = 0.55f) = Path().apply {
        val curveY = tipY + (shoulderY - tipY) * 0.45f
        moveTo(cx - tipHalf, tipY)
        quadraticTo(cx - halfWidth * belly, curveY, cx - halfWidth, shoulderY)
        lineTo(cx - halfWidth, bottom)
        lineTo(cx + halfWidth, bottom)
        lineTo(cx + halfWidth, shoulderY)
        quadraticTo(cx + halfWidth * belly, curveY, cx + tipHalf, tipY)
        close()
    }

    private fun glossLine(halfWidth: Float, fromY: Float) = scope.drawLine(
        gloss, Offset(cx - halfWidth * 0.42f, fromY), Offset(cx - halfWidth * 0.42f, bottom), 1.6f * u,
    )

    private fun metalTip(top: Float, height: Float, halfWidth: Float) {
        val tip = Path().apply {
            moveTo(cx, top)
            lineTo(cx + halfWidth, top + height)
            lineTo(cx - halfWidth, top + height)
            close()
        }
        part(tip, metalBrush(halfWidth), metalLo, withShadow = false)
    }

    fun draw(style: ShelfDrawing, top: Float) = when (style) {
        ShelfDrawing.BALLPOINT -> ballpoint(top)
        ShelfDrawing.PENCIL -> pencil(top)
        ShelfDrawing.FOUNTAIN -> fountain(top)
        ShelfDrawing.MARKER -> marker(top)
        ShelfDrawing.HIGHLIGHTER -> highlighter(top)
        ShelfDrawing.CALLIGRAPHY -> brushPen(top)
        ShelfDrawing.ERASER -> eraser(top)
        ShelfDrawing.LASSO -> lasso(top)
        ShelfDrawing.TEXT -> textTool(top)
        ShelfDrawing.RULER -> ruler(top)
    }

    private fun ballpoint(t: Float) {
        val hw = 8.5f * u
        part(taper(1.4f * u, t + 5f * u, hw, t + 36f * u), bodyBrush(hw))
        glossLine(hw, t + 38f * u)
        metalTip(t, 5.5f * u, 1.5f * u)
        scope.drawCircle(ink, 1.1f * u, Offset(cx, t + 0.9f * u))
        scope.drawLine(translucentInk, Offset(cx, t + 46f * u), Offset(cx, t + 60f * u), 2.6f * u, StrokeCap.Round)
    }

    private fun pencil(t: Float) {
        val hw = 8.5f * u
        val woodTop = t + 9f * u
        val bodyTop = t + 28f * u
        part(barrel(hw, bodyTop), bodyBrush(hw))
        val facet = edge.copy(alpha = 0.55f)
        scope.drawLine(facet, Offset(cx - hw / 3f, bodyTop + 2f * u), Offset(cx - hw / 3f, bottom), 0.7f * u)
        scope.drawLine(facet, Offset(cx + hw / 3f, bodyTop + 2f * u), Offset(cx + hw / 3f, bottom), 0.7f * u)
        val wood = Path().apply {
            moveTo(cx - 2.3f * u, woodTop)
            lineTo(cx - hw, bodyTop)
            quadraticTo(cx - hw * 0.66f, bodyTop + 3.5f * u, cx - hw / 3f, bodyTop)
            quadraticTo(cx, bodyTop + 3.5f * u, cx + hw / 3f, bodyTop)
            quadraticTo(cx + hw * 0.66f, bodyTop + 3.5f * u, cx + hw, bodyTop)
            lineTo(cx + 2.3f * u, woodTop)
            close()
        }
        part(wood, Brush.horizontalGradient(listOf(Color(0xFFD4A877), Color(0xFFF3DDBA), Color(0xFFCB9C69)), cx - hw, cx + hw), Color(0xFFB98D5C).copy(alpha = 0.6f))
        val lead = Path().apply {
            moveTo(cx, t)
            lineTo(cx + 2.3f * u, woodTop + 0.5f * u)
            lineTo(cx - 2.3f * u, woodTop + 0.5f * u)
            close()
        }
        part(lead, ink, ink)
        scope.drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - 0.4f * u, t + 2.5f * u), Offset(cx - 1.2f * u, woodTop), 0.7f * u)
    }

    private fun fountain(t: Float) {
        val hw = 8f * u
        val sectionTop = t + 26f * u
        val bodyTop = t + 40f * u
        part(barrel(hw, bodyTop), bodyBrush(hw))
        glossLine(hw, bodyTop + 2f * u)
        val section = Path().apply {
            moveTo(cx - hw * 0.72f, sectionTop)
            lineTo(cx + hw * 0.72f, sectionTop)
            lineTo(cx + hw * 0.9f, bodyTop)
            lineTo(cx - hw * 0.9f, bodyTop)
            close()
        }
        part(section, bodyBrush(hw * 0.9f))
        scope.drawRect(gold, Offset(cx - hw, bodyTop - 1f * u), Size(2f * hw, 2.4f * u))
        val nib = Path().apply {
            moveTo(cx, t)
            cubicTo(cx + 1.6f * u, t + 6f * u, cx + hw * 0.86f, t + 15f * u, cx + hw * 0.66f, sectionTop + 1f * u)
            lineTo(cx - hw * 0.66f, sectionTop + 1f * u)
            cubicTo(cx - hw * 0.86f, t + 15f * u, cx - 1.6f * u, t + 6f * u, cx, t)
            close()
        }
        part(nib, metalBrush(hw * 0.8f), metalLo, withShadow = false)
        val nibInlay = Path().apply {
            moveTo(cx, t + 9f * u)
            lineTo(cx + 2.6f * u, t + 21f * u)
            lineTo(cx - 2.6f * u, t + 21f * u)
            close()
        }
        scope.drawPath(nibInlay, gold, alpha = 0.85f)
        val slit = if (dark) Color(0xFF2A2C30) else Color(0xFF50555C)
        scope.drawLine(slit, Offset(cx, t + 2.5f * u), Offset(cx, t + 16f * u), 0.8f * u)
        scope.drawCircle(slit, 1.2f * u, Offset(cx, t + 16.5f * u))
        scope.drawCircle(ink, 1f * u, Offset(cx, t + 0.9f * u))
    }

    private fun marker(t: Float) {
        val hw = 10.5f * u
        val collarTop = t + 12f * u
        val bodyTop = t + 21f * u
        part(taper(hw * 0.78f, bodyTop - 1f * u, hw, bodyTop + 5f * u, belly = 0.95f), bodyBrush(hw))
        glossLine(hw, bodyTop + 6f * u)
        scope.drawRect(translucentInk, Offset(cx - hw, t + 44f * u), Size(2f * hw, 3f * u))
        val collar = Path().apply {
            moveTo(cx - 5f * u, collarTop)
            lineTo(cx + 5f * u, collarTop)
            lineTo(cx + hw * 0.78f, bodyTop)
            lineTo(cx - hw * 0.78f, bodyTop)
            close()
        }
        part(collar, metalBrush(hw * 0.78f), metalLo)
        val tip = Path().apply {
            moveTo(cx - 3.8f * u, collarTop + 0.5f * u)
            lineTo(cx - 3.8f * u, t + 4.5f * u)
            quadraticTo(cx - 3.8f * u, t, cx, t)
            quadraticTo(cx + 3.8f * u, t, cx + 3.8f * u, t + 4.5f * u)
            lineTo(cx + 3.8f * u, collarTop + 0.5f * u)
            close()
        }
        part(tip, translucentInk, translucentInk)
        scope.drawLine(Color.White.copy(alpha = 0.35f), Offset(cx - 1.6f * u, t + 3f * u), Offset(cx - 1.6f * u, collarTop - 1f * u), 1f * u, StrokeCap.Round)
    }

    private fun highlighter(t: Float) {
        val hw = 11.5f * u
        val collarTop = t + 16f * u
        val bodyTop = t + 24f * u
        part(barrel(hw, bodyTop), bodyBrush(hw))
        glossLine(hw, bodyTop + 2f * u)
        scope.drawRect(SolidColor(mark), Offset(cx - hw, t + 44f * u), Size(2f * hw, 2.4f * u), alpha = 0.35f)
        val collar = Path().apply {
            moveTo(cx - 6.5f * u, collarTop)
            lineTo(cx + 6.5f * u, collarTop)
            lineTo(cx + hw, bodyTop)
            lineTo(cx - hw, bodyTop)
            close()
        }
        part(collar, bodyBrush(hw))
        val chisel = Path().apply {
            moveTo(cx - 5.5f * u, collarTop + 0.5f * u)
            lineTo(cx - 5.5f * u, t + 6f * u)
            lineTo(cx + 5.5f * u, t)
            lineTo(cx + 5.5f * u, collarTop + 0.5f * u)
            close()
        }
        part(chisel, translucentInk, translucentInk)
        scope.drawLine(Color.White.copy(alpha = 0.4f), Offset(cx - 4f * u, t + 6.5f * u), Offset(cx + 4f * u, t + 2f * u), 1f * u, StrokeCap.Round)
    }

    private fun brushPen(t: Float) {
        val hw = 7.5f * u
        val ferruleTop = t + 26f * u
        val bodyTop = t + 37f * u
        part(barrel(hw, bodyTop), bodyBrush(hw))
        glossLine(hw, bodyTop + 2f * u)
        val ferrule = Path().apply {
            moveTo(cx - 5f * u, ferruleTop)
            lineTo(cx + 5f * u, ferruleTop)
            lineTo(cx + hw, bodyTop)
            lineTo(cx - hw, bodyTop)
            close()
        }
        part(ferrule, metalBrush(hw), metalLo)
        scope.drawLine(metalLo, Offset(cx - 5.6f * u, ferruleTop + 3f * u), Offset(cx + 5.6f * u, ferruleTop + 3f * u), 0.7f * u)
        val bristles = Path().apply {
            moveTo(cx, t)
            cubicTo(cx + 2f * u, t + 8f * u, cx + 6.5f * u, t + 15f * u, cx + 5.2f * u, ferruleTop + 0.5f * u)
            lineTo(cx - 5.2f * u, ferruleTop + 0.5f * u)
            cubicTo(cx - 6.5f * u, t + 15f * u, cx - 2f * u, t + 8f * u, cx, t)
            close()
        }
        part(bristles, translucentInk, translucentInk)
        scope.drawPath(bristles, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.18f)), cx - 6f * u, cx + 6f * u))
    }

    private fun lasso(t: Float) {
        val hw = 8f * u
        part(taper(1.4f * u, t + 5f * u, hw, t + 32f * u, belly = 0.45f), bodyBrush(hw))
        glossLine(hw, t + 34f * u)
        metalTip(t, 5.5f * u, 1.5f * u)
        scope.drawOval(
            mark,
            topLeft = Offset(cx - 5f * u, t + 40f * u),
            size = Size(10f * u, 7f * u),
            style = DrawStroke(width = 1.3f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f * u, 1.8f * u))),
        )
        for (i in 0..4) {
            val y = t + 52f * u + i * 3.2f * u
            scope.drawLine(mark.copy(alpha = 0.45f), Offset(cx - hw + 1.5f * u, y + 2f * u), Offset(cx + hw - 1.5f * u, y - 2f * u), 0.8f * u)
        }
    }

    private fun textTool(t: Float) {
        val hw = 8.5f * u
        val bodyTop = t + 14f * u
        val body = Path().apply {
            addRoundRect(RoundRect(Rect(cx - hw, bodyTop, cx + hw, bottom), topLeft = CornerRadius(hw), topRight = CornerRadius(hw)))
        }
        part(body, bodyBrush(hw))
        glossLine(hw, bodyTop + 8f * u)
        val glyphTop = bodyTop + 12f * u
        scope.drawLine(mark, Offset(cx - 4.5f * u, glyphTop), Offset(cx + 4.5f * u, glyphTop), 1.8f * u, StrokeCap.Round)
        scope.drawLine(mark, Offset(cx, glyphTop), Offset(cx, glyphTop + 12f * u), 1.8f * u, StrokeCap.Round)
    }

    private fun eraser(t: Float) {
        val hw = 16f * u
        val bodyTop = t + 14f * u
        val body = Path().apply {
            addRoundRect(RoundRect(Rect(cx - hw, bodyTop, cx + hw, bottom), topLeft = CornerRadius(9f * u), topRight = CornerRadius(9f * u)))
        }
        part(body, bodyBrush(hw))
        for (row in 0..1) for (column in 0..2) {
            if ((row + column) % 2 == 0) {
                scope.drawRect(mark, Offset(cx - 4.5f * u + column * 3f * u, bodyTop + 18f * u + row * 3f * u), Size(3f * u, 3f * u), alpha = 0.8f)
            }
        }
        scope.drawLine(mark.copy(alpha = 0.35f), Offset(cx - 7f * u, bodyTop + 28f * u), Offset(cx + 7f * u, bodyTop + 28f * u), 0.8f * u)
        val wave = Path().apply {
            moveTo(cx - 6f * u, bodyTop + 36f * u)
            cubicTo(cx - 3f * u, bodyTop + 32f * u, cx, bodyTop + 40f * u, cx + 3f * u, bodyTop + 36f * u)
            quadraticTo(cx + 4.5f * u, bodyTop + 34f * u, cx + 6f * u, bodyTop + 35f * u)
        }
        scope.drawPath(wave, mark, style = DrawStroke(width = 1.2f * u, cap = StrokeCap.Round))
    }

    private fun ruler(t: Float) {
        val hw = 15f * u
        val bodyTop = t + 12f * u
        val body = Path().apply {
            addRoundRect(RoundRect(Rect(cx - hw, bodyTop, cx + hw, bottom), topLeft = CornerRadius(3f * u), topRight = CornerRadius(3f * u)))
        }
        with(scope) {
            translate(left = 1.2f * u, top = 0.6f * u) { drawPath(body, shadow) }
            drawPath(body, bodyBrush(hw), alpha = if (dark) 0.92f else 0.7f)
            drawPath(body, outline, style = DrawStroke(width = 0.8f * u))
            drawLine(gloss, Offset(cx + hw - 6f * u, bodyTop + 2f * u), Offset(cx + hw - 6f * u, bottom), 3f * u)
        }
        var i = 0
        var y = bodyTop + 6f * u
        while (y < bottom) {
            val len = when {
                i % 4 == 0 -> 9f * u
                i % 2 == 0 -> 6f * u
                else -> 4f * u
            }
            scope.drawLine(mark.copy(alpha = 0.75f), Offset(cx - hw + 1.5f * u, y), Offset(cx - hw + 1.5f * u + len, y), 0.8f * u)
            y += 4f * u
            i++
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
    val gap = if (MaterialTheme.colorScheme.isDark) Color(0xFF111111) else Color.White
    Canvas(Modifier.size(40.dp)) {
        val radius = size.minDimension / 2f
        val ringWidth = 4.5.dp.toPx()
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color(0xFFFF9E9E), Color(0xFFFFD27A), Color(0xFFF4F27C), Color(0xFF9BEBA0),
                    Color(0xFF8FE3F0), Color(0xFF9DB4FF), Color(0xFFD7A2F5), Color(0xFFFF9E9E),
                ),
            ),
            radius = radius - ringWidth / 2f,
            style = DrawStroke(width = ringWidth),
        )
        drawCircle(gap, radius = radius - ringWidth)
        drawCircle(Color(color), radius = radius - ringWidth - 2.5.dp.toPx())
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
    val hairline = android.graphics.Color.luminance(argb) > 0.78f
    val ringColor = MaterialTheme.colorScheme.onSurface
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(dotSize).clip(CircleShape).clickable(onClick = onClick)) {
        val radius = size.minDimension / 2f
        val dotRadius = radius * 0.72f
        if (ring > 0f) drawCircle(ringColor.copy(alpha = ring), radius - 1.dp.toPx(), style = DrawStroke(width = 2.dp.toPx()))
        drawCircle(Color(argb), dotRadius)
        if (hairline) drawCircle(hairlineColor, dotRadius, style = DrawStroke(width = 0.8.dp.toPx()))
    }
}

@Composable
private fun HistoryArrow(redo: Boolean, tint: Color) {
    Canvas(Modifier.size(28.dp).semantics { contentDescription = if (redo) "Redo" else "Undo" }) {
        val g = size.minDimension / 24f
        val arrow = Path().apply {
            moveTo(5f * g, 9f * g)
            lineTo(14.5f * g, 9f * g)
            cubicTo(17.8f * g, 9f * g, 19.5f * g, 11f * g, 19.5f * g, 14f * g)
            lineTo(19.5f * g, 19.5f * g)
            moveTo(9f * g, 5f * g)
            lineTo(5f * g, 9f * g)
            lineTo(9f * g, 13f * g)
        }
        scale(scaleX = if (redo) -1f else 1f, scaleY = 1f) {
            drawPath(arrow, tint, style = DrawStroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
