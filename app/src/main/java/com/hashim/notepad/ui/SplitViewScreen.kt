package com.hashim.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Notebook
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.SplitPair
import com.hashim.notepad.recognition.InkRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun snapSplitDividerRatio(value: Float): Float =
    listOf(0.33f, 0.5f, 0.67f).minBy { abs(value - it) }

internal fun splitVisualOrder(leftNotebookId: String, rightNotebookId: String, swapped: Boolean): Pair<String, String> =
    if (swapped) rightNotebookId to leftNotebookId else leftNotebookId to rightNotebookId

@Composable
fun SplitViewScreen(
    repo: NotePadRepository,
    recognizer: InkRecognizer,
    persistScope: CoroutineScope,
    openedNotebookId: String,
    openedPageId: String,
    onClose: (notebookId: String, pageId: String) -> Unit,
    initialSearchQuery: String? = null,
    onInitialSearchApplied: (String) -> Unit = {},
) {
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    var pair by remember(openedNotebookId) { mutableStateOf<SplitPair?>(null) }
    LaunchedEffect(openedNotebookId) { pair = repo.splitPair(openedNotebookId) }

    val savedPair = pair
    if (savedPair == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var swapped by rememberSaveable(savedPair.id) { mutableStateOf(savedPair.swapped) }
    val (visualLeftId, visualRightId) =
        splitVisualOrder(savedPair.leftNotebookId, savedPair.rightNotebookId, swapped)
    val leftNotebook = notebooks.firstOrNull { it.id == visualLeftId }
    val rightNotebook = notebooks.firstOrNull { it.id == visualRightId }
    if (leftNotebook == null || rightNotebook == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val leftPages by repo.pages(visualLeftId).collectAsState(initial = emptyList())
    val rightPages by repo.pages(visualRightId).collectAsState(initial = emptyList())
    var leftPageId by rememberSaveable(savedPair.id, visualLeftId) {
        mutableStateOf(openedPageId.takeIf { openedNotebookId == visualLeftId })
    }
    var rightPageId by rememberSaveable(savedPair.id, visualRightId) {
        mutableStateOf(openedPageId.takeIf { openedNotebookId == visualRightId })
    }
    LaunchedEffect(leftPages, leftNotebook, openedNotebookId, openedPageId, initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank() &&
            openedNotebookId == visualLeftId &&
            leftPages.any { it.id == openedPageId }
        ) {
            leftPageId = openedPageId
        } else if (leftPageId == null || leftPages.none { it.id == leftPageId }) {
            leftPageId = if (openedNotebookId == visualLeftId) openedPageId else
                leftPages.getOrNull(leftNotebook.lastPageIndex.coerceIn(0, (leftPages.size - 1).coerceAtLeast(0)))?.id
                    ?: leftPages.firstOrNull()?.id
        }
    }
    LaunchedEffect(rightPages, rightNotebook, openedNotebookId, openedPageId, initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank() &&
            openedNotebookId == visualRightId &&
            rightPages.any { it.id == openedPageId }
        ) {
            rightPageId = openedPageId
        } else if (rightPageId == null || rightPages.none { it.id == rightPageId }) {
            rightPageId = if (openedNotebookId == visualRightId) openedPageId else
                rightPages.getOrNull(rightNotebook.lastPageIndex.coerceIn(0, (rightPages.size - 1).coerceAtLeast(0)))?.id
                    ?: rightPages.firstOrNull()?.id
        }
    }

    var ratio by remember(savedPair.id) { mutableFloatStateOf(savedPair.dividerRatio.coerceIn(0.33f, 0.67f)) }
    var compactPane by rememberSaveable(savedPair.id, openedNotebookId) {
        mutableStateOf(openedNotebookId == visualRightId)
    }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onSizeChanged { containerSize = it },
    ) {
        val compact = maxWidth < 840.dp
        if (compact) {
            val selectedNotebook = if (compactPane) rightNotebook else leftNotebook
            val selectedPage = if (compactPane) rightPageId else leftPageId
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            leftNotebook.title,
                            Modifier.weight(1f).clickable { compactPane = false }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!compactPane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text("·", style = MaterialTheme.typography.labelLarge)
                        Text(
                            rightNotebook.title,
                            Modifier.weight(1f).clickable { compactPane = true }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (compactPane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                SplitDocumentPane(
                    repo = repo,
                    notebookId = selectedNotebook.id,
                    initialPageId = selectedPage,
                    recognizer = recognizer,
                    initialSearchQuery = initialSearchQuery.takeIf { selectedNotebook.id == openedNotebookId },
                    searchTargetPageId = openedPageId.takeIf {
                        !initialSearchQuery.isNullOrBlank() && selectedNotebook.id == openedNotebookId
                    },
                    onInitialSearchApplied = onInitialSearchApplied,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onPageChange = { pageId ->
                        if (compactPane) rightPageId = pageId else leftPageId = pageId
                    },
                    onClose = { onClose(selectedNotebook.id, selectedPage.orEmpty()) },
                )
            }
        } else {
            Row(Modifier.fillMaxSize().statusBarsPadding().onSizeChanged { containerSize = it }) {
                SplitDocumentPane(
                    repo = repo,
                    notebookId = leftNotebook.id,
                    initialPageId = leftPageId,
                    recognizer = recognizer,
                    initialSearchQuery = initialSearchQuery.takeIf { leftNotebook.id == openedNotebookId },
                    searchTargetPageId = openedPageId.takeIf {
                        !initialSearchQuery.isNullOrBlank() && leftNotebook.id == openedNotebookId
                    },
                    onInitialSearchApplied = onInitialSearchApplied,
                    modifier = Modifier.weight(ratio),
                    onPageChange = { leftPageId = it },
                    onClose = { onClose(leftNotebook.id, leftPageId.orEmpty()) },
                )
                SplitDivider(
                    ratio = ratio,
                    widthPx = containerSize.width,
                    swapped = swapped,
                    onRatioChange = { ratio = it },
                    onSwap = {
                        swapped = !swapped
                        persistScope.launch { repo.updateSplitPair(savedPair.id, ratio, swapped) }
                    },
                    onClose = { onClose(leftNotebook.id, leftPageId.orEmpty()) },
                    onDragFinished = { finalRatio ->
                        persistScope.launch { repo.updateSplitPair(savedPair.id, finalRatio, swapped) }
                    },
                )
                SplitDocumentPane(
                    repo = repo,
                    notebookId = rightNotebook.id,
                    initialPageId = rightPageId,
                    recognizer = recognizer,
                    initialSearchQuery = initialSearchQuery.takeIf { rightNotebook.id == openedNotebookId },
                    searchTargetPageId = openedPageId.takeIf {
                        !initialSearchQuery.isNullOrBlank() && rightNotebook.id == openedNotebookId
                    },
                    onInitialSearchApplied = onInitialSearchApplied,
                    modifier = Modifier.weight(1f - ratio),
                    onPageChange = { rightPageId = it },
                    onClose = { onClose(rightNotebook.id, rightPageId.orEmpty()) },
                )
            }
        }
    }
}

