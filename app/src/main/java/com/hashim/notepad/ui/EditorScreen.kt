package com.hashim.notepad.ui

import com.hashim.notepad.pdf.PdfSourceCache

import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Image
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
import androidx.ink.brush.StockBrushes
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.audio.AudioPlaybackBar
import com.hashim.notepad.audio.AudioRecorderButton
import com.hashim.notepad.data.PageImage
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.ink.BrushSpec
import com.hashim.notepad.ink.InkSurfaceView
import com.hashim.notepad.ink.SelectionClipboard
import com.hashim.notepad.ink.InkTool
import com.hashim.notepad.ink.PenType
import com.hashim.notepad.ink.PointerSample
import com.hashim.notepad.ink.StylusButtonAction
import com.hashim.notepad.ink.StylusInputDiagnostics
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.StylusDetector
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfUserPoint
import com.hashim.notepad.pdf.PdfTextCache
import com.hashim.notepad.pdf.PdfWordSnap
import com.hashim.notepad.pdf.PdfiumDispatcher
import com.hashim.notepad.pdf.PageImageImporter
import com.hashim.notepad.recognition.InkRecognizer
import com.hashim.notepad.recognition.InkPoint
import com.hashim.notepad.recognition.InkRecognitionCandidate
import com.hashim.notepad.recognition.RecognitionCandidatePolicy
import com.hashim.notepad.recognition.TextBlockGeometry
import com.hashim.notepad.recognition.TextBlockPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.util.Locale
import java.util.UUID

private val PALETTE = listOf(
    0xFF000000, 0xFF4A4A4A, 0xFF8E8E93, 0xFFC7C7CC, 0xFFFFFFFF, 0xFF1B2A4E,
    0xFFD32F2F, 0xFFF57C00, 0xFFFBC02D, 0xFF388E3C, 0xFF00897B, 0xFF1976D2,
    0xFFEF9A9A, 0xFFFFCC80, 0xFFFFF59D, 0xFFA5D6A7, 0xFF80CBC4, 0xFF90CAF9,
    0xFF7B1FA2, 0xFFC2185B, 0xFF5D4037, 0xFFCE93D8, 0xFFF48FB1, 0xFFBCAAA4,
).map { it.toInt() }

private data class ToolBrushSettings(val color: Int, val size: Float, val opacity: Float)
private data class FavoritePenPreset(val tool: InkTool, val pen: PenType, val color: Int, val size: Float, val opacity: Float)
private data class DrawingToolSelection(val tool: InkTool, val pen: PenType)
private data class TextConversionDraft(
    val token: Int,
    val page: Page,
    val geometry: TextBlockPlacement,
    val colorArgb: Int,
    val loading: Boolean,
    val text: String = "",
    val alternatives: List<String> = emptyList(),
)
private enum class EditorToolbarEdge { TOP, BOTTOM, START, END }
private val TAPE_COLORS = listOf(0xFFF2D37B.toInt(), 0xFFB7D7C5.toInt(), 0xFFA9CBE0.toInt(), 0xFFDDB4C0.toInt(), 0xFFB9BBC0.toInt(), 0xFFC7A884.toInt())

/** Width reserved on each side of a top-docked toolbar for the back/pages and action buttons. */
private val TOP_CHROME_CLEARANCE = 150.dp

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

private fun readFavoritePresets(preferences: android.content.SharedPreferences): List<FavoritePenPreset> =
    runCatching {
        val json = org.json.JSONArray(preferences.getString("favorite_pens", "[]") ?: "[]")
        buildList {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val tool = InkTool.valueOf(item.getString("tool"))
                if (tool != InkTool.PEN && tool != InkTool.HIGHLIGHTER) continue
                add(
                    FavoritePenPreset(
                        tool,
                        PenType.valueOf(item.optString("pen", PenType.FOUNTAIN.name)),
                        item.getInt("color"),
                        item.getDouble("size").toFloat(),
                        item.optDouble("opacity", 1.0).toFloat(),
                    ),
                )
            }
        }.take(8)
    }.getOrDefault(emptyList())

