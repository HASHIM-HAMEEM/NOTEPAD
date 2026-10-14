package com.hashim.notepad.ui

import android.content.Context
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import android.view.MotionEvent
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import com.hashim.notepad.data.displayHeightPt
import com.hashim.notepad.data.displayWidthPt
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfPageCoordinates
import com.hashim.notepad.pdf.PdfPasswordVault
import com.hashim.notepad.pdf.PdfTextCache
import com.hashim.notepad.pdf.PdfTextRect
import com.hashim.notepad.pdf.PdfTextSearchHit
import com.hashim.notepad.pdf.PdfWordSnap
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private data class PdfReadSelection(
    val pageId: String,
    val start: Int,
    val end: Int,
    val text: String,
    val rects: List<PdfTextRect>,
    val mark: PdfMark? = null,
)

private enum class SelectionHandle { START, END }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PdfReadScreen(
    repo: NotePadRepository,
    notebookId: String,
    startPageId: String,
    onBack: () -> Unit,
    onWrite: (String) -> Unit,
    onOpenSideBySide: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val notebook = notebooks.firstOrNull { it.id == notebookId }
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    val marks by repo.pdfMarks(notebookId).collectAsState(initial = emptyList())
    val initialIndex = pages.indexOfFirst { it.id == startPageId }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("notepad", Context.MODE_PRIVATE) }
    var fitPage by remember(notebookId) {
        mutableStateOf(preferences.getBoolean("pdf_fit_page_$notebookId", false))
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var chromeVisible by remember { mutableStateOf(true) }
    var showMenu by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(initialIndex.toFloat()) }
    var scrubbing by remember { mutableStateOf(false) }
    var backPage by remember { mutableStateOf<Int?>(null) }
    var settledPages by remember { mutableStateOf(emptySet<Int>()) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var currentHit by remember { mutableStateOf<PdfTextSearchHit?>(null) }
    var searchResults by remember { mutableStateOf(emptyList<PdfTextSearchHit>()) }
    var searchHitIndex by remember { mutableIntStateOf(-1) }
    var selection by remember { mutableStateOf<PdfReadSelection?>(null) }
    var draggingSelectionHandle by remember { mutableStateOf(false) }
    val selectionState = rememberUpdatedState(selection)
    var searchQuery by remember { mutableStateOf("") }
    var selectedMarkColor by remember { mutableIntStateOf(0x66FFF176) }
    var noteDialog by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val currentIndex by remember(listState, pages) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.maxByOrNull {
                minOf(it.offset + it.size, info.viewportEndOffset) - maxOf(it.offset, info.viewportStartOffset)
            }?.index ?: initialIndex
        }
    }
    LaunchedEffect(pages, startPageId) {
        if (pages.isNotEmpty()) listState.scrollToItem(initialIndex.coerceIn(pages.indices))
    }
    LaunchedEffect(currentIndex, pages) {
        if (pages.isEmpty() || currentIndex !in pages.indices) return@LaunchedEffect
        delay(350)
        repo.setReadingPosition(notebookId, currentIndex)
    }
    LaunchedEffect(currentIndex) {
        if (!scrubbing) scrubValue = currentIndex.toFloat()
        delay(2200)
        if (!scrubbing) chromeVisible = false
    }
    LaunchedEffect(backPage) {
        if (backPage != null) {
            delay(6000)
            backPage = null
        }
    }
    fun showSearchHit(index: Int) {
        if (index !in searchResults.indices) return
        val hit = searchResults[index]
        searchHitIndex = index
        currentHit = hit
        val target = pages.indexOfFirst { it.id == hit.pageId }.coerceAtLeast(0)
        if (kotlin.math.abs(target - currentIndex) > 3) backPage = currentIndex
        scope.launch { listState.animateScrollToItem(target) }
    }
    fun acceptSearchResults(hits: List<PdfTextSearchHit>) {
        searchResults = hits
        if (hits.isEmpty()) {
            currentHit = null
            searchHitIndex = -1
        } else if (searchHitIndex < 0) {
            val hit = hits.first()
            searchHitIndex = 0
            currentHit = hit
            val target = pages.indexOfFirst { it.id == hit.pageId }.coerceAtLeast(0)
            if (kotlin.math.abs(target - currentIndex) > 3) backPage = currentIndex
            scope.launch { listState.animateScrollToItem(target) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val tabletWidth = maxWidth >= 840.dp
        val dockedSidebar = maxWidth >= 840.dp && sidebarOpen
        val sidebarWidth = if (dockedSidebar) 300.dp else 0.dp
        val pageWidth = maxWidth - sidebarWidth - 32.dp
        val density = LocalDensity.current
        val pagePaper = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF202024) else Color.White
        val bookmarkColor = MaterialTheme.colorScheme.primary
        LaunchedEffect(listState, pages, pageWidth, maxHeight, fitPage, zoom, pagePaper, bookmarkColor) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (scrolling) {
                        settledPages = emptySet()
                    } else {
                        delay(150)
                        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }.filter { it in pages.indices }.toSet()
                        if (visible.isEmpty()) return@collectLatest
                        val layout = listState.layoutInfo
                        val current = layout.visibleItemsInfo.maxByOrNull {
                            minOf(it.offset + it.size, layout.viewportEndOffset) -
                                maxOf(it.offset, layout.viewportStartOffset)
                        }?.index ?: return@collectLatest
                        val prefetch = (current - 2..current + 2).filter { it in pages.indices }
                        prefetch.forEach { index ->
                            val page = pages[index]
                            val displayWidth = if (fitPage) minOf(pageWidth, maxHeight * page.displayWidthPt / page.displayHeightPt) else pageWidth
                            val widthPx = with(density) { (displayWidth * 0.25f).roundToPx() }
                            precomposeReadPage(context, repo, page, widthPx, pagePaper, bookmarkColor)
                        }
                        visible.forEach { index ->
                            val page = pages[index]
                            val displayWidth = if (fitPage) minOf(pageWidth, maxHeight * page.displayWidthPt / page.displayHeightPt) else pageWidth
                            val widthPx = with(density) { (displayWidth * zoom).roundToPx() }
                            precomposeReadPage(context, repo, page, widthPx, pagePaper, bookmarkColor)
                        }
                        settledPages = visible
                    }
                }
        }
        val pageTransform = if (zoom != 1f || panX != 0f || panY != 0f) {
            Modifier.graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panX
                translationY = panY
            }
        } else {
            Modifier
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = sidebarWidth).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.size >= 2) {
                            val first = pointers[0]
                            val second = pointers[1]
                            val previousCenter = (first.previousPosition + second.previousPosition) * 0.5f
                            val currentCenter = (first.position + second.position) * 0.5f
                            val previousDistance = (first.previousPosition - second.previousPosition).getDistance().coerceAtLeast(1f)
                            val currentDistance = (first.position - second.position).getDistance()
                            zoom = (zoom * currentDistance / previousDistance).coerceIn(1f, 4f)
                            panX += currentCenter.x - previousCenter.x
                            panY += currentCenter.y - previousCenter.y
                            pointers.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }.then(pageTransform),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                pages,
                key = { _, page -> page.id },
                contentType = { _, _ -> "pdf-page" },
            ) { index, page ->
                var pageCoordinates by remember(page.id) { mutableStateOf<LayoutCoordinates?>(null) }
                Box(
                    Modifier.width(
                        if (fitPage) minOf(pageWidth, maxHeight * page.displayWidthPt / page.displayHeightPt) else pageWidth,
                    ).aspectRatio(page.displayWidthPt / page.displayHeightPt)
                        .clip(RoundedCornerShape(4.dp))
                        .onGloballyPositioned { pageCoordinates = it }
                        .pointerInput(page.id, page.rotation, marks, notebook?.language) {
                            detectTapGestures(
                                onLongPress = { point ->
                                    scope.launch {
                                        val assetId = page.assetId ?: return@launch
                                        val asset = repo.asset(assetId) ?: return@launch
                                        val coordinates = pageCoordinates?.takeIf { it.isAttached } ?: return@launch
                                        val textPoint = pagePointForText(point, coordinates, page)
                                        val pdfIndex = page.pdfPageIndex ?: index
                                        val found = withContext(PdfiumDispatcher.dispatcher) {
                                            PdfPageSource(context, File(asset.localPath), PdfPasswordVault(context).read(asset.id)).use { source ->
                                                val text = PdfTextCache.getOrLoad(context, asset.id, pdfIndex) { source.textGeometry(pdfIndex) }
                                                val charIndex = PdfWordSnap.nearestCharacterIndex(
                                                    text,
                                                    textPoint.x,
                                                    textPoint.y,
                                                    page.displayWidthPt * 4f / coordinates.size.width,
                                                    page.displayHeightPt * 12f / coordinates.size.height,
                                                ) ?: return@use null
                                                val range = PdfWordSnap.wordRange(
                                                    text.text, charIndex, Locale.forLanguageTag(notebook?.language ?: "und"),
                                                ) ?: return@use null
                                                PdfReadSelection(
                                                    page.id,
                                                    range.start,
                                                    range.end,
                                                    text.text.substring(range.start, range.end),
                                                    text.rectsForRange(range.start, range.end),
                                                )
                                            }
                                        }
                                        if (found != null) {
                                            selection = found
                                            chromeVisible = true
                                        }
                                    }
                                },
                                onTap = { point ->
                                    chromeVisible = !chromeVisible
                                    val coordinates = pageCoordinates?.takeIf { it.isAttached }
                                    val textPoint = if (coordinates != null) {
                                        pagePointForText(point, coordinates, page)
                                    } else null
                                    val mark = textPoint?.let { pointOnPage ->
                                        marks.firstOrNull { item ->
                                            item.pageId == page.id && PdfWordSnap.topLeftRects(item.quads, page.heightPt).any {
                                                pointOnPage.x in it.left..it.right && pointOnPage.y in it.top..it.bottom
                                            }
                                        }
                                    }
                                    if (mark != null) {
                                        selection = PdfReadSelection(
                                            page.id, mark.charStart ?: 0, mark.charEnd ?: 0, mark.text,
                                            PdfWordSnap.topLeftRects(mark.quads, page.heightPt), mark,
                                        )
                                        chromeVisible = true
                                    }
                                },
                            )
                        }
                        .pointerInteropFilter { event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP &&
                                event.pointerCount > 0 &&
                                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            ) {
                                onWrite(page.id)
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    val renderScale = if (index in settledPages) zoom else 0.25f
                    PagePreview(
                        repo = repo,
                        page = page,
                        paperColor = pagePaper,
                        renderEnabled = true,
                        renderScale = renderScale,
                        precomposedOnly = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    currentHit?.takeIf { it.pageId == page.id }?.let { hit ->
                        Canvas(Modifier.fillMaxSize()) {
                            hit.rects.forEach { rect ->
                                val shown = pageRectToDisplay(page.widthPt, page.heightPt, page.rotation, rect)
                                val sx = size.width / page.displayWidthPt
                                val sy = size.height / page.displayHeightPt
                                drawRect(
                                    Color(0xFFFFD54F).copy(alpha = 0.35f),
                                    Offset(shown.left * sx, shown.top * sy),
                                    Size((shown.right - shown.left) * sx, (shown.bottom - shown.top) * sy),
                                )
                            }
                        }
                    }
                    selection?.takeIf { it.pageId == page.id }?.let { selected ->
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(page.id, page.rotation) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val current = selectionState.value?.takeIf { it.pageId == page.id }
                                        ?: return@awaitEachGesture
                                    val anchors = selectionHandleAnchors(
                                        current,
                                        page,
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                    )
                                    val hitRadius = 28.dp.toPx()
                                    val startDistance = (down.position - anchors.first).getDistance()
                                    val endDistance = (down.position - anchors.second).getDistance()
                                    val activeHandle = when {
                                        startDistance <= hitRadius && startDistance <= endDistance -> SelectionHandle.START
                                        endDistance <= hitRadius -> SelectionHandle.END
                                        else -> return@awaitEachGesture
                                    }
                                    down.consume()
                                    draggingSelectionHandle = true
                                    val pointerId = down.id
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (change.position != change.previousPosition) {
                                            change.consume()
                                            scope.launch {
                                                val currentSelection = selectionState.value ?: return@launch
                                                val coordinates = pageCoordinates?.takeIf { it.isAttached } ?: return@launch
                                                val assetId = page.assetId ?: return@launch
                                                val asset = repo.asset(assetId) ?: return@launch
                                                val pdfIndex = page.pdfPageIndex ?: index
                                                val text = withContext(PdfiumDispatcher.dispatcher) {
                                                    PdfPageSource(
                                                        context,
                                                        File(asset.localPath),
                                                        PdfPasswordVault(context).read(asset.id),
                                                    ).use { source ->
                                                        PdfTextCache.getOrLoad(context, asset.id, pdfIndex) {
                                                            source.textGeometry(pdfIndex)
                                                        }
                                                    }
                                                }
                                                val textPoint = pagePointForText(change.position, coordinates, page)
                                                val characterIndex = PdfWordSnap.nearestCharacterIndex(
                                                    text,
                                                    textPoint.x,
                                                    textPoint.y,
                                                    page.displayWidthPt * 4f / coordinates.size.width,
                                                    page.displayHeightPt * 12f / coordinates.size.height,
                                                ) ?: return@launch
                                                val target = PdfWordSnap.wordRange(
                                                    text.text,
                                                    characterIndex,
                                                    Locale.forLanguageTag(notebook?.language ?: "und"),
                                                ) ?: return@launch
                                                val (start, end) = when (activeHandle) {
                                                    SelectionHandle.START -> if (target.start < currentSelection.end) {
                                                        target.start to currentSelection.end
                                                    } else {
                                                        currentSelection.end to target.end
                                                    }
                                                    SelectionHandle.END -> if (target.end > currentSelection.start) {
                                                        currentSelection.start to target.end
                                                    } else {
                                                        target.start to currentSelection.start
                                                    }
                                                }
                                                if (start < end) {
                                                    selection = PdfReadSelection(
                                                        page.id,
                                                        start,
                                                        end,
                                                        text.text.substring(start, end),
                                                        text.rectsForRange(start, end),
                                                    )
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    draggingSelectionHandle = false
                                }
                            },
                        ) {
                            val sx = size.width / page.displayWidthPt
                            val sy = size.height / page.displayHeightPt
                            selected.rects.forEach { rect ->
                                val shown = pageRectToDisplay(page.widthPt, page.heightPt, page.rotation, rect)
                                drawRect(
                                    Color(0x664A90E2),
                                    Offset(shown.left * sx, shown.top * sy),
                                    Size((shown.right - shown.left) * sx, (shown.bottom - shown.top) * sy),
                                )
                            }
                            val (startHandle, endHandle) = selectionHandleAnchors(
                                selected,
                                page,
                                size.width,
                                size.height,
                            )
                            val radius = 12.dp.toPx()
                            listOf(startHandle, endHandle).forEach { anchor ->
                                drawPath(selectionHandlePath(anchor, radius), Color(0xFF1565C0))
                                drawCircle(Color.White, radius * 0.28f, Offset(anchor.x, anchor.y - radius))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(start = sidebarWidth),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        notebook?.title.orEmpty(),
                        Modifier.weight(1f),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${currentIndex + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (searchResults.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { showSearchHit(searchHitIndex - 1) }) {
                            Text("‹", color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            "${(searchHitIndex + 1).coerceAtLeast(0)} / ${searchResults.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        androidx.compose.material3.TextButton(onClick = { showSearchHit(searchHitIndex + 1) }) {
                            Text("›", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { sidebarOpen = !sidebarOpen; chromeVisible = true }) {
                        Text("Contents", color = MaterialTheme.colorScheme.onSurface)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        selection?.text?.let { searchQuery = it }
                        sidebarOpen = true
                        chromeVisible = true
                    }) { Text("Marks", color = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = { onWrite(pages.getOrNull(currentIndex)?.id ?: startPageId) }) {
                        Icon(Icons.Outlined.Edit, "Write", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, "Reader options", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (tabletWidth && onOpenSideBySide != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Open side by side") },
                                    onClick = {
                                        showMenu = false
                                        pages.getOrNull(currentIndex)?.let { onOpenSideBySide(notebookId, it.id) }
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (fitPage) "Fit width" else "Fit page") },
                                onClick = {
                                    fitPage = !fitPage
                                    preferences.edit().putBoolean("pdf_fit_page_$notebookId", fitPage).apply()
                                    showMenu = false
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Rotate page 90°") },
                                onClick = {
                                    pages.getOrNull(currentIndex)?.let { page ->
                                        scope.launch { repo.setPageRotation(page.id, page.rotation + 90) }
                                    }
                                    showMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = sidebarWidth)
                .navigationBarsPadding().systemGesturesPadding(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    if (scrubbing) Text("${scrubValue.toInt() + 1} / ${pages.size}", Modifier.align(Alignment.CenterHorizontally))
                    PageScrubber(
                        value = scrubValue,
                        maxValue = (pages.size - 1).coerceAtLeast(0).toFloat(),
                        onValueChange = { scrubValue = it; scrubbing = true; chromeVisible = true },
                        onValueChangeFinished = {
                            val old = currentIndex
                            val next = scrubValue.toInt().coerceIn(pages.indices)
                            scope.launch { listState.animateScrollToItem(next) }
                            if (kotlin.math.abs(next - old) > 3) backPage = old
                            scrubbing = false
                        },
                    )
                }
            }
        }
        backPage?.let { previous ->
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                TextButton(onClick = { scope.launch { listState.animateScrollToItem(previous) }; backPage = null }) {
                    Text("Back to p. ${previous + 1}", color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
        selection?.takeUnless { draggingSelectionHandle }?.let { selected ->
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (chromeVisible) 118.dp else 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(0x66FFF176, 0x6681C784, 0x6680DEEA, 0x66FF8A65, 0x66B39DDB).forEach { argb ->
                        Box(
                            Modifier.width(20.dp).height(20.dp).clip(RoundedCornerShape(50))
                                .background(Color(argb)).clickable { selectedMarkColor = argb },
                        )
                    }
                    TextButton(onClick = {
                        saveSelection(repo, scope, pages, notebookId, selected, PdfMarkKind.HIGHLIGHT, selectedMarkColor)
                        selection = null
                    }) { Text("Highlight") }
                    TextButton(onClick = {
                        saveSelection(repo, scope, pages, notebookId, selected, PdfMarkKind.UNDERLINE, selectedMarkColor)
                        selection = null
                    }) { Text("Underline") }
                    TextButton(onClick = {
                        saveSelection(repo, scope, pages, notebookId, selected, PdfMarkKind.STRIKE, selectedMarkColor)
                        selection = null
                    }) { Text("Strike") }
                    TextButton(onClick = {
                        noteDraft = selected.mark?.note.orEmpty()
                        noteDialog = true
                    }) { Text("Note") }
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                java.text.Normalizer.normalize(selected.text, java.text.Normalizer.Form.NFC),
                            ),
                        )
                        selection = null
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        searchQuery = selected.text
                        sidebarOpen = true
                        selection = null
                    }) { Text("Search") }
                    selected.mark?.let { mark ->
                        TextButton(onClick = {
                            scope.launch {
                                repo.deletePdfMark(mark.id)
                                val result = snackbarHost.showSnackbar("Mark deleted", "Undo")
                                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                    repo.savePdfMark(mark.copy(updatedAt = System.currentTimeMillis(), deleted = false))
                                }
                            }
                            selection = null
                        }) { Text("Delete") }
                    }
                    TextButton(onClick = { selection = null }) { Text("×") }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        )
        if (noteDialog) {
            AlertDialog(
                onDismissRequest = { noteDialog = false },
                title = { Text("Mark note") },
                text = {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        selection?.let {
                            saveSelection(
                                repo, scope, pages, notebookId, it, it.mark?.kind ?: PdfMarkKind.HIGHLIGHT,
                                selectedMarkColor, noteDraft,
                            )
                        }
                        noteDialog = false
                        selection = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel") } },
            )
        }
        if (dockedSidebar) {
            Surface(
                Modifier.align(Alignment.CenterStart).width(sidebarWidth).fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                PageSidebar(
                    repo = repo,
                    pages = pages,
                    currentPageId = pages.getOrNull(currentIndex)?.id ?: startPageId,
                    onPageSelected = { pageId ->
                        val target = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
                        if (kotlin.math.abs(target - currentIndex) > 3) backPage = currentIndex
                        scope.launch { listState.animateScrollToItem(target) }
                        chromeVisible = true
                    },
                    onClose = { sidebarOpen = false },
                    onSearchHit = { hit -> showSearchHit(searchResults.indexOf(hit).coerceAtLeast(0)) },
                    onSearchResults = ::acceptSearchResults,
                    initialQuery = searchQuery,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (sidebarOpen) {
            ModalBottomSheet(onDismissRequest = { sidebarOpen = false }) {
                PageSidebar(
                    repo = repo,
                    pages = pages,
                    currentPageId = pages.getOrNull(currentIndex)?.id ?: startPageId,
                    onPageSelected = { pageId ->
                        val target = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
                        if (kotlin.math.abs(target - currentIndex) > 3) backPage = currentIndex
                        scope.launch { listState.animateScrollToItem(target) }
                        chromeVisible = true
                    },
                    onClose = { sidebarOpen = false },
                    onSearchHit = { hit -> showSearchHit(searchResults.indexOf(hit).coerceAtLeast(0)) },
                    onSearchResults = ::acceptSearchResults,
                    initialQuery = searchQuery,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                )
            }
        }
    }
}

@Composable
private fun PageScrubber(
    value: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val surface = MaterialTheme.colorScheme.surface
    val view = LocalView.current
    Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .systemGestureExclusion(view)
            .pointerInput(maxValue) {
                detectTapGestures { point ->
                    val start = 8.dp.toPx()
                    val end = size.width - start
                    onValueChange((((point.x - start) / (end - start)).coerceIn(0f, 1f) * maxValue))
                    onValueChangeFinished()
                }
            }
            .pointerInput(maxValue) {
                detectDragGestures(
                    onDragStart = {},
                    onDragEnd = onValueChangeFinished,
                    onDragCancel = onValueChangeFinished,
                ) { change, _ ->
                    val start = 8.dp.toPx()
                    val end = size.width - start
                    onValueChange((((change.position.x - start) / (end - start)).coerceIn(0f, 1f) * maxValue))
                }
            },
    ) {
        val start = 8.dp.toPx()
        val end = size.width - start
        val y = size.height / 2f
        val x = start + (end - start) * (value.coerceIn(0f, maxValue.coerceAtLeast(1f)) / maxValue.coerceAtLeast(1f))
        drawLine(track, Offset(start, y), Offset(end, y), 2.dp.toPx())
        drawLine(primary, Offset(start, y), Offset(x, y), 2.dp.toPx())
        drawCircle(surface, 6.dp.toPx(), Offset(x, y))
        drawCircle(primary, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

private fun Modifier.systemGestureExclusion(view: View): Modifier =
    onGloballyPositioned { coordinates ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bounds = coordinates.boundsInRoot()
            view.systemGestureExclusionRects = listOf(
                AndroidRect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
            )
        }
    }

private fun pagePointForText(
    point: Offset,
    coordinates: LayoutCoordinates,
    page: Page,
): Offset {
    val pagePoint = PdfPageCoordinates.screenToPageTopLeft(
        screenX = point.x,
        screenY = point.y,
        pageLeft = 0f,
        pageTop = 0f,
        pageWidthPx = coordinates.size.width.toFloat(),
        pageHeightPx = coordinates.size.height.toFloat(),
        pageWidthPt = page.widthPt,
        pageHeightPt = page.heightPt,
        rotation = page.rotation,
    )
    return Offset(pagePoint.x, pagePoint.y)
}

private fun pageRectToDisplay(
    pageWidth: Float,
    pageHeight: Float,
    rotation: Int,
    rect: PdfTextRect,
): androidx.compose.ui.geometry.Rect = when ((rotation % 360 + 360) % 360) {
    90 -> androidx.compose.ui.geometry.Rect(pageHeight - rect.bottom, rect.left, pageHeight - rect.top, rect.right)
    180 -> androidx.compose.ui.geometry.Rect(pageWidth - rect.right, pageHeight - rect.bottom, pageWidth - rect.left, pageHeight - rect.top)
    270 -> androidx.compose.ui.geometry.Rect(rect.top, pageWidth - rect.right, rect.bottom, pageWidth - rect.left)
    else -> androidx.compose.ui.geometry.Rect(rect.left, rect.top, rect.right, rect.bottom)
}

private fun selectionHandleAnchors(
    selection: PdfReadSelection,
    page: Page,
    widthPx: Float,
    heightPx: Float,
): Pair<Offset, Offset> {
    val first = selection.rects.firstOrNull() ?: PdfTextRect(0f, 0f, 0f, 0f)
    val last = selection.rects.lastOrNull() ?: first
    val firstDisplay = pageRectToDisplay(page.widthPt, page.heightPt, page.rotation, first)
    val lastDisplay = pageRectToDisplay(page.widthPt, page.heightPt, page.rotation, last)
    val scaleX = widthPx / page.displayWidthPt
    val scaleY = heightPx / page.displayHeightPt
    val rtl = isRtlText(selection.text)
    val startX = if (rtl) firstDisplay.right else firstDisplay.left
    val endX = if (rtl) lastDisplay.left else lastDisplay.right
    return Offset(startX * scaleX, firstDisplay.bottom * scaleY) to
        Offset(endX * scaleX, lastDisplay.bottom * scaleY)
}

private fun selectionHandlePath(anchor: Offset, radius: Float) = Path().apply {
    moveTo(anchor.x, anchor.y)
    cubicTo(
        anchor.x - radius * 0.25f,
        anchor.y - radius * 0.25f,
        anchor.x - radius,
        anchor.y - radius * 0.45f,
        anchor.x - radius,
        anchor.y - radius,
    )
    cubicTo(anchor.x - radius, anchor.y - radius * 1.65f, anchor.x + radius, anchor.y - radius * 1.65f, anchor.x + radius, anchor.y - radius)
    cubicTo(
        anchor.x + radius,
        anchor.y - radius * 0.45f,
        anchor.x + radius * 0.25f,
        anchor.y - radius * 0.25f,
        anchor.x,
        anchor.y,
    )
    close()
}

private fun isRtlText(text: String): Boolean {
    for (character in text) {
        when (Character.getDirectionality(character.code)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

private fun saveSelection(
    repo: NotePadRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    pages: List<Page>,
    notebookId: String,
    selection: PdfReadSelection,
    kind: PdfMarkKind,
    colorArgb: Int,
    note: String? = selection.mark?.note,
) {
    val page = pages.firstOrNull { it.id == selection.pageId } ?: return
    val now = System.currentTimeMillis()
    val mark = selection.mark?.copy(
        kind = kind,
        colorArgb = colorArgb,
        quads = PdfWordSnap.quadPoints(selection.rects, page.heightPt),
        text = selection.text,
        note = note?.takeIf(String::isNotBlank),
        updatedAt = now,
        deleted = false,
    ) ?: PdfMark(
        id = UUID.randomUUID().toString(),
        notebookId = notebookId,
        pageId = page.id,
        kind = kind,
        colorArgb = colorArgb,
        quads = PdfWordSnap.quadPoints(selection.rects, page.heightPt),
        charStart = selection.start,
        charEnd = selection.end,
        text = selection.text,
        note = note?.takeIf(String::isNotBlank),
        createdAt = now,
        updatedAt = now,
    )
    scope.launch { repo.savePdfMark(mark) }
}
