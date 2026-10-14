package com.hashim.notepad.ui

import android.content.Context
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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.hashim.notepad.pdf.PdfPasswordVault
import com.hashim.notepad.pdf.PdfTextCache
import com.hashim.notepad.pdf.PdfTextRect
import com.hashim.notepad.pdf.PdfTextSearchHit
import com.hashim.notepad.pdf.PdfWordSnap
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PdfReadScreen(
    repo: NotePadRepository,
    notebookId: String,
    startPageId: String,
    onBack: () -> Unit,
    onWrite: (String) -> Unit,
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
    LaunchedEffect(listState, zoom, pages) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
            .distinctUntilChanged()
            .debounce(150)
            .collect { settledPages = it }
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
        val dockedSidebar = maxWidth >= 840.dp && sidebarOpen
        val sidebarWidth = if (dockedSidebar) 300.dp else 0.dp
        val pageWidth = maxWidth - sidebarWidth - 32.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = sidebarWidth).pointerInput(Unit) {
                detectTransformGestures { _, pan, scale, _ ->
                    zoom = (zoom * scale).coerceIn(1f, 4f)
                    panX += pan.x
                    panY += pan.y
                }
            }.graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panX
                translationY = panY
            },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                pages,
                key = { _, page -> page.id },
                contentType = { _, _ -> "pdf-page" },
            ) { index, page ->
                Box(
                    Modifier.width(
                        if (fitPage) minOf(pageWidth, maxHeight * page.displayWidthPt / page.displayHeightPt) else pageWidth,
                    ).aspectRatio(page.displayWidthPt / page.displayHeightPt)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { chromeVisible = !chromeVisible }
                        .pointerInput(page.id, page.rotation, marks, notebook?.language) {
                            detectTapGestures(
                                onLongPress = { point ->
                                    scope.launch {
                                        val assetId = page.assetId ?: return@launch
                                        val asset = repo.asset(assetId) ?: return@launch
                                        val viewX = point.x / size.width * page.displayWidthPt
                                        val viewY = point.y / size.height * page.displayHeightPt
                                        val pagePoint = displayToPagePoint(page.widthPt, page.heightPt, page.rotation, viewX, viewY)
                                        val pdfIndex = page.pdfPageIndex ?: index
                                        val found = withContext(PdfiumDispatcher.dispatcher) {
                                            PdfPageSource(context, File(asset.localPath), PdfPasswordVault(context).read(asset.id)).use { source ->
                                                val text = PdfTextCache.getOrLoad(context, asset.id, pdfIndex) { source.text(pdfIndex) }
                                                val charIndex = PdfWordSnap.nearestCharacterIndex(
                                                    source.textCharBoxes(pdfIndex),
                                                    pagePoint.x,
                                                    pagePoint.y,
                                                    page.widthPt * 4f / size.width,
                                                    page.heightPt * 12f / size.height,
                                                ) ?: return@use null
                                                val range = PdfWordSnap.wordRange(
                                                    text, charIndex, Locale.forLanguageTag(notebook?.language ?: "und"),
                                                ) ?: return@use null
                                                PdfReadSelection(
                                                    page.id,
                                                    range.start,
                                                    range.end,
                                                    text.substring(range.start, range.end),
                                                    source.textLineRects(pdfIndex, range.start, range.end),
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
                                    val viewX = point.x / size.width * page.displayWidthPt
                                    val viewY = point.y / size.height * page.displayHeightPt
                                    val pagePoint = displayToPagePoint(page.widthPt, page.heightPt, page.rotation, viewX, viewY)
                                    val mark = marks.firstOrNull { item ->
                                        item.pageId == page.id && PdfWordSnap.topLeftRects(item.quads, page.heightPt).any {
                                            pagePoint.x in it.left..it.right && pagePoint.y in it.top..it.bottom
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
                    PagePreview(
                        repo = repo,
                        page = page,
                        paperColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF202024) else Color.White,
                        renderEnabled = true,
                        renderScale = if (index in settledPages) zoom else 0.4f,
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
                        Canvas(Modifier.fillMaxSize()) {
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
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f), shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Text(notebook?.title.orEmpty(), Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    Text("${currentIndex + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium)
                    if (searchResults.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { showSearchHit(searchHitIndex - 1) }) { Text("‹") }
                        Text("${(searchHitIndex + 1).coerceAtLeast(0)} / ${searchResults.size}", style = MaterialTheme.typography.labelMedium)
                        androidx.compose.material3.TextButton(onClick = { showSearchHit(searchHitIndex + 1) }) { Text("›") }
                    }
                    androidx.compose.material3.TextButton(onClick = { sidebarOpen = !sidebarOpen; chromeVisible = true }) {
                        Text("Contents")
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        selection?.text?.let { searchQuery = it }
                        sidebarOpen = true
                        chromeVisible = true
                    }) { Text("Marks") }
                    IconButton(onClick = { onWrite(pages.getOrNull(currentIndex)?.id ?: startPageId) }) {
                        Icon(Icons.Outlined.Edit, "Write")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "Reader options") }
                        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = sidebarWidth),
        ) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 4.dp) {
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
        selection?.let { selected ->
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
                    TextButton(onClick = { clipboard.setText(AnnotatedString(selected.text)); selection = null }) { Text("Copy") }
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
    Canvas(
        Modifier.fillMaxWidth().height(32.dp)
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

private fun displayToPagePoint(
    pageWidth: Float,
    pageHeight: Float,
    rotation: Int,
    x: Float,
    y: Float,
): Offset = when ((rotation % 360 + 360) % 360) {
    90 -> Offset(y, pageHeight - x)
    180 -> Offset(pageWidth - x, pageHeight - y)
    270 -> Offset(pageWidth - y, x)
    else -> Offset(x, y)
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