@Composable
private fun SplitDivider(
    ratio: Float,
    widthPx: Int,
    swapped: Boolean,
    onRatioChange: (Float) -> Unit,
    onSwap: () -> Unit,
    onClose: () -> Unit,
    onDragFinished: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dividerWidthPx = with(density) { 40.dp.toPx() }
    val draggableState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        onRatioChange((ratio + delta / (widthPx - dividerWidthPx).coerceAtLeast(1f)).coerceIn(0.25f, 0.75f))
    }
    Column(
        Modifier.width(40.dp).fillMaxHeight().zIndex(1f)
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val target = snapSplitDividerRatio(ratio)
                    onDragFinished(target)
                    scope.launch {
                        val animation = Animatable(ratio)
                        animation.animateTo(target, spring(dampingRatio = 0.72f, stiffness = 420f)) {
                            onRatioChange(value)
                        }
                    }
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onSwap) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = if (swapped) "Restore pane order" else "Swap panes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.width(4.dp).height(56.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape),
        )
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close side-by-side view",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SplitDocumentPane(
    repo: NotePadRepository,
    notebookId: String,
    initialPageId: String?,
    recognizer: InkRecognizer,
    modifier: Modifier = Modifier,
    initialSearchQuery: String? = null,
    searchTargetPageId: String? = null,
    onInitialSearchApplied: (String) -> Unit = {},
    onPageChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    var pageId by rememberSaveable(notebookId) { mutableStateOf(initialPageId) }
    var editingPdfPageId by rememberSaveable(notebookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(initialPageId, pages, initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank() &&
            initialPageId != null &&
            pages.any { it.id == initialPageId }
        ) {
            pageId = initialPageId
        } else if (pageId == null || pages.none { it.id == pageId }) {
            pageId = initialPageId ?: pages.firstOrNull()?.id
        }
    }
    val page = pages.firstOrNull { it.id == pageId }
    if (page == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val writePdf = page.kind == PageKind.PDF && editingPdfPageId == page.id
    Box(modifier.fillMaxSize().clipToBounds()) {
        if (page.kind == PageKind.PDF && !writePdf) {
            PdfReadScreen(
                repo = repo,
                notebookId = notebookId,
                startPageId = page.id,
                onBack = onClose,
                initialSearchQuery = initialSearchQuery.takeIf { page.id == searchTargetPageId },
                onInitialSearchApplied = onInitialSearchApplied,
                onWrite = {
                    pageId = it
                    onPageChange(it)
                    editingPdfPageId = it
                },
            )
        } else {
            EditorScreen(
                repo = repo,
                notebookId = notebookId,
                pageId = page.id,
                recognizer = recognizer,
                onBack = {
                    if (writePdf) editingPdfPageId = null else onClose()
                },
                onNavigateToPage = {
                    pageId = it
                    onPageChange(it)
                },
            )
        }
    }
}
