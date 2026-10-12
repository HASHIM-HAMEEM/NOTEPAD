package com.hashim.notepad.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.Notebook
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.pdf.PdfImporter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebooksScreen(
    repo: NotePadRepository,
    importer: PdfImporter,
    importing: Boolean,
    onImportingChanged: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Notebook?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            onImportingChanged(true)
            runCatching { importer.importAsNotebook(uri) }
                .onSuccess { onOpen(it) }
                .onFailure { Toast.makeText(context, "Could not import PDF: ${it.message}", Toast.LENGTH_LONG).show() }
            onImportingChanged(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebooks") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !importing) {
                        Icon(Icons.Default.PictureAsPdf, "Import PDF")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "New notebook") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notebooks.isEmpty() && !importing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notebooks yet. Tap + or import a PDF.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notebooks, key = { it.id }) { nb ->
                        OutlinedCard(
                            Modifier.aspectRatio(0.78f).combinedClickable(onClick = { onOpen(nb.id) }, onLongClick = { toDelete = nb })
                        ) {
                            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(nb.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Column {
                                    Text("${nb.pageCount} pages", style = MaterialTheme.typography.labelMedium)
                                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New notebook") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") }) },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch { val id = repo.createNotebook(title.trim(), null); repo.addPage(id, com.hashim.notepad.data.PageKind.BLANK); showCreate = false; onOpen(id) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
    toDelete?.let { nb ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete “${nb.title}”?") },
            text = { Text("The notebook and its ${nb.pageCount} pages will be removed.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteNotebook(nb.id) }; toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
