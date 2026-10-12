package com.hashim.notepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Archive
import com.hashim.notepad.export.BundleCodec
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.recognition.InkRecognizer
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
fun PagesScreen(
    repo: NotePadRepository, notebookId: String, exporter: PdfExporter, recognizer: InkRecognizer, bundles: BundleCodec,
    onBack: () -> Unit, onOpenPage: (String) -> Unit, showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var showLanguage by remember { mutableStateOf(false) }
    val pages by repo.pages(notebookId).collectAsState(initial = emptyList())
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val notebook = notebooks.firstOrNull { it.id == notebookId }
    val title = notebook?.title ?: ""
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { showLanguage = true }) { Text(InkRecognizer.LANGUAGES.firstOrNull { it.first == notebook?.language }?.second ?: "ar") }
                    IconButton(enabled = busy == null, onClick = {
                        scope.launch {
                            busy = "Recognising…"
                            runCatching {
                                val lang = notebook?.language ?: "ar"
                                recognizer.ensureModel(lang)
                                for (p in pages) {
                                    val bytes = repo.loadStrokes(p.id) ?: continue
                                    val strokes = StrokeStore.decode(bytes)
                                    val text = recognizer.recognize(strokes, lang, p.widthPt, p.heightPt)
                                    repo.saveRecognition(p.id, "ink", lang, text)
                                }
                            }.onFailure { Toast.makeText(context, "Recognition failed: ${it.message}", Toast.LENGTH_LONG).show() }
                                .onSuccess { Toast.makeText(context, "Handwriting indexed for search", Toast.LENGTH_SHORT).show() }
                            busy = null
                        }
                    }) { Icon(Icons.Default.Spellcheck, "Recognise handwriting") }
                    IconButton(enabled = busy == null, onClick = {
                        scope.launch {
                            busy = "Exporting…"
                            runCatching { exporter.exportNotebook(notebookId, title) }
                                .onSuccess { exporter.share(it) }
                                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                            busy = null
                        }
                    }) { Icon(Icons.Default.IosShare, "Export PDF") }
                    IconButton(enabled = busy == null, onClick = {
                        scope.launch {
                            busy = "Exporting bundle…"
                            runCatching {
                                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                val f = java.io.File(dir, title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" } + ".notepad.zip")
                                f.outputStream().use { bundles.export(notebookId, it) }
                                f
                            }.onSuccess { f ->
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                context.startActivity(android.content.Intent.createChooser(send, "Export notebook bundle"))
                            }.onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                            busy = null
                        }
                    }) { Icon(Icons.Default.Archive, "Export notebook bundle") }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add page") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.fillMaxSize(),
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
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text("Handwriting language") },
            text = {
                Column {
                    InkRecognizer.LANGUAGES.forEach { (tag, label) ->
                        TextButton(onClick = { scope.launch { repo.setNotebookLanguage(notebookId, tag) }; showLanguage = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLanguage = false }) { Text("Cancel") } },
        )
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
