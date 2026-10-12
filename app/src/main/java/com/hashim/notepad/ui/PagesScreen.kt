package com.hashim.notepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PageKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(repo: NotePadRepository, notebookId: String, onBack: () -> Unit, onOpenPage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val title = notebooks.firstOrNull { it.id == notebookId }?.title ?: ""
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add page") } },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedCard(Modifier.aspectRatio(page.widthPt / page.heightPt).clickable { onOpenPage(page.id) }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when (page.kind) { PageKind.PDF -> "PDF"; PageKind.LINED -> "Lined"; PageKind.GRID -> "Grid"; PageKind.BLANK -> "" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add page") },
            text = {
                Column {
                    listOf(PageKind.BLANK to "Blank", PageKind.LINED to "Lined", PageKind.GRID to "Grid").forEach { (kind, label) ->
                        TextButton(onClick = { scope.launch { val id = repo.addPage(notebookId, kind); showAdd = false; onOpenPage(id) } }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
