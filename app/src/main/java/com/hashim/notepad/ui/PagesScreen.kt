package com.hashim.notepad.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.export.BundleCodec
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.recognition.InkRecognizer
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

private val PAGE_TEMPLATES = listOf(
    PageKind.BLANK to "Blank",
    PageKind.LINED to "Lined",
    PageKind.GRID to "Grid",
)

private data class PageTemplateRequest(val page: Page, val insertAfter: Boolean)

/** One notebook: its pages as live thumbnails. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PagesScreen(
    repo: NotePadRepository, notebookId: String, exporter: PdfExporter, recognizer: InkRecognizer, bundles: BundleCodec,
    onBack: () -> Unit, onOpenPage: (String) -> Unit, showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var busy by remember { mutableStateOf<String?>(null) }
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    var displayedPages by remember(notebookId) { mutableStateOf(emptyList<Page>()) }
    LaunchedEffect(pages) { displayedPages = pages }
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val notebook = notebooks.firstOrNull { it.id == notebookId }
    val title = notebook?.title ?: ""
    val paper = notebook?.let { paperOf(it) } ?: Color.White
    var showAdd by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var managePage by remember { mutableStateOf<Page?>(null) }
    var templateRequest by remember { mutableStateOf<PageTemplateRequest?>(null) }
    var deletePage by remember { mutableStateOf<Page?>(null) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIndex = from.index - 1
        val toIndex = to.index - 1
        if (fromIndex in displayedPages.indices && toIndex in displayedPages.indices) {
            displayedPages = displayedPages.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch { busy = label; runCatching { block() }.onFailure { Toast.makeText(context, "${label.trimEnd('…')} failed: ${it.message}", Toast.LENGTH_LONG).show() }; busy = null }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Default.Add, "Add page") }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            state = gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showBack) IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = titleFontFamily(title)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                busy ?: "${pages.size} page${if (pages.size == 1) "" else "s"} · ${InkRecognizer.LANGUAGES.firstOrNull { it.first == notebook?.language }?.second ?: ""}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreHoriz, "More") }
                    }
                    AnimatedVisibility(busy != null, enter = fadeIn(), exit = fadeOut()) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape)) }
                }
            }
            itemsIndexed(displayedPages, key = { _, p -> p.id }) { index, page ->
                ReorderableItem(reorderState, key = page.id) { isDragging ->
                    val dragHandle: @Composable () -> Unit = {
                        Box(
                            Modifier.size(32.dp).longPressDraggableHandle(
                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                onDragStopped = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    val targetIndex = displayedPages.indexOfFirst { it.id == page.id }
                                    if (targetIndex >= 0) scope.launch {
                                        runCatching { repo.movePage(page.id, targetIndex) }
                                            .onFailure { Toast.makeText(context, "Could not reorder page: ${it.message}", Toast.LENGTH_LONG).show() }
                                    }
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DragHandle,
                                "Reorder page",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                    PageTile(
                        repo = repo,
                        page = page,
                        number = index + 1,
                        paper = paper,
                        modifier = Modifier.animateItem().graphicsLayer {
                            val scale = if (isDragging) 1.025f else 1f
                            scaleX = scale
                            scaleY = scale
                        },
                        onClick = { onOpenPage(page.id) },
                        onLongClick = { managePage = page },
                        dragHandle = dragHandle.takeIf { displayedPages.size > 1 },
                    )
                }
            }
        }
    }

    if (showMore) ModalBottomSheet(onDismissRequest = { showMore = false }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        SheetTitle(title)
        SheetRow(Icons.Outlined.Spellcheck, "Recognise handwriting", "Index every page for search (${InkRecognizer.LANGUAGES.firstOrNull { it.first == notebook?.language }?.second ?: "ar"})") {
            showMore = false
            run("Recognising…") {
                val lang = notebook?.language ?: "ar"
                recognizer.ensureModel(lang)
                for (p in pages) {
                    val bytes = repo.loadStrokes(p.id) ?: continue
                    val text = recognizer.recognize(StrokeStore.decode(bytes), lang, p.widthPt, p.heightPt)
                    repo.saveRecognition(p.id, "ink", lang, text)
                }
                Toast.makeText(context, "Handwriting indexed for search", Toast.LENGTH_SHORT).show()
            }
        }
        SheetRow(Icons.Outlined.IosShare, "Export as PDF", "Flattened, opens anywhere") {
            showMore = false
            run("Exporting…") { exporter.share(exporter.exportNotebook(notebookId, title)) }
        }
        SheetRow(Icons.Outlined.Archive, "Export notebook bundle", "Lossless .notepad.zip") {
            showMore = false
            run("Exporting bundle…") {
                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val f = java.io.File(dir, title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" } + ".notepad.zip")
                f.outputStream().use { bundles.export(notebookId, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(android.content.Intent.createChooser(send, "Export notebook bundle"))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    managePage?.let { page ->
        val number = displayedPages.indexOfFirst { it.id == page.id } + 1
        ModalBottomSheet(
            onDismissRequest = { managePage = null },
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            SheetTitle("Page $number")
            SheetRow(Icons.Default.Add, "Insert page after", "Choose a template") {
                templateRequest = PageTemplateRequest(page, insertAfter = true)
                managePage = null
            }
            SheetRow(Icons.Outlined.ContentCopy, "Duplicate", "Copy this page and its content") {
                managePage = null
                run("Duplicating…") { repo.duplicatePage(page.id) }
            }
            if (page.kind != PageKind.PDF) SheetRow(Icons.Outlined.Palette, "Change template") {
                templateRequest = PageTemplateRequest(page, insertAfter = false)
                managePage = null
            }
            SheetRow(Icons.Outlined.DeleteOutline, "Delete", "Remove this page and its content", MaterialTheme.colorScheme.error) {
                deletePage = page
                managePage = null
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd || templateRequest != null) {
        val request = templateRequest
        val pickerTitle = when {
            showAdd -> "Add page"
            request?.insertAfter == true -> "Insert page after"
            else -> "Change template"
        }
        ModalBottomSheet(
            onDismissRequest = { showAdd = false; templateRequest = null },
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            SheetTitle(pickerTitle)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(PAGE_TEMPLATES) { (kind, label) ->
                    Column(
                        Modifier.clip(MaterialTheme.shapes.medium).clickable {
                            scope.launch {
                                runCatching {
                                    when {
                                        request == null -> repo.addPage(notebookId, kind)
                                        request.insertAfter -> repo.addPage(notebookId, kind, afterSortKey = request.page.sortKey)
                                        else -> {
                                            repo.setPageKind(request.page.id, kind)
                                            null
                                        }
                                    }
                                }.onSuccess { newPageId ->
                                    showAdd = false
                                    templateRequest = null
                                    if (request == null) onOpenPage(requireNotNull(newPageId))
                                }.onFailure {
                                    Toast.makeText(context, "Could not update page: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PagePreview(
                            repo,
                            Page("preview-$kind", notebookId, 0.0, kind, null, null, 595f, 842f, 0, 0),
                            paper,
                            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    deletePage?.let { page ->
        AlertDialog(
            onDismissRequest = { deletePage = null },
            title = { Text("Delete page?") },
            text = { Text("This page and its content will be removed from the notebook.") },
            confirmButton = {
                TextButton(onClick = {
                    deletePage = null
                    run("Deleting…") { repo.deletePage(page.id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletePage = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTile(
    repo: NotePadRepository,
    page: Page,
    number: Int,
    paper: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(page.widthPt / page.heightPt)
                .shadow(5.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
        ) {
            PagePreview(repo, page, paper, Modifier.fillMaxSize())
            dragHandle?.let {
                Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) { it() }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text("$number", style = MaterialTheme.typography.labelMedium)
        }
    }
}