private fun writeFavoritePresets(preferences: android.content.SharedPreferences, presets: List<FavoritePenPreset>) {
    val json = org.json.JSONArray()
    presets.take(8).forEach { preset ->
        json.put(
            org.json.JSONObject()
                .put("tool", preset.tool.name)
                .put("pen", preset.pen.name)
                .put("color", preset.color)
                .put("size", preset.size.toDouble())
                .put("opacity", preset.opacity.toDouble()),
        )
    }
    preferences.edit().putString("favorite_pens", json.toString()).apply()
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EditorScreen(
    repo: NotePadRepository,
    notebookId: String,
    pageId: String,
    recognizer: InkRecognizer,
    onBack: () -> Unit,
    onNavigateToPage: (String) -> Unit,
    onOpenSideBySide: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val notebook = notebooks.firstOrNull { it.id == notebookId }
    val language = notebook?.language ?: "ar"
    val pageList by produceState<List<Page>?>(null, repo, notebookId) {
        repo.pages(notebookId).collect { value = it }
    }
    val pages = pageList.orEmpty()
    val page = pages.firstOrNull { it.id == pageId }
    val index = pages.indexOfFirst { it.id == pageId }
    LaunchedEffect(notebookId, pageId, index) {
        if (index >= 0) {
            delay(450)
            repo.setReadingPosition(notebookId, index)
        }
    }
    DisposableEffect(notebookId, pageId, index) {
        onDispose {
            if (index >= 0) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                repo.setReadingPosition(notebookId, index)
            }
        }
    }
    LaunchedEffect(pageList, pageId) {
        val currentPages = pageList ?: return@LaunchedEffect
        if (currentPages.none { it.id == pageId }) onBack()
    }

    var tool by rememberSaveableTool()
    val preferences = remember(context) { context.getSharedPreferences("notepad", 0) }
    var pageSidebarOpen by rememberSaveable { mutableStateOf(false) }
    var selectedAudioRecordingId by rememberSaveable(notebookId) { mutableStateOf<String?>(null) }
    var requestedAudioSeekMs by remember { mutableStateOf<Long?>(null) }
    var toolbarEdge by remember(preferences) {
        mutableStateOf(
            runCatching {
                EditorToolbarEdge.valueOf(
                    preferences.getString("editor_toolbar_edge", EditorToolbarEdge.TOP.name)
                        ?: EditorToolbarEdge.TOP.name,
                )
            }.getOrDefault(EditorToolbarEdge.TOP),
        )
    }
    var toolbarPinned by remember(preferences) {
        mutableStateOf(preferences.getBoolean("editor_toolbar_pinned", false))
    }
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
    var favoritePresets by remember(preferences) { mutableStateOf(readFavoritePresets(preferences)) }
    var tapeSettings by remember(preferences) {
        mutableStateOf(readBrushSettings(preferences, "tape", ToolBrushSettings(TAPE_COLORS.first(), 24f, 1f)))
    }
    var alwaysStraight by remember(preferences) { mutableStateOf(preferences.getBoolean("highlighter.alwaysStraight", false)) }
    var snapHighlighterToText by remember(preferences) {
        mutableStateOf(preferences.getBoolean("highlighter.snapToText", true))
    }
    LaunchedEffect(snapHighlighterToText) {
        preferences.edit().putBoolean("highlighter.snapToText", snapHighlighterToText).apply()
    }
    var shapeStraightening by remember(preferences) { mutableStateOf(preferences.getBoolean("shape.straightening", true)) }
    var recentColors by remember { mutableStateOf(emptyList<Int>()) }
    var fingerDraws by remember { mutableStateOf(!StylusDetector.hasStylus(context)) }
    var zoomBox by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(PointerSample()) }
    var hasSelection by remember { mutableStateOf(false) }
    var selectionBounds by remember { mutableStateOf<android.graphics.RectF?>(null) }
    var clipboardHasContent by remember { mutableStateOf(!SelectionClipboard.isEmpty) }
    var recolorSelectionMode by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var pixelEraser by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    DisposableEffect(surface, showLab) {
        val view = surface
        view?.onSample = if (showLab) ({ sample = it }) else null
        onDispose { view?.onSample = null }
    }
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
    var stylusConfirmation by remember { mutableStateOf<String?>(null) }
    var drawingActive by remember { mutableStateOf(false) }
    var toolbarAutoHidden by remember { mutableStateOf(false) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var toolbarPositionReady by remember { mutableStateOf(false) }
    var toolbarDragging by remember { mutableStateOf(false) }
    var toolbarDragPosition by remember { mutableStateOf(IntOffset.Zero) }
    var toolbarDragFingerPosition by remember { mutableStateOf(Offset.Zero) }
    var editorOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var toolbarGripOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val toolbarX = remember { Animatable(0f) }
    val toolbarY = remember { Animatable(0f) }
    val darkTheme = MaterialTheme.colorScheme.isDark
    val toolbarColor = when {
        darkTheme -> Color(0xFF111111)
        LocalThemeMode.current == ThemeMode.SEPIA -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color(0xFFEDEDED)
    }
    val deskColor = toolbarColor
    val activeBrushSettings = when (tool) {
        InkTool.HIGHLIGHTER -> highlighterSettings
        InkTool.TAPE -> tapeSettings
        else -> penSettings[pen] ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, pen.defaultOpacity())
    }
    val color = activeBrushSettings.color
    val size = activeBrushSettings.size
    val exporter = remember(context, repo) { PdfExporter(context, repo) }
    fun updateActiveBrushSettings(update: (ToolBrushSettings) -> ToolBrushSettings) {
        val updated = update(activeBrushSettings)
        if (tool == InkTool.HIGHLIGHTER) {
            highlighterSettings = updated
            writeBrushSettings(preferences, "highlighter", updated)
        } else if (tool == InkTool.TAPE) {
            tapeSettings = updated
            writeBrushSettings(preferences, "tape", updated)
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
    var processedHighlighterStrokeCount by remember(page?.id) { mutableIntStateOf(-1) }
    var dirtyPageImages by remember { mutableStateOf<Pair<String, List<PageImage>>?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val textBlocks by repo.textBlocks(pageId).collectAsState(initial = emptyList())
    val audioObservedItemKeys = remember(pageId) { mutableSetOf<String>() }
    val pdfMarks by repo.pdfMarks(notebookId).collectAsState(initial = emptyList())
    var editingBlock by remember { mutableStateOf<TextBlock?>(null) }
    var newBlockAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val scope = rememberCoroutineScope()
    var textConversion by remember { mutableStateOf<TextConversionDraft?>(null) }
    var textConversionJob by remember { mutableStateOf<Job?>(null) }
    var textConversionToken by remember { mutableIntStateOf(0) }
    fun dismissTextConversion() {
        textConversionToken++
        textConversionJob?.cancel()
        textConversionJob = null
        textConversion = null
    }
    fun beginTextConversion(target: InkSurfaceView, currentPage: Page, selected: List<Stroke>) {
        val selection = selected.toList()
        val points = selection.flatMap { stroke ->
            val inputs = stroke.inputs
            (0 until inputs.size).map { inputs[it] }
        }
        if (points.isEmpty()) {
            Toast.makeText(context, "No handwriting recognized", Toast.LENGTH_SHORT).show()
            return
        }
        val geometry = TextBlockGeometry.fromBounds(
            points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
        )
        textConversionJob?.cancel()
        val token = ++textConversionToken
        textConversion = TextConversionDraft(
            token = token,
            page = currentPage,
            geometry = geometry,
            colorArgb = target.selectedTextColorForPaper(target.paperColorArgb()),
            loading = true,
        )
        textConversionJob = scope.launch {
            val languages = if (language.equals("en", ignoreCase = true)) listOf("en", "ar") else listOf(language, "en")
            val groups = mutableListOf<List<InkRecognitionCandidate>>()
            val failures = mutableListOf<Exception>()
            for (recognitionLanguage in languages.distinct()) {
                try {
                    if (!recognizer.ensureModel(recognitionLanguage)) {
                        throw IllegalStateException("model unavailable")
                    }
                    groups.add(recognizer.recognizeCandidates(
                        selection, recognitionLanguage, currentPage.widthPt, currentPage.heightPt,
                    ).filter { it.text.isNotBlank() })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += error
                    Log.w("NotePad", "recognition $recognitionLanguage", error)
                }
            }
            if (token != textConversionToken) return@launch
            val candidates = groups.flatten()
            val preferLatin = RecognitionCandidatePolicy.prefersLatin(
                selection.map { stroke ->
                    val inputs = stroke.inputs
                    (0 until inputs.size).map { index -> InkPoint(inputs[index].x, inputs[index].y) }
                },
            )
            val best = RecognitionCandidatePolicy.bestCandidate(candidates, preferLatin)
            if (best == null) {
                textConversion = null
                val message = failures.lastOrNull()?.let {
                    "Handwriting recognition failed: ${it.message ?: "model unavailable"}"
                } ?: "No handwriting recognized"
                Toast.makeText(context, message, if (failures.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                textConversionJob = null
                return@launch
            }
            val alternatives = groups.asSequence()
                .flatMap { it.take(3).asSequence() }
                .map { it.text.trim() }
                .filter { it.isNotBlank() && it != best.text.trim() }
                .distinct()
                .take(6)
                .toList()
            textConversion = textConversion?.takeIf { it.token == token }?.copy(
                loading = false,
                text = best.text.trim(),
                alternatives = alternatives,
            )
            textConversionJob = null
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val imported = PageImageImporter(context).import(uri)
                val target = surface ?: return@runCatching
                val bounds = target.imagePlacement(imported.widthPx, imported.heightPx)
                val row = repo.addPageImage(pageId, imported.asset, bounds.left, bounds.top, bounds.width(), bounds.height())
                target.addPageImage(row)
            }.onFailure { Toast.makeText(context, "Could not insert image: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(textBlocks, surface) {
        surface?.textBlocks = textBlocks
        audioObservedItemKeys.addAll(textBlocks.map { "text:${it.id}" })
    }
    LaunchedEffect(pdfMarks, pageId, surface) { surface?.setPdfMarks(pdfMarks.filter { it.pageId == pageId }) }
    LaunchedEffect(page?.rotation, surface) { surface?.setPageRotation(page?.rotation ?: 0) }

    val spec = BrushSpec(tool, color, size, pen, activeBrushSettings.opacity)
    LaunchedEffect(surface, zoomBox, language) { surface?.setZoomBox(zoomBox, language != "en") }
    LaunchedEffect(surface, rulerVisible) { surface?.setRulerVisible(rulerVisible) }
    LaunchedEffect(surface, darkTheme) { surface?.setRulerDarkTheme(darkTheme) }
    DisposableEffect(surface) {
        surface?.onAudioSeekRequested = { requestedAudioSeekMs = it }
        onDispose { surface?.onAudioSeekRequested = null }
    }
    LaunchedEffect(surface, deskColor) { surface?.setDeskColor(deskColor.toArgb()) }
    LaunchedEffect(surface, alwaysStraight) { surface?.highlighterAlwaysStraight = alwaysStraight }
    LaunchedEffect(surface, shapeStraightening) { surface?.shapeStraightening = shapeStraightening }
    LaunchedEffect(surface, primaryButtonAction, secondaryButtonAction) {
        surface?.primaryButtonAction = primaryButtonAction
        surface?.secondaryButtonAction = secondaryButtonAction
    }
    LaunchedEffect(primaryButtonAction) { preferences.edit().putString("stylus.primaryAction", primaryButtonAction.name).apply() }
    LaunchedEffect(secondaryButtonAction) { preferences.edit().putString("stylus.secondaryAction", secondaryButtonAction.name).apply() }
    LaunchedEffect(toolbarEdge) { preferences.edit().putString("editor_toolbar_edge", toolbarEdge.name).apply() }
    LaunchedEffect(toolbarPinned) { preferences.edit().putBoolean("editor_toolbar_pinned", toolbarPinned).apply() }
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

    fun recordAudioItem(pageId: String, kind: String, key: String) {
        scope.launch(Dispatchers.IO) {
            val recording = repo.activeAudioRecording(notebookId) ?: return@launch
            repo.addInkTimestamp(
                recording.id,
                pageId,
                kind,
                key,
                (System.currentTimeMillis() - recording.startedAt).coerceAtLeast(0),
            )
        }
    }

    // Load page geometry + strokes once the surface exists.
    LaunchedEffect(page?.id, surface) {
        val p = page ?: return@LaunchedEffect
        val s = surface ?: return@LaunchedEffect
        if (loadedFor == p.id) return@LaunchedEffect
        s.setRevealPage(p.id)
        s.setPageGeometry(p.widthPt, p.heightPt, p.kind)
        s.setPageRotation(p.rotation)
        val bytes = repo.loadStrokes(p.id)
        val strokes = bytes?.let { b -> withContext(Dispatchers.Default) { runCatching { StrokeStore.decode(b) }.getOrElse { Log.e("NotePad", "decode", it); emptyList() } } } ?: emptyList()
        audioObservedItemKeys.addAll(strokes.indices.map { "stroke:$it" })
        s.onStrokesChanged = null
        s.setStrokes(strokes)
        var observedStrokeCount = strokes.size
        s.onStrokesChanged = { changed ->
            dirtyStrokes = changed
            if (changed.size > observedStrokeCount) {
                (observedStrokeCount until changed.size).forEach { index ->
                    if (audioObservedItemKeys.add("stroke:$index")) {
                        recordAudioItem(p.id, "stroke", "stroke:$index")
                    }
                }
            }
            observedStrokeCount = changed.size
        }
        s.onPageImagesChanged = { images ->
            dirtyPageImages = p.id to images
            images.forEach {
                if (audioObservedItemKeys.add("image:${it.id}")) {
                    recordAudioItem(p.id, "image", it.id)
                }
            }
        }
        s.onTextBlocksChanged = { blocks ->
            blocks.forEach {
                if (audioObservedItemKeys.add("text:${it.id}")) {
                    recordAudioItem(p.id, "text", it.id)
                }
            }
            scope.launch {
                repo.replacePageTextBlocks(p.id, blocks)
            }
        }
        val images = repo.pageImages(p.id).first()
        audioObservedItemKeys.addAll(images.map { "image:${it.id}" })
        s.setPageImages(p.id, images)
        loadedFor = p.id
        processedHighlighterStrokeCount = strokes.size
    }
    LaunchedEffect(dirtyStrokes, page?.id, loadedFor, snapHighlighterToText, surface) {
        val strokes = dirtyStrokes ?: return@LaunchedEffect
        val currentPage = page ?: return@LaunchedEffect
        val target = surface ?: return@LaunchedEffect
        if (loadedFor != currentPage.id) return@LaunchedEffect
        if (currentPage.kind != PageKind.PDF || !snapHighlighterToText) {
            processedHighlighterStrokeCount = strokes.size
            return@LaunchedEffect
        }
        if (processedHighlighterStrokeCount < 0) {
            processedHighlighterStrokeCount = strokes.size
            return@LaunchedEffect
        }
        if (strokes.size <= processedHighlighterStrokeCount) {
            processedHighlighterStrokeCount = strokes.size
            return@LaunchedEffect
        }
        val stroke = strokes.lastOrNull() ?: return@LaunchedEffect
        if (stroke.inputs.isEmpty()) return@LaunchedEffect
        processedHighlighterStrokeCount = strokes.size
        if (stroke.brush.family != StockBrushes.highlighter()) return@LaunchedEffect
        val assetId = currentPage.assetId ?: return@LaunchedEffect
        val asset = repo.asset(assetId) ?: return@LaunchedEffect
        val created = withContext(PdfiumDispatcher.dispatcher) {
            PdfSourceCache.withSource(context, File(asset.localPath), com.hashim.notepad.pdf.PdfPasswordVault(context).read(asset.id)) { source ->
                val pdfIndex = currentPage.pdfPageIndex ?: pages.indexOf(currentPage)
                val text = PdfTextCache.getOrLoad(context, asset.id, pdfIndex) { source.textGeometry(pdfIndex) }
                val toleranceX = maxOf(4f, stroke.brush.size * 2f)
                val toleranceY = maxOf(10f, stroke.brush.size * 2f)
                val points = (0 until stroke.inputs.size).map { index ->
                    stroke.inputs[index].let { PdfUserPoint(it.x, it.y) }
                }
                val range = PdfWordSnap.wordRangeForPoints(
                    text,
                    points,
                    Locale.forLanguageTag(language),
                    toleranceX,
                    toleranceY,
                ) ?: return@withSource null
                val rects = text.rectsForRange(range.start, range.end)
                if (rects.isEmpty()) return@withSource null
                PdfMark(
                    id = UUID.randomUUID().toString(),
                    notebookId = notebookId,
                    pageId = currentPage.id,
                    kind = PdfMarkKind.HIGHLIGHT,
                    colorArgb = stroke.brush.colorIntArgb,
                    quads = PdfWordSnap.quadPoints(rects, currentPage.heightPt),
                    charStart = range.start,
                    charEnd = range.end,
                    text = text.text.substring(range.start, range.end),
                    note = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        if (created != null) {
            repo.savePdfMark(created)
            recordAudioItem(created.pageId, "mark", created.id)
            val converted = target.convertLastStrokeToExternalUndo(
                stroke,
                undoAction = { scope.launch { repo.deletePdfMark(created.id) } },
                redoAction = { scope.launch { repo.savePdfMark(created) } },
            )
            if (!converted) scope.launch { repo.deletePdfMark(created.id) }
        }
    }

    // Render the PDF page bitmap for PDF pages.
    LaunchedEffect(page?.id, page?.assetId, surfaceWidth) {
        val p = page ?: return@LaunchedEffect
        if (p.kind != PageKind.PDF || p.assetId == null || surfaceWidth == 0) { pageBitmap = null; surface?.setPage(null); return@LaunchedEffect }
        val asset = repo.asset(p.assetId) ?: return@LaunchedEffect
        val bmp = withContext(PdfiumDispatcher.dispatcher) {
            runCatching { PdfSourceCache.withSource(context, File(asset.localPath), com.hashim.notepad.pdf.PdfPasswordVault(context).read(asset.id)) { it.render(p.pdfPageIndex ?: 0, (surfaceWidth * 1.5f).toInt()) } }
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
    LaunchedEffect(dirtyPageImages) {
        val (id, images) = dirtyPageImages ?: return@LaunchedEffect
        delay(250)
        repo.savePageImages(id, images)
    }
    DisposableEffect(Unit) {
        onDispose {
            val strokes = dirtyStrokes; val p = page
            if (strokes != null && p != null) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.saveStrokes(p.id, StrokeStore.encode(strokes), strokes.size) }
            }
            val images = dirtyPageImages
            if (images != null) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { repo.savePageImages(images.first, images.second) }
            }
        }
    }

    var colorPop by remember { mutableStateOf(false) }
    var penPopover by remember { mutableStateOf<PenType?>(null) }
    var highlighterPopover by remember { mutableStateOf(false) }
    var tapePopover by remember { mutableStateOf(false) }
    var morePop by remember { mutableStateOf(false) }
    fun addFavorite(toolType: InkTool, penType: PenType, settings: ToolBrushSettings) {
        if (favoritePresets.size >= 8) return
        val preset = FavoritePenPreset(toolType, penType, settings.color, settings.size, settings.opacity)
        favoritePresets = (favoritePresets + preset).distinct().take(8)
        writeFavoritePresets(preferences, favoritePresets)
    }
    fun removeFavorite(preset: FavoritePenPreset) {
        favoritePresets = favoritePresets - preset
        writeFavoritePresets(preferences, favoritePresets)
    }
    val toolbarOverlayOpen = penPopover != null || highlighterPopover || tapePopover || colorPop || morePop ||
        showPenButtonSettings || editingBlock != null || newBlockAt != null
    LaunchedEffect(drawingActive, toolbarPinned, toolbarOverlayOpen) {
        if (toolbarPinned || toolbarOverlayOpen) {
            toolbarAutoHidden = false
        } else if (drawingActive) {
            toolbarAutoHidden = true
        } else {
            delay(500)
            toolbarAutoHidden = false
        }
    }
    val haptics = LocalHapticFeedback.current

    fun currentDrawingSelection() = DrawingToolSelection(tool, pen)
    fun isDrawingSelection(selection: DrawingToolSelection) =
        selection.tool == InkTool.PEN || selection.tool == InkTool.HIGHLIGHTER || selection.tool == InkTool.TAPE

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
            StylusButtonAction.SWITCH_TO_ERASER -> {
                switchToEraser()
                stylusConfirmation = "Eraser"
            }
            StylusButtonAction.PREVIOUS_PEN -> {
                switchToPreviousPen()
                stylusConfirmation = "Previous pen"
            }
        }
    }

    LaunchedEffect(stylusConfirmation) {
        if (stylusConfirmation != null) {
            delay(1200)
            stylusConfirmation = null
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

    fun applyFavorite(preset: FavoritePenPreset) {
        val settings = ToolBrushSettings(preset.color, preset.size, preset.opacity)
        if (preset.tool == InkTool.HIGHLIGHTER) {
            selectHighlighter()
            highlighterSettings = settings
            writeBrushSettings(preferences, "highlighter", settings)
        } else {
            selectPen(preset.pen)
            penSettings = penSettings + (preset.pen to settings)
            writeBrushSettings(preferences, "pen.${preset.pen.name.lowercase()}", settings)
        }
        penPopover = null
        highlighterPopover = false
        tapePopover = false
    }

    fun chooseColor(next: Int) {
        val chosen = if (tool == InkTool.TAPE) {
            TAPE_COLORS.minBy { candidate ->
                val dr = android.graphics.Color.red(candidate) - android.graphics.Color.red(next)
                val dg = android.graphics.Color.green(candidate) - android.graphics.Color.green(next)
                val db = android.graphics.Color.blue(candidate) - android.graphics.Color.blue(next)
                dr * dr + dg * dg + db * db
            }
        } else next
        if (chosen != color) {
            recentColors = (listOf(color) + recentColors.filterNot { it == color || it == chosen })
                .distinct().take(8)
            updateActiveBrushSettings { it.copy(color = chosen) }
        }
        if (recolorSelectionMode && hasSelection) surface?.recolorSelection(chosen)
        recolorSelectionMode = false
        colorPop = false
    }

    fun setAlwaysStraight(enabled: Boolean) {
        alwaysStraight = enabled
        preferences.edit().putBoolean("highlighter.alwaysStraight", enabled).apply()
    }

    fun setShapeStraightening(enabled: Boolean) {
        shapeStraightening = enabled
        preferences.edit().putBoolean("shape.straightening", enabled).apply()
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val toolbarContentColor = if (darkTheme) Color(0xFFEDEDED) else Color(0xFF333333)
    val displayedRecentColors = (recentColors + listOf(0xFFFFFFFF.toInt(), 0xFF1B2A4E.toInt()))
        .distinct()
        .take(2)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(toolbarColor)
            .onGloballyPositioned { editorOriginInRoot = it.localToRoot(Offset.Zero) },
    ) {
        val tabletWidth = maxWidth >= 840.dp
        val density = androidx.compose.ui.platform.LocalDensity.current
        val verticalToolbar = toolbarEdge == EditorToolbarEdge.START || toolbarEdge == EditorToolbarEdge.END
        val compactToolbar = maxWidth < 600.dp
        val narrowToolbar = maxWidth < 1024.dp
        val toolbarWidth = when {
            verticalToolbar -> 76.dp
            compactToolbar -> (maxWidth - 24.dp).coerceAtLeast(0.dp)
            narrowToolbar -> (maxWidth - TOP_CHROME_CLEARANCE * 2).coerceAtLeast(320.dp)
            else -> minOf(740.dp, (maxWidth - TOP_CHROME_CLEARANCE * 2).coerceAtLeast(0.dp))
        }
        val sideToolbarClearance = if (verticalToolbar) 48.dp else 0.dp
        val pageIndicatorClearance = if (toolbarEdge == EditorToolbarEdge.START) 64.dp else 0.dp
        val toolbarSafeHeight =
            (maxHeight - statusBarHeight - navigationBarHeight - 24.dp - sideToolbarClearance - pageIndicatorClearance)
                .coerceAtLeast(0.dp)
        val containerWidthPx = with(density) { maxWidth.roundToPx() }
        val containerHeightPx = with(density) { maxHeight.roundToPx() }
        val statusBarPx = with(density) { statusBarHeight.roundToPx() }
        val navigationBarPx = with(density) { navigationBarHeight.roundToPx() }
        val toolbarGapPx = with(density) { 12.dp.roundToPx() }
        val safeTopPx = statusBarPx + toolbarGapPx
        val topToolbarClearance = if (compactToolbar && !verticalToolbar) 88.dp else sideToolbarClearance
        val dockSafeTopPx = safeTopPx + with(density) { topToolbarClearance.roundToPx() }
        val chromeInsetPx = if (toolbarEdge == EditorToolbarEdge.TOP && toolbarSize.height > 0) {
            dockSafeTopPx + toolbarSize.height + toolbarGapPx
        } else {
            safeTopPx + with(density) { 48.dp.roundToPx() }
        }
        LaunchedEffect(surface, chromeInsetPx) { surface?.setTopInset(chromeInsetPx.toFloat()) }

        fun dockedToolbarPosition(edge: EditorToolbarEdge, size: IntSize): IntOffset {
            val maxX = (containerWidthPx - size.width).coerceAtLeast(0)
            val maxY = (containerHeightPx - size.height).coerceAtLeast(0)
            val safeMinY = dockSafeTopPx.coerceIn(0, maxY)
            val pageIndicatorClearancePx = if (edge == EditorToolbarEdge.START) with(density) { 64.dp.roundToPx() } else 0
            val safeMaxY = (containerHeightPx - navigationBarPx - toolbarGapPx - pageIndicatorClearancePx - size.height)
                .coerceIn(safeMinY, maxY)
            val x = when (edge) {
                EditorToolbarEdge.TOP, EditorToolbarEdge.BOTTOM -> (containerWidthPx - size.width) / 2
                EditorToolbarEdge.START -> toolbarGapPx.coerceAtMost(maxX)
                EditorToolbarEdge.END -> (maxX - toolbarGapPx).coerceAtLeast(0)
            }
            val y = when (edge) {
                EditorToolbarEdge.TOP -> safeMinY
                EditorToolbarEdge.BOTTOM -> safeMaxY
                EditorToolbarEdge.START, EditorToolbarEdge.END ->
                    (safeMinY + safeMaxY) / 2
            }
            return IntOffset(x, y.coerceIn(0, maxY))
        }

        fun toolbarEdgeForRelease(position: Offset): EditorToolbarEdge = when {
            position.x < containerWidthPx * 0.18f -> EditorToolbarEdge.START
            position.x > containerWidthPx * 0.82f -> EditorToolbarEdge.END
            position.y < containerHeightPx * 0.5f -> EditorToolbarEdge.TOP
            else -> EditorToolbarEdge.BOTTOM
        }

        LaunchedEffect(toolbarEdge, toolbarSize, maxWidth, maxHeight, statusBarHeight, navigationBarHeight, toolbarDragging) {
            if (toolbarSize.width == 0 || toolbarSize.height == 0 || toolbarDragging) return@LaunchedEffect
            val target = dockedToolbarPosition(toolbarEdge, toolbarSize)
            if (!toolbarPositionReady) {
                toolbarX.snapTo(target.x.toFloat())
                toolbarY.snapTo(target.y.toFloat())
                toolbarPositionReady = true
            } else {
                coroutineScope {
                    launch {
                        toolbarX.animateTo(
                            target.x.toFloat(),
                            spring(dampingRatio = 1.05f, stiffness = 420f),
                        )
                    }
                    launch {
                        toolbarY.animateTo(
                            target.y.toFloat(),
                            spring(dampingRatio = 1.05f, stiffness = 420f),
                        )
                    }
                }
            }
        }

        val toolbarHidden = toolbarAutoHidden && !toolbarPinned && !toolbarOverlayOpen
        val toolbarAlpha by animateFloatAsState(
            targetValue = if (toolbarHidden) 0f else 1f,
            animationSpec = tween(Motion.chrome),
            label = "toolbar-alpha",
        )
        val hideDistance = if (toolbarHidden) 24.dp else 0.dp
        val toolbarShiftX = when (toolbarEdge) {
            EditorToolbarEdge.START -> -hideDistance
            EditorToolbarEdge.END -> hideDistance
            else -> 0.dp
        }
        val toolbarShiftY = when (toolbarEdge) {
            EditorToolbarEdge.TOP -> -hideDistance
            EditorToolbarEdge.BOTTOM -> hideDistance
            else -> 0.dp
        }
        val toolbarShiftXPx = with(density) { toolbarShiftX.toPx() }
        val toolbarShiftYPx = with(density) { toolbarShiftY.toPx() }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                InkSurfaceView(ctx).also { v ->
                    v.onDrawingActiveChanged = { drawingActive = it }
                    v.onStylusToolSwitch = ::handleStylusToolSwitch
                    v.onSelectionChanged = { hasSelection = it }
                    v.onSelectionBoundsChanged = { selectionBounds = it }
                    v.onZoomChanged = { zoomPercent = it }
                    v.onUndoRedoChanged = { undo, redo -> canUndo = undo; canRedo = redo }
                    v.onTextTap = { x, y, block -> if (block != null) editingBlock = block else newBlockAt = x to y }
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> if (view.width != surfaceWidth) surfaceWidth = view.width }
                    surface = v
                }
            },
        )

        val shelfTools: @Composable () -> Unit = {
            listOf(PenType.BALLPOINT, PenType.PENCIL, PenType.FOUNTAIN, PenType.MARKER).forEach { type ->
                val settings = penSettings[type] ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, type.defaultOpacity())
                Box {
                    ShelfCanvasItem(
                        label = type.label,
                        style = type.toShelfStyle(),
                        selected = tool == InkTool.PEN && pen == type,
                        color = settings.color,
                        opacity = settings.opacity,
                        enabled = !toolbarHidden,
                        vertical = verticalToolbar,
                        edge = toolbarEdge,
                        onClick = { selectPen(type) },
                    )
                    ToolbarPopover(
                        expanded = penPopover == type,
                        edge = toolbarEdge,
                        onDismissRequest = { penPopover = null },
                    ) {
                        BrushSettingsPopover(
                            title = type.label,
                            favoritePresets = favoritePresets,
                            onAddFavorite = { addFavorite(InkTool.PEN, type, settings) },
                            onSelectFavorite = ::applyFavorite,
                            onRemoveFavorite = ::removeFavorite,
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
                    enabled = !toolbarHidden,
                    vertical = verticalToolbar,
                    edge = toolbarEdge,
                    onClick = { selectHighlighter() },
                )
                ToolbarPopover(
                    expanded = highlighterPopover,
                    edge = toolbarEdge,
                    onDismissRequest = { highlighterPopover = false },
                ) {
                    BrushSettingsPopover(
                        title = "Highlighter",
                        favoritePresets = favoritePresets,
                        onAddFavorite = { addFavorite(InkTool.HIGHLIGHTER, pen, highlighterSettings) },
                        onSelectFavorite = ::applyFavorite,
                        onRemoveFavorite = ::removeFavorite,
                        color = highlighterSettings.color,
                        brushSize = highlighterSettings.size,
                        previewStrokeSize = highlighterSettings.size * 4f,
                        onSizeChange = { value -> updateActiveBrushSettings { it.copy(size = value) } },
                        opacity = highlighterSettings.opacity,
                        onOpacityChange = { value -> updateActiveBrushSettings { it.copy(opacity = value) } },
                        showOpacity = true,
                        alwaysStraight = alwaysStraight,
                        onAlwaysStraightChange = ::setAlwaysStraight,
                        bottomContent = {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Snap to text", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = snapHighlighterToText, onCheckedChange = { snapHighlighterToText = it })
                            }
                        },
                    )
                }
            }
            Box {
                ShelfCanvasItem(
                    label = "Tape",
                    style = ShelfDrawing.TAPE,
                    selected = tool == InkTool.TAPE,
                    color = tapeSettings.color,
                    enabled = !toolbarHidden,
                    vertical = verticalToolbar,
                    edge = toolbarEdge,
                    onClick = {
                        if (tool == InkTool.TAPE) tapePopover = true else selectTool(InkTool.TAPE)
                    },
                )
                ToolbarPopover(
                    expanded = tapePopover,
                    edge = toolbarEdge,
                    onDismissRequest = { tapePopover = false },
                ) {
                    TapeSettingsPopover(
                        color = tapeSettings.color,
                        tapeSize = tapeSettings.size,
                        onColorChange = { value -> updateActiveBrushSettings { it.copy(color = value) } },
                        onSizeChange = { value -> updateActiveBrushSettings { it.copy(size = value) } },
                    )
                }
            }
            val calligraphySettings = penSettings[PenType.CALLIGRAPHY]
                ?: ToolBrushSettings(0xFF000000.toInt(), 2.5f, PenType.CALLIGRAPHY.defaultOpacity())
            Box {
                ShelfCanvasItem(
                    label = PenType.CALLIGRAPHY.label,
                    style = ShelfDrawing.CALLIGRAPHY,
                    selected = tool == InkTool.PEN && pen == PenType.CALLIGRAPHY,
                    color = calligraphySettings.color,
                    opacity = calligraphySettings.opacity,
                    enabled = !toolbarHidden,
                    vertical = verticalToolbar,
                    edge = toolbarEdge,
                    onClick = { selectPen(PenType.CALLIGRAPHY) },
                )
                ToolbarPopover(
                    expanded = penPopover == PenType.CALLIGRAPHY,
                    edge = toolbarEdge,
                    onDismissRequest = { penPopover = null },
                ) {
                        BrushSettingsPopover(
                            title = PenType.CALLIGRAPHY.label,
                            favoritePresets = favoritePresets,
                            onAddFavorite = { addFavorite(InkTool.PEN, PenType.CALLIGRAPHY, calligraphySettings) },
                            onSelectFavorite = ::applyFavorite,
                            onRemoveFavorite = ::removeFavorite,
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
            ShelfCanvasItem(
                "Eraser", ShelfDrawing.ERASER, tool == InkTool.ERASER, color,
                enabled = !toolbarHidden, vertical = verticalToolbar, edge = toolbarEdge,
                onClick = { selectTool(InkTool.ERASER) },
            )
            ShelfCanvasItem(
                "Lasso", ShelfDrawing.LASSO, tool == InkTool.LASSO, color,
                enabled = !toolbarHidden, vertical = verticalToolbar, edge = toolbarEdge,
                onClick = { selectTool(InkTool.LASSO) },
            )
            ShelfCanvasItem(
                "Text", ShelfDrawing.TEXT, tool == InkTool.TEXT, color,
                enabled = !toolbarHidden, vertical = verticalToolbar, edge = toolbarEdge,
                onClick = { selectTool(InkTool.TEXT) },
            )
            ShelfCanvasItem(
                "Ruler", ShelfDrawing.RULER, rulerVisible, color,
                enabled = !toolbarHidden, vertical = verticalToolbar, edge = toolbarEdge,
                onClick = {
                    rulerVisible = !rulerVisible
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            )
            ShelfCanvasItem(
                "Laser", ShelfDrawing.LASER, tool == InkTool.LASER, color,
                enabled = !toolbarHidden, vertical = verticalToolbar, edge = toolbarEdge,
                onClick = { selectTool(InkTool.LASER) },
            )
        }
        val colorControls: @Composable () -> Unit = {
            if (verticalToolbar) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 2.dp)) {
                        IconButton(
                            onClick = { recolorSelectionMode = false; colorPop = true },
                            enabled = !toolbarHidden,
                            modifier = Modifier.size(36.dp),
                        ) {
                            ColorWheelButton(color)
                        }
                        ToolbarPopover(
                            expanded = colorPop,
                            edge = toolbarEdge,
                            onDismissRequest = { colorPop = false; recolorSelectionMode = false },
                        ) {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Colour",
                                        Modifier.padding(4.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    PALETTE.chunked(6).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            row.forEach { swatch ->
                                                ColorDot(swatch, swatch == color, 36.dp, enabled = !toolbarHidden) { chooseColor(swatch) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    displayedRecentColors.forEach { swatch ->
                        ColorDot(swatch, false, 32.dp, enabled = !toolbarHidden) { chooseColor(swatch) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 7.dp)) {
                        IconButton(
                            onClick = { recolorSelectionMode = false; colorPop = true },
                            enabled = !toolbarHidden,
                            modifier = Modifier.size(40.dp),
                        ) {
                            ColorWheelButton(color)
                        }
                        ToolbarPopover(
                            expanded = colorPop,
                            edge = toolbarEdge,
                            onDismissRequest = { colorPop = false; recolorSelectionMode = false },
                        ) {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Colour",
                                        Modifier.padding(4.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    PALETTE.chunked(6).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            row.forEach { swatch ->
                                                ColorDot(swatch, swatch == color, 36.dp, enabled = !toolbarHidden) { chooseColor(swatch) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    displayedRecentColors.forEach { swatch ->
                        ColorDot(swatch, false, 40.dp, enabled = !toolbarHidden) { chooseColor(swatch) }
                    }
                }
            }
        }
        val historyControls: @Composable () -> Unit = {
            if (verticalToolbar) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        enabled = canUndo && !toolbarHidden,
                        onClick = { surface?.undo() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        HistoryArrow(redo = false, tint = toolbarContentColor.copy(alpha = if (canUndo) 1f else 0.38f))
                    }
                    IconButton(
                        enabled = canRedo && !toolbarHidden,
                        onClick = { surface?.redo() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        HistoryArrow(redo = true, tint = toolbarContentColor.copy(alpha = if (canRedo) 1f else 0.38f))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = canUndo && !toolbarHidden,
                        onClick = { surface?.undo() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        HistoryArrow(redo = false, tint = toolbarContentColor.copy(alpha = if (canUndo) 1f else 0.38f))
                    }
                    IconButton(
                        enabled = canRedo && !toolbarHidden,
                        onClick = { surface?.redo() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        HistoryArrow(redo = true, tint = toolbarContentColor.copy(alpha = if (canRedo) 1f else 0.38f))
                    }
                }
            }
        }
        val toolbarPanelModifier = Modifier
            .offset {
                if (toolbarDragging) toolbarDragPosition
                else IntOffset(toolbarX.value.roundToInt(), toolbarY.value.roundToInt())
            }
            .width(toolbarWidth)
            .then(if (verticalToolbar) Modifier.heightIn(max = toolbarSafeHeight) else Modifier.height(76.dp))
            .onSizeChanged { if (toolbarSize != it) toolbarSize = it }
            .graphicsLayer {
                alpha = if (toolbarPositionReady) toolbarAlpha else 0f
                translationX = toolbarShiftXPx
                translationY = toolbarShiftYPx
            }
        Surface(
            modifier = toolbarPanelModifier,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            if (verticalToolbar) {
                Column(
                    Modifier.width(toolbarWidth)
                        .heightIn(max = toolbarSafeHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EditorToolbarGrip(
                        enabled = toolbarPositionReady && !toolbarHidden,
                        modifier = Modifier.padding(vertical = 2.dp)
                            .onGloballyPositioned { toolbarGripOriginInRoot = it.localToRoot(Offset.Zero) },
                        gripSize = 24.dp,
                        onDragStart = { fingerPosition ->
                            toolbarDragPosition = IntOffset(toolbarX.value.roundToInt(), toolbarY.value.roundToInt())
                            toolbarDragFingerPosition = toolbarGripOriginInRoot + fingerPosition - editorOriginInRoot
                            toolbarDragging = true
                        },
                        onDrag = { delta ->
                            toolbarDragFingerPosition += delta
                            val maxX = (containerWidthPx - toolbarSize.width).coerceAtLeast(0)
                            val maxY = (containerHeightPx - toolbarSize.height).coerceAtLeast(0)
                            toolbarDragPosition = IntOffset(
                                (toolbarDragPosition.x + delta.x).roundToInt().coerceIn(0, maxX),
                                (toolbarDragPosition.y + delta.y).roundToInt().coerceIn(0, maxY),
                            )
                        },
                        onDragEnd = {
                            val position = toolbarDragPosition
                            val edge = toolbarEdgeForRelease(toolbarDragFingerPosition)
                            scope.launch {
                                toolbarX.snapTo(position.x.toFloat())
                                toolbarY.snapTo(position.y.toFloat())
                                toolbarEdge = edge
                                toolbarDragging = false
                            }
                        },
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { shelfTools() }
                    Box(
                        Modifier.padding(vertical = 4.dp).fillMaxWidth(0.4f).height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    colorControls()
                    Box(
                        Modifier.padding(vertical = 4.dp).fillMaxWidth(0.4f).height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    historyControls()
                }
            } else {
                Row(
                Modifier.width(toolbarWidth).height(76.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorToolbarGrip(
                        enabled = toolbarPositionReady && !toolbarHidden,
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .onGloballyPositioned { toolbarGripOriginInRoot = it.localToRoot(Offset.Zero) },
                        onDragStart = { fingerPosition ->
                            toolbarDragPosition = IntOffset(toolbarX.value.roundToInt(), toolbarY.value.roundToInt())
                            toolbarDragFingerPosition = toolbarGripOriginInRoot + fingerPosition - editorOriginInRoot
                            toolbarDragging = true
                        },
                        onDrag = { delta ->
                            toolbarDragFingerPosition += delta
                            val maxX = (containerWidthPx - toolbarSize.width).coerceAtLeast(0)
                            val minY = dockSafeTopPx.coerceAtMost((containerHeightPx - toolbarSize.height).coerceAtLeast(0))
                            val maxY = (containerHeightPx - navigationBarPx - toolbarGapPx - toolbarSize.height)
                                .coerceAtLeast(minY)
                            toolbarDragPosition = IntOffset(
                                (toolbarDragPosition.x + delta.x).roundToInt().coerceIn(0, maxX),
                                (toolbarDragPosition.y + delta.y).roundToInt().coerceIn(minY, maxY),
                            )
                        },
                        onDragEnd = {
                            val position = toolbarDragPosition
                            val edge = toolbarEdgeForRelease(toolbarDragFingerPosition)
                            scope.launch {
                                toolbarX.snapTo(position.x.toFloat())
                                toolbarY.snapTo(position.y.toFloat())
                                toolbarEdge = edge
                                toolbarDragging = false
                            }
                        },
                    )
                    if (narrowToolbar) {
                        // Tools and colours scroll; undo/redo stay pinned so they are never hidden off-screen.
                        Row(
                            Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).clipToBounds(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            shelfTools()
                            Box(
                                Modifier.padding(horizontal = 6.dp).fillMaxHeight(0.4f).width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            colorControls()
                        }
                        Box(
                            Modifier.padding(horizontal = 6.dp).fillMaxHeight(0.4f).width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        historyControls()
                    } else {
                        Row(
                            Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).clipToBounds(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { shelfTools() }
                        Box(
                            Modifier.padding(horizontal = 8.dp).fillMaxHeight(0.4f).width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        colorControls()
                        Box(
                            Modifier.padding(horizontal = 8.dp).fillMaxHeight(0.4f).width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        historyControls()
                    }
                }
            }
        }

        if (toolbarDragging) {
            val edgeHintModifier = when (toolbarEdgeForRelease(toolbarDragFingerPosition)) {
                EditorToolbarEdge.TOP ->
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(6.dp)
                EditorToolbarEdge.BOTTOM ->
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp)
                EditorToolbarEdge.START ->
                    Modifier.align(Alignment.CenterStart).fillMaxHeight().width(6.dp)
                EditorToolbarEdge.END ->
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp)
            }
            Box(edgeHintModifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
        }

        stylusConfirmation?.let { message ->
            val chipWidthPx = with(density) { 112.dp.roundToPx() }
            val gapPx = with(density) { 8.dp.roundToPx() }
            val toolbarXpx = toolbarX.value.roundToInt()
            val toolbarYpx = toolbarY.value.roundToInt()
            val chipX = when (toolbarEdge) {
                EditorToolbarEdge.TOP, EditorToolbarEdge.BOTTOM ->
                    toolbarXpx + (toolbarSize.width - chipWidthPx) / 2
                EditorToolbarEdge.START -> toolbarXpx + toolbarSize.width + gapPx
                EditorToolbarEdge.END -> toolbarXpx - chipWidthPx - gapPx
            }.coerceIn(0, (containerWidthPx - chipWidthPx).coerceAtLeast(0))
            val chipY = when (toolbarEdge) {
                EditorToolbarEdge.TOP -> toolbarYpx + toolbarSize.height + gapPx
                EditorToolbarEdge.BOTTOM -> toolbarYpx - with(density) { 40.dp.roundToPx() } - gapPx
                EditorToolbarEdge.START, EditorToolbarEdge.END ->
                    toolbarYpx + (toolbarSize.height - with(density) { 40.dp.roundToPx() }) / 2
            }.coerceIn(0, (containerHeightPx - with(density) { 40.dp.roundToPx() }).coerceAtLeast(0))
            Surface(
                modifier = Modifier.offset { IntOffset(chipX, chipY) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    message,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Surface(
            modifier = Modifier.offset { IntOffset(with(density) { 12.dp.roundToPx() }, safeTopPx) }
                .graphicsLayer {
                    alpha = toolbarAlpha
                    translationX = toolbarShiftXPx
                    translationY = toolbarShiftYPx
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = onBack,
                enabled = !toolbarHidden,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = toolbarContentColor, modifier = Modifier.size(22.dp))
            }
        }
        Surface(
            modifier = Modifier.offset { IntOffset(with(density) { 60.dp.roundToPx() }, safeTopPx) }
                .graphicsLayer { alpha = toolbarAlpha; translationX = toolbarShiftXPx; translationY = toolbarShiftYPx },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            IconButton(onClick = { pageSidebarOpen = !pageSidebarOpen }, enabled = !toolbarHidden, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.List, "Pages and bookmarks", tint = toolbarContentColor, modifier = Modifier.size(22.dp))
            }
        }
        Surface(
            modifier = Modifier.offset {
                IntOffset((containerWidthPx - with(density) { 132.dp.roundToPx() }).coerceAtLeast(0), safeTopPx)
            }.graphicsLayer {
                alpha = toolbarAlpha
                translationX = toolbarShiftXPx
                translationY = toolbarShiftYPx
            },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                AudioRecorderButton(
                    repo = repo,
                    notebookId = notebookId,
                    notebookTitle = notebook?.title ?: "Lecture",
                    onRecordingSelected = { selectedAudioRecordingId = it },
                    modifier = Modifier.size(40.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            runCatching { exporter.exportNotebook(notebookId, notebook?.title ?: "Notebook") }
                                .onSuccess { exporter.share(it) }
                                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                    enabled = !toolbarHidden,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.IosShare, "Share notebook as PDF", modifier = Modifier.size(22.dp), tint = toolbarContentColor)
                }
                Box {
                    IconButton(
                        onClick = { morePop = true },
                        enabled = !toolbarHidden,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(24.dp), tint = toolbarContentColor)
                    }
                    DropdownMenu(
                        expanded = morePop,
                        onDismissRequest = { morePop = false },
                        modifier = Modifier.width(320.dp),
                        shape = RoundedCornerShape(22.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        if (tabletWidth && onOpenSideBySide != null) {
                            DropdownMenuItem(
                                text = { Text("Open side by side") },
                                onClick = {
                                    morePop = false
                                    onOpenSideBySide(notebookId, pageId)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (page?.bookmarked == true) "Remove bookmark" else "Bookmark page") },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                            onClick = {
                                morePop = false
                                page?.let { current ->
                                    scope.launch { repo.setPageBookmarked(current.id, !current.bookmarked) }
                                }
                            },
                        )
                        if (tool == InkTool.LASSO && clipboardHasContent) {
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                onClick = { surface?.paste(); morePop = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Insert image") },
                            leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = {
                                morePop = false
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        ToggleItem(
                            "Shape straightening",
                            "Hold to straighten pen shapes",
                            Icons.Outlined.Category,
                            shapeStraightening,
                            ::setShapeStraightening,
                        )
                        ToggleItem("Pin toolbar", "Keep the toolbar visible while writing", Icons.Outlined.PushPin, toolbarPinned) {
                            toolbarPinned = it
                        }
                        ToggleItem("Zoom box", "Magnified line for small writing", Icons.Outlined.ZoomIn, zoomBox) { zoomBox = it }
                        ToggleItem("Finger draws", "Otherwise finger pans and zooms", Icons.Outlined.TouchApp, fingerDraws) { fingerDraws = it }
                        ToggleItem("Pen diagnostics", "Pressure, tilt and latency", Icons.Outlined.Speed, showLab) { showLab = it }
                        DropdownMenuItem(
                            text = { Text("Pen buttons") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { morePop = false; showPenButtonSettings = true },
                        )
                        ToggleItem("Pixel eraser", "Erase pixels, not strokes", Icons.Outlined.AutoFixNormal, pixelEraser) { pixelEraser = it }
                    }
                }
            }
        }

        if (hasSelection && selectionBounds != null) {
            val bounds = requireNotNull(selectionBounds)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val barWidth = 320.dp
            val barHeight = 56.dp
            val left = with(density) {
                (bounds.centerX().toDp() + 12.dp - barWidth / 2)
                    .coerceIn(8.dp, (maxWidth - barWidth - 8.dp).coerceAtLeast(8.dp))
            }
            val top = with(density) {
                (bounds.top.toDp() - barHeight - 8.dp)
                    .coerceIn(8.dp, (maxHeight - barHeight - 8.dp).coerceAtLeast(8.dp))
            }
            Surface(
                modifier = Modifier.offset(left, top),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 4.dp,
            ) {
                Row(
                    Modifier.width(barWidth).height(barHeight).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val imageSelected = surface?.hasImageSelection() == true
                    SelectionAction(Icons.Outlined.ControlPointDuplicate, "Duplicate", Modifier.weight(1f)) {
                        surface?.duplicateSelection()
                    }
                    if (!imageSelected) {
                        if (surface?.selectedStrokes()?.isNotEmpty() == true) {
                            SelectionAction(Icons.Outlined.Spellcheck, "Text", Modifier.weight(1f)) {
                                val target = surface
                                val currentPage = page
                                val selected = target?.selectedStrokes().orEmpty()
                                if (target != null && currentPage != null && selected.isNotEmpty()) {
                                    beginTextConversion(target, currentPage, selected)
                                }
                            }
                        }
                        SelectionAction(Icons.Outlined.ContentCopy, "Copy", Modifier.weight(1f)) {
                            surface?.copySelection()
                            clipboardHasContent = !SelectionClipboard.isEmpty
                        }
                        SelectionAction(Icons.Outlined.Palette, "Recolour", Modifier.weight(1f)) {
                            recolorSelectionMode = true
                            colorPop = true
                        }
                    }
                    SelectionAction(
                        Icons.Default.DeleteOutline,
                        "Delete",
                        Modifier.weight(1f),
                        MaterialTheme.colorScheme.error,
                    ) {
                        surface?.deleteSelection()
                    }
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

        if (showLab) {
            val lastStylusEvent by StylusInputDiagnostics.lastEvent.collectAsState()
            Text(
            text = "tool=${sample.toolType}  pressure=%.3f  tilt=%.0f°  orient=%.0f°  input→handler=%d ms  batched=%d  strokes=%d\n$lastStylusEvent".format(
                sample.pressure, sample.tilt, sample.orientation, sample.handlerLatencyMs, sample.historySize, sample.strokeCount
            ),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.shapes.small).padding(8.dp),
        )
        }

        AudioPlaybackBar(
            repo = repo,
            notebookId = notebookId,
            pages = pages,
            recordingId = selectedAudioRecordingId,
            onRecordingIdChange = { selectedAudioRecordingId = it },
            onPlaybackState = { recordingId, positionMs, timestamps ->
                surface?.setAudioPlayback(
                    pageId.takeIf { recordingId != null },
                    positionMs.takeIf { recordingId != null },
                    timestamps,
                )
            },
            seekToMs = requestedAudioSeekMs,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
        )

        if (pageSidebarOpen && maxWidth >= 600.dp) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).clickable { pageSidebarOpen = false })
                Surface(
                    Modifier.fillMaxHeight().width(260.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                ) {
                    PageSidebar(
                        repo,
                        pages,
                        pageId,
                        onNavigateToPage,
                        { pageSidebarOpen = false },
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                        onAudioRecordingSelected = { selectedAudioRecordingId = it; pageSidebarOpen = false },
                    )
                }
            }
        }
        if (pageSidebarOpen && maxWidth < 600.dp) {
            ModalBottomSheet(
                onDismissRequest = { pageSidebarOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                PageSidebar(
                    repo, pages, pageId, onNavigateToPage, { pageSidebarOpen = false },
                    Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    onAudioRecordingSelected = { selectedAudioRecordingId = it; pageSidebarOpen = false },
                )
            }
        }
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
            if (p != null) {
                val xPt = (x - 10f).coerceIn(0f, p.widthPt - 120f)
                val widthPt = (p.widthPt - x).coerceIn(120f, p.widthPt - 24f)
                scope.launch {
                    var textBlockId = repo.addTextBlock(p.id, xPt, y, widthPt, content)
                    surface?.recordExternalAction(
                        undoAction = { scope.launch { repo.deleteTextBlock(textBlockId, p.id) } },
                        redoAction = {
                            scope.launch {
                                textBlockId = repo.addTextBlock(p.id, xPt, y, widthPt, content)
                            }
                        },
                    )
                }
            }
            newBlockAt = null
        }
    }
    editingBlock?.let { b ->
        TextBlockDialog(initial = b.content, onDismiss = { editingBlock = null },
            onDelete = {
                var textBlockId = b.id
                scope.launch {
                    repo.deleteTextBlock(textBlockId, b.pageId)
                    surface?.recordExternalAction(
                        undoAction = {
                            scope.launch {
                                textBlockId = repo.addTextBlock(
                                    b.pageId, b.xPt, b.yPt, b.wPt, b.content,
                                    b.fontSizePt, b.colorArgb, b.rotationDegrees,
                                )
                            }
                        },
                        redoAction = { scope.launch { repo.deleteTextBlock(textBlockId, b.pageId) } },
                    )
                }
                editingBlock = null
            }) { content ->
            scope.launch {
                repo.updateTextBlock(b.id, b.pageId, content = content)
                surface?.recordExternalAction(
                    undoAction = { scope.launch { repo.updateTextBlock(b.id, b.pageId, content = b.content) } },
                    redoAction = { scope.launch { repo.updateTextBlock(b.id, b.pageId, content = content) } },
                )
            }
            editingBlock = null
        }
    }
    textConversion?.let { draft ->
        AlertDialog(
            onDismissRequest = { dismissTextConversion() },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            title = { Text("Convert handwriting") },
            text = {
                if (draft.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Recognizing handwriting…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.text,
                            onValueChange = { value ->
                                textConversion = textConversion?.takeIf { it.token == draft.token }?.copy(text = value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                        if (draft.alternatives.isNotEmpty()) {
                            Text("Alternatives", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                draft.alternatives.forEach { alternative ->
                                    AssistChip(
                                        onClick = {
                                            textConversion = textConversion?.takeIf { it.token == draft.token }?.copy(text = alternative)
                                        },
                                        label = { Text(alternative, maxLines = 1) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !draft.loading && draft.text.isNotBlank(),
                    onClick = {
                        val current = textConversion?.takeIf { it.token == draft.token }
                        val text = current?.text?.trim().orEmpty()
                        val target = surface
                        if (current != null && text.isNotEmpty() && target != null) {
                            target.convertSelectionToText(
                                TextBlock(
                                    id = java.util.UUID.randomUUID().toString(),
                                    pageId = current.page.id,
                                    xPt = current.geometry.xPt,
                                    yPt = current.geometry.yPt,
                                    wPt = current.geometry.widthPt,
                                    direction = RecognitionCandidatePolicy.directionFor(text),
                                    content = text,
                                    updatedAt = System.currentTimeMillis(),
                                    fontSizePt = current.geometry.fontSizePt,
                                    colorArgb = current.colorArgb,
                                ),
                            )
                        }
                        dismissTextConversion()
                    },
                ) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { dismissTextConversion() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EditorToolbarGrip(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    gripSize: Dp = 32.dp,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val gripColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    Box(
        Modifier.size(gripSize).then(modifier)
            .pointerInput(enabled) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                    )
                }
            }
            .semantics { contentDescription = "Drag toolbar" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val radius = 1.35.dp.toPx()
            val horizontalGap = 5.5.dp.toPx()
            val verticalGap = 5.5.dp.toPx()
            for (row in 0..1) {
                for (column in 0..2) {
                    drawCircle(
                        gripColor,
                        radius,
                        Offset(
                            center.x + (column - 1) * horizontalGap,
                            center.y + (row - 0.5f) * verticalGap,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarPopover(
    expanded: Boolean,
    edge: EditorToolbarEdge,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (expanded) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        Popup(
            popupPositionProvider = remember(edge, density) {
                EditorToolbarPopupPositionProvider(edge, density)
            },
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
            content = content,
        )
    }
}

private class EditorToolbarPopupPositionProvider(
    private val edge: EditorToolbarEdge,
    private val density: androidx.compose.ui.unit.Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = with(density) { 12.dp.roundToPx() }
        val preferredX = when (edge) {
            EditorToolbarEdge.TOP, EditorToolbarEdge.BOTTOM ->
                (anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2
            EditorToolbarEdge.START -> anchorBounds.right + gap
            EditorToolbarEdge.END -> anchorBounds.left - popupContentSize.width - gap
        }
        val preferredY = when (edge) {
            EditorToolbarEdge.TOP -> anchorBounds.bottom + gap
            EditorToolbarEdge.BOTTOM -> anchorBounds.top - popupContentSize.height - gap
            EditorToolbarEdge.START, EditorToolbarEdge.END ->
                (anchorBounds.top + anchorBounds.bottom - popupContentSize.height) / 2
        }
        return IntOffset(
            preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            preferredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
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

private enum class ShelfDrawing { BALLPOINT, PENCIL, FOUNTAIN, MARKER, HIGHLIGHTER, TAPE, CALLIGRAPHY, ERASER, LASSO, TEXT, RULER, LASER }

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
    enabled: Boolean = true,
    vertical: Boolean = false,
    edge: EditorToolbarEdge = EditorToolbarEdge.TOP,
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
        Modifier.width(if (vertical) 76.dp else if (wide) 50.dp else 40.dp)
            .height(if (vertical) if (wide) 40.dp else 34.dp else 76.dp)
            .clipToBounds()
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ink = Color(color)
            val translucentInk = ink.copy(alpha = opacity.coerceIn(0.2f, 1f))
            if (vertical) {
                val logicalWidth = (if (wide) 50.dp else 40.dp).toPx()
                val logicalHeight = 76.dp.toPx()
                val verticalScale = (size.height / logicalWidth).coerceAtMost(1f)
                val alongDockScale = ((size.width - 4.dp.toPx()) / logicalHeight).coerceAtMost(1f)
                withTransform({
                    translate(size.width / 2f, size.height / 2f)
                    rotate(if (edge == EditorToolbarEdge.START) 90f else -90f, pivot = Offset.Zero)
                    scale(scaleX = verticalScale, scaleY = alongDockScale, pivot = Offset.Zero)
                    translate(-logicalWidth / 2f, -logicalHeight / 2f)
                }) {
                    ShelfPainter(this, dark, ink, translucentInk, logicalWidth, logicalHeight, bottomExtensionDp = 0f)
                        .draw(style, top = 7.dp.toPx() + lift.toPx())
                }
            } else {
                ShelfPainter(this, dark, ink, translucentInk)
                    .draw(style, top = 7.dp.toPx() + lift.toPx())
            }
        }
    }
}

private class ShelfPainter(
    val scope: DrawScope,
    val dark: Boolean,
    val ink: Color,
    val translucentInk: Color,
    canvasWidth: Float = scope.size.width,
    canvasHeight: Float = scope.size.height,
    bottomExtensionDp: Float = 2f,
) {
    private val u = with(scope) { 1.dp.toPx() }
    private val cx = canvasWidth / 2f
    private val bottom = canvasHeight + bottomExtensionDp * u

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
        ShelfDrawing.TAPE -> tape(top)
        ShelfDrawing.CALLIGRAPHY -> brushPen(top)
        ShelfDrawing.ERASER -> eraser(top)
        ShelfDrawing.LASSO -> lasso(top)
        ShelfDrawing.TEXT -> textTool(top)
        ShelfDrawing.RULER -> ruler(top)
        ShelfDrawing.LASER -> laser(top)
    }

    private fun laser(t: Float) {
        val tipY = t + 2.5f * u
        val halfWidth = 2.5f * u
        scope.drawCircle(Color(0x44FF3B30), 8f * u, Offset(cx, tipY + 1.5f * u))
        scope.drawCircle(Color(0x88FF3B30), 4f * u, Offset(cx, tipY + 1.5f * u))
        val body = Path().apply {
            moveTo(cx - halfWidth * 0.55f, tipY + 3f * u)
            lineTo(cx + halfWidth * 0.55f, tipY + 3f * u)
            lineTo(cx + halfWidth, bottom - 4f * u)
            lineTo(cx - halfWidth, bottom - 4f * u)
            close()
        }
        part(body, Color(0xFF34373B), Color(0xFF17191C))
        scope.drawLine(
            Color.White.copy(alpha = if (dark) 0.18f else 0.34f),
            Offset(cx - halfWidth * 0.18f, tipY + 6f * u),
            Offset(cx - halfWidth * 0.45f, bottom - 7f * u),
            0.7f * u,
            StrokeCap.Round,
        )
        scope.drawCircle(Color(0xFFFF3B30), 2f * u, Offset(cx, tipY + 1.5f * u))
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

    private fun tape(t: Float) {
        val halfWidth = 10f * u
        val top = t + 8f * u
        val tail = Path().apply {
            moveTo(cx - 6f * u, top + 11f * u)
            lineTo(cx - 1f * u, top + 11f * u)
            lineTo(cx - 1f * u, bottom)
            lineTo(cx - 6f * u, bottom)
            close()
        }
        part(tail, ink.copy(alpha = 0.94f), outline)
        val roll = Path().apply { addOval(Rect(cx - halfWidth, top, cx + halfWidth, top + 20f * u)) }
        part(roll, bodyBrush(halfWidth), outline)
        scope.drawCircle(if (dark) Color(0xFF232428) else Color(0xFFF7F4EA), 5f * u, Offset(cx, top + 10f * u))
        scope.drawCircle(outline, 5f * u, Offset(cx, top + 10f * u), style = DrawStroke(width = 0.8f * u))
        scope.drawLine(gloss, Offset(cx - 5f * u, top + 4f * u), Offset(cx - 5f * u, top + 16f * u), 1.2f * u)
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
@OptIn(ExperimentalMaterial3Api::class)
private fun BrushSettingsPopover(
    title: String,
    favoritePresets: List<FavoritePenPreset>,
    onAddFavorite: () -> Unit,
    onSelectFavorite: (FavoritePenPreset) -> Unit,
    onRemoveFavorite: (FavoritePenPreset) -> Unit,
    color: Int,
    brushSize: Float,
    previewStrokeSize: Float = brushSize,
    onSizeChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showOpacity: Boolean,
    alwaysStraight: Boolean? = null,
    onAlwaysStraightChange: ((Boolean) -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val sliderColors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.onSurface,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
        thumbColor = MaterialTheme.colorScheme.onSurface,
    )
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.width(264.dp).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FavoritePresetsRow(favoritePresets, onAddFavorite, onSelectFavorite, onRemoveFavorite)
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            drawLine(
                Color(color).copy(alpha = if (showOpacity) opacity else 1f),
                Offset(12.dp.toPx(), size.height / 2f),
                Offset(size.width - 12.dp.toPx(), size.height / 2f),
                strokeWidth = previewStrokeSize.dp.toPx().coerceAtLeast(1.dp.toPx()),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Stroke width", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.1f".format(brushSize), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1.5f, 2.5f, 4f, 6f, 9f).forEach { preset ->
                val selected = kotlin.math.abs(brushSize - preset) <= 0.25f
                val dotSize = (4f + ((preset - 1.5f) / 7.5f) * 12f).dp
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .clickable { onSizeChange(preset) }
                        .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(dotSize).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                }
            }
        }
        Slider(
            value = brushSize,
            onValueChange = onSizeChange,
            valueRange = 1f..10f,
            colors = sliderColors,
            thumb = { Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)) },
            track = { sliderState ->
                val activeColor = MaterialTheme.colorScheme.onSurface
                val inactiveColor = activeColor.copy(alpha = 0.16f)
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val start = 2.dp.toPx()
                    val end = size.width - start
                    val centerY = size.height / 2f
                    val minHalf = 1.dp.toPx()
                    val maxHalf = 8.dp.toPx()
                    val fraction = ((sliderState.value - sliderState.valueRange.start) /
                        (sliderState.valueRange.endInclusive - sliderState.valueRange.start))
                        .coerceIn(0f, 1f)
                    val progressX = start + (end - start) * fraction
                    val progressHalf = minHalf + (maxHalf - minHalf) * fraction
                    val wedge = Path().apply {
                        moveTo(start, centerY - minHalf)
                        lineTo(end, centerY - maxHalf)
                        lineTo(end, centerY + maxHalf)
                        lineTo(start, centerY + minHalf)
                        close()
                    }
                    drawPath(wedge, inactiveColor)
                    if (fraction > 0f) {
                        val selectedWedge = Path().apply {
                            moveTo(start, centerY - minHalf)
                            lineTo(progressX, centerY - progressHalf)
                            lineTo(progressX, centerY + progressHalf)
                            lineTo(start, centerY + minHalf)
                            close()
                        }
                        drawPath(selectedWedge, activeColor)
                    }
                }
            },
        )
        if (showOpacity) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%d%%".format((opacity * 100f).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Slider(
                value = opacity,
                onValueChange = onOpacityChange,
                valueRange = 0.1f..1f,
                colors = sliderColors,
                thumb = { Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)) },
                track = {
                    SliderDefaults.Track(
                        sliderState = it,
                        modifier = Modifier.height(4.dp),
                        colors = sliderColors,
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
            )
        }
        onAlwaysStraightChange?.let { onChange ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Always straight", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = alwaysStraight == true, onCheckedChange = onChange)
            }
        }
        bottomContent?.invoke()
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FavoritePresetsRow(
    presets: List<FavoritePenPreset>,
    onAdd: () -> Unit,
    onSelect: (FavoritePenPreset) -> Unit,
    onRemove: (FavoritePenPreset) -> Unit,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val presetBackground = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text("Favourites", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { preset ->
                Canvas(
                    Modifier.size(34.dp).combinedClickable(
                        onClick = { onSelect(preset) },
                        onLongClick = { onRemove(preset) },
                    ),
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(outlineColor, size.minDimension * 0.37f, center)
                    drawCircle(Color(preset.color), size.minDimension * 0.31f, center)
                    val innerRadius = size.minDimension * (0.07f + preset.size.coerceIn(1f, 36f) / 36f * 0.16f)
                    drawCircle(presetBackground, innerRadius, center)
                }
            }
            if (presets.size < 8) {
                Box(
                    Modifier.size(30.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clip(CircleShape).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TapeSettingsPopover(
    color: Int,
    tapeSize: Float,
    onColorChange: (Int) -> Unit,
    onSizeChange: (Float) -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.width(264.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tape", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                val left = 8.dp.toPx()
                val top = size.height * 0.3f
                val right = size.width - left
                val bottom = size.height * 0.7f
                clipRect(left, top, right, bottom) {
                    drawRect(Color(color), topLeft = Offset(left, top), size = Size(right - left, bottom - top))
                    var x = 0f
                    while (x < size.width) {
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(x, bottom), Offset(x + size.height, top), 2.dp.toPx())
                        x += 12.dp.toPx()
                    }
                }
            }
            Text("Width", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(16f, 24f, 36f).forEach { preset ->
                    FilterChip(
                        selected = tapeSize == preset,
                        onClick = { onSizeChange(preset) },
                        label = { Text("${preset.toInt()} pt") },
                    )
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TAPE_COLORS.forEach { swatch -> ColorDot(swatch, swatch == color, 32.dp) { onColorChange(swatch) } }
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
private fun ToggleItem(title: String, subtitle: String, leadingIcon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Column { Text(title); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingIcon = { Icon(leadingIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun ColorDot(
    argb: Int,
    selected: Boolean,
    dotSize: Dp = 32.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, label = "ring")
    val hairline = android.graphics.Color.luminance(argb) > 0.78f
    val ringColor = MaterialTheme.colorScheme.onSurface
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(dotSize).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)) {
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
