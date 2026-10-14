package com.hashim.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.AudioRecording
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.displayHeightPt
import com.hashim.notepad.data.displayWidthPt
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.pdf.PdfOutlineItem
import com.hashim.notepad.pdf.PdfOutlineRow
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfPasswordVault
import com.hashim.notepad.pdf.PdfTextSearchHit
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.hashim.notepad.pdf.PdfSearchResultKey
import com.hashim.notepad.pdf.PdfiumDispatcher
import com.hashim.notepad.pdf.chapterForPage
import com.hashim.notepad.pdf.flattenOutline
import com.hashim.notepad.pdf.outlinePathForPage
import com.hashim.notepad.pdf.searchPdfText
import com.hashim.notepad.pdf.PdfWordSnap
import com.hashim.notepad.search.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.launch

@Composable
internal fun PageSidebar(
    repo: NotePadRepository,
    pages: List<Page>,
    currentPageId: String,
    onPageSelected: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchHit: (PdfTextSearchHit) -> Unit = {},
    onSearchResults: (List<PdfTextSearchHit>) -> Unit = {},
    initialQuery: String = "",
    onAudioRecordingSelected: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf(emptyList<PdfTextSearchHit>()) }
    var searching by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(emptyList<PdfOutlineItem>()) }
    var outlineLoading by remember { mutableStateOf(false) }
    var expandedPaths by remember { mutableStateOf(emptySet<List<Int>>()) }
    var marks by remember { mutableStateOf(emptyList<PdfMark>()) }
    var markColorFilter by remember { mutableStateOf<Int?>(null) }
    var deletedAudio by remember { mutableStateOf<AudioRecording?>(null) }
    var renamingAudioId by remember { mutableStateOf<String?>(null) }
    var editedAudioTitle by remember { mutableStateOf("") }
    val tabListState = rememberLazyListState()
    LaunchedEffect(selectedTab) {
        tabListState.animateScrollToItem(selectedTab)
    }
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            query = initialQuery
            selectedTab = 4
        }
    }
    val assetId = pages.firstOrNull { it.kind == PageKind.PDF }?.assetId
    val currentPage = pages.firstOrNull { it.id == currentPageId }
    val notebookId = pages.firstOrNull()?.notebookId
    val audioRecordings by (notebookId?.let(repo::audioRecordings)
        ?: remember { kotlinx.coroutines.flow.flowOf(emptyList()) }).collectAsState(initial = emptyList())
    val currentPdfIndex = currentPage?.pdfPageIndex ?: pages.indexOfFirst { it.id == currentPageId }
    val currentOutlinePath = outlinePathForPage(outline, currentPdfIndex)
    val activeChapter = chapterForPage(outline, currentPdfIndex)
    val contextPaths = currentOutlinePath?.indices?.map { currentOutlinePath.take(it + 1) }?.toSet().orEmpty()
    val outlineRows = flattenOutline(outline, expandedPaths)

    LaunchedEffect(assetId) {
        outlineLoading = assetId != null
        outline = emptyList()
        if (assetId != null) {
            val asset = repo.asset(assetId)
            if (asset != null) {
                outline = withContext(PdfiumDispatcher.dispatcher) {
                    runCatching {
                        PdfPageSource(context, File(asset.localPath), PdfPasswordVault(context).read(asset.id))
                            .use { it.tableOfContents() }
                    }.getOrDefault(emptyList())
                }
            }
        }
        outlineLoading = false
    }
    LaunchedEffect(currentOutlinePath) {
        if (currentOutlinePath != null) expandedPaths = contextPaths
    }
    LaunchedEffect(pages.firstOrNull()?.notebookId) {
        val notebookId = pages.firstOrNull()?.notebookId ?: return@LaunchedEffect
        repo.pdfMarks(notebookId).collect { marks = it.filterNot(PdfMark::deleted) }
    }
    LaunchedEffect(query, assetId, pages, marks) {
        searchHits = emptyList()
        onSearchResults(emptyList())
        val id = assetId ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        val asset = repo.asset(id) ?: return@LaunchedEffect
        searching = true
        try {
            searchPdfText(
                context = context,
                assetId = id,
                file = File(asset.localPath),
                password = PdfPasswordVault(context).read(asset.id),
                pages = pages,
                query = query,
                loadOcrWords = { pageId -> repo.ocrWords(pageId) },
            ) { hit ->
                withContext(Dispatchers.Main.immediate) {
                    searchHits = searchHits + hit
                    onSearchResults(searchHits)
                }
            }
            val normalizedQuery = TextNormalizer.normalize(query).text
            if (normalizedQuery.isNotEmpty()) {
                val markHits = marks.asSequence().filterNot(PdfMark::deleted).mapNotNull { mark ->
                    val combined = listOf(mark.text, mark.note.orEmpty()).filter(String::isNotBlank).joinToString(" ")
                    val normalized = TextNormalizer.normalize(combined)
                    val at = normalized.text.indexOf(normalizedQuery)
                    if (mark.pageId.isBlank() || at < 0) null else {
                        val pageIndex = pages.indexOfFirst { it.id == mark.pageId }.coerceAtLeast(0)
                        PdfTextSearchHit(
                            pageId = mark.pageId,
                            pageIndex = pageIndex,
                            snippet = combined,
                            matchStart = mark.charStart ?: at,
                            matchEnd = mark.charEnd ?: at + normalizedQuery.length,
                            snippetMatchStart = at,
                            snippetMatchEnd = at + normalizedQuery.length,
                            rects = PdfWordSnap.topLeftRects(mark.quads, pages.getOrNull(pageIndex)?.heightPt ?: 0f),
                            resultKey = PdfSearchResultKey.mark(mark.id),
                        )
                    }
                }.toList()
                if (markHits.isNotEmpty()) {
                    searchHits = searchHits + markHits
                    onSearchResults(searchHits)
                }
            }
        } finally {
            searching = false
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = tabListState,
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(listOf("Pages", "Contents", "Marks", "Bookmarks", "Search", "Audio")) { index, title ->
                    Column(
                        Modifier.clickable { selectedTab = index }.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent))
                    }
                }
            }
            Text("×", Modifier.clickable(onClick = onClose).padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (selectedTab) {
            0, 3 -> {
                val visible = pages.withIndex().filter { selectedTab == 0 || it.value.bookmarked }
                if (selectedTab == 3 && visible.isEmpty()) {
                    EmptySidebar("No bookmarks yet")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(visible, key = { _, item -> "page:${item.value.id}" }) { _, indexedPage ->
                            val page = indexedPage.value
                            Column(
                                Modifier.fillMaxWidth().clickable { onPageSelected(page.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PagePreview(
                                    repo = repo,
                                    page = page,
                                    paperColor = MaterialTheme.colorScheme.background,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(page.displayWidthPt / page.displayHeightPt)
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(
                                            if (page.id == currentPageId) 2.dp else 1.dp,
                                            if (page.id == currentPageId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(4.dp),
                                        ),
                                )
                                Text("${indexedPage.index + 1}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            1 -> {
                when {
                    outlineLoading -> EmptySidebar("Loading contents…")
                    outline.isEmpty() -> EmptySidebar("No contents in this PDF")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(outlineRows, key = { "contents:${it.path.joinToString(".")}" }) { row ->
                            OutlineRow(
                                row = row,
                                selected = row.item == activeChapter,
                                expanded = row.path in expandedPaths,
                                onExpand = {
                                    expandedPaths = expandedPaths.toMutableSet().apply {
                                        if (contains(row.path)) remove(row.path) else add(row.path)
                                    }
                                },
                                onClick = {
                                    val target = pages.firstOrNull { it.pdfPageIndex == row.item.pageIndex }
                                        ?: pages.getOrNull(row.item.pageIndex)
                                    target?.let { onPageSelected(it.id) }
                                },
                            )
                        }
                    }
                }
            }
            2 -> {
                val visibleMarks = marks.filter { markColorFilter == null || it.colorArgb == markColorFilter }
                    .groupBy { it.pageId }
                if (marks.isEmpty()) {
                    EmptySidebar("No marks yet")
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("All", Modifier.clickable { markColorFilter = null }, color = MaterialTheme.colorScheme.primary)
                            marks.map { it.colorArgb }.distinct().forEach { color ->
                                Box(
                                    Modifier.padding(2.dp).width(18.dp).height(18.dp)
                                        .clip(RoundedCornerShape(50)).background(Color(color))
                                        .clickable { markColorFilter = if (markColorFilter == color) null else color },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Export",
                                Modifier.clickable {
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/markdown"
                                        putExtra(android.content.Intent.EXTRA_TEXT, PdfMarkGeometry.markdown(marks))
                                    }
                                    context.startActivity(android.content.Intent.createChooser(send, "Export marks"))
                                },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            visibleMarks.forEach { (pageId, pageMarks) ->
                                val pageNumber = pages.indexOfFirst { it.id == pageId } + 1
                                item(key = "marks-page:$pageId") {
                                    Text(
                                        "Page $pageNumber",
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                items(pageMarks, key = { "pdf-mark:${it.id}" }) { mark ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            pages.firstOrNull { it.id == pageId }?.let { onPageSelected(it.id) }
                                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(mark.text.ifBlank { mark.kind.db.replaceFirstChar(Char::uppercase) }, maxLines = 2)
                                        mark.note?.takeIf(String::isNotBlank)?.let {
                                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            5 -> {
                if (audioRecordings.isEmpty() && deletedAudio == null) {
                    EmptySidebar("No recordings yet")
                } else {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        deletedAudio?.let { deleted ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recording deleted", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    scope.launch { repo.setAudioRecordingDeleted(deleted.id, false) }
                                    deletedAudio = null
                                }) { Text("Undo") }
                            }
                        }
                        if (audioRecordings.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("No recordings yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                items(audioRecordings, key = { "audio:${it.id}" }) { recording ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { onAudioRecordingSelected(recording.id) }
                                        .padding(vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    if (renamingAudioId == recording.id) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(
                                                value = editedAudioTitle,
                                                onValueChange = { editedAudioTitle = it },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                            )
                                            TextButton(onClick = {
                                                scope.launch { repo.renameAudioRecording(recording.id, editedAudioTitle) }
                                                renamingAudioId = null
                                            }) { Text("Save") }
                                        }
                                    } else {
                                        Text(recording.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    Text(
                                        "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.startedAt))} · ${formatRecordingDuration(recording.durationMs)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Row {
                                        TextButton(onClick = {
                                            editedAudioTitle = recording.title
                                            renamingAudioId = recording.id
                                        }) { Text("Rename") }
                                        TextButton(onClick = {
                                            scope.launch { repo.setAudioRecordingDeleted(recording.id, true) }
                                            deletedAudio = recording
                                        }) { Text("Delete") }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search this PDF") },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (searching && searchHits.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    if (searchHits.isEmpty() && !searching && query.isNotBlank()) {
                        Text("No matches", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(searchHits, key = { it.resultKey }) { hit ->
                            val pageNumber = pages.indexOfFirst { it.id == hit.pageId } + 1
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    onPageSelected(hit.pageId)
                                    onSearchHit(hit)
                                }.padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Page $pageNumber", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                val scriptFont = rememberScriptFontFamily(hit.snippet)
                                Text(
                                    buildAnnotatedString {
                                        append(hit.snippet.substring(0, hit.snippetMatchStart))
                                        withStyle(SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))) {
                                            append(hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
                                        }
                                        append(hit.snippet.substring(hit.snippetMatchEnd))
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content, fontFamily = scriptFont),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRecordingDuration(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0) / 1000).toInt()
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun EmptySidebar(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlineRow(
    row: PdfOutlineRow,
    selected: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = (8 + row.depth * 12).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.item.children.isNotEmpty()) {
            Text(if (expanded) "⌄" else "›", Modifier.clickable(onClick = onExpand).padding(end = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            row.item.title,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
        )
    }
}
