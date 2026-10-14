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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.pdf.PdfOutlineItem
import com.hashim.notepad.pdf.PdfOutlineRow
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfPasswordVault
import com.hashim.notepad.pdf.PdfTextSearchHit
import com.hashim.notepad.pdf.PdfiumDispatcher
import com.hashim.notepad.pdf.chapterForPage
import com.hashim.notepad.pdf.flattenOutline
import com.hashim.notepad.pdf.outlinePathForPage
import com.hashim.notepad.pdf.searchPdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf(emptyList<PdfTextSearchHit>()) }
    var searching by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(emptyList<PdfOutlineItem>()) }
    var outlineLoading by remember { mutableStateOf(false) }
    var expandedPaths by remember { mutableStateOf(emptySet<List<Int>>()) }
    val assetId = pages.firstOrNull { it.kind == PageKind.PDF }?.assetId
    val currentPage = pages.firstOrNull { it.id == currentPageId }
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
    LaunchedEffect(query, assetId, pages) {
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
            ) { hit ->
                withContext(Dispatchers.Main.immediate) {
                    searchHits = searchHits + hit
                    onSearchResults(searchHits)
                }
            }
        } finally {
            searching = false
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Pages", "Contents", "Bookmarks", "Search").forEachIndexed { index, title ->
                Column(
                    Modifier.weight(1f).clickable { selectedTab = index }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        title,
                        color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
            Text("×", Modifier.clickable(onClick = onClose).padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (selectedTab) {
            0, 2 -> {
                val visible = pages.withIndex().filter { selectedTab == 0 || it.value.bookmarked }
                if (selectedTab == 2 && visible.isEmpty()) {
                    EmptySidebar("No bookmarks yet")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(visible, key = { _, item -> item.value.id }) { _, indexedPage ->
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
                                    modifier = Modifier.fillMaxWidth().aspectRatio(page.widthPt / page.heightPt)
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
                        items(outlineRows, key = { it.path.joinToString(".") }) { row ->
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
                        items(searchHits, key = { "${it.pageId}:${it.matchStart}" }) { hit ->
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
