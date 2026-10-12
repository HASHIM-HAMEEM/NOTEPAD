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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Unarchive
import com.hashim.notepad.export.BundleCodec
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
    bundles: BundleCodec,
    importing: Boolean,
    onImportingChanged: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit = {},
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    selectedId: String? = null,
    compact: Boolean = false,
) {
    var showSettings by remember { mutableStateOf(false) }
    var paperFor by remember { mutableStateOf<Notebook?>(null) }
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

    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            onImportingChanged(true)
            runCatching { context.contentResolver.openInputStream(uri)!!.use { bundles.import(it) } }
                .onSuccess { onOpen(it) }
                .onFailure { Toast.makeText(context, "Could not import bundle: ${it.message}", Toast.LENGTH_LONG).show() }
            onImportingChanged(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebooks") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = onAccount) { Icon(Icons.Default.AccountCircle, "Sync & account") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Settings") }
                    IconButton(onClick = { pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !importing) {
                        Icon(Icons.Default.Unarchive, "Import notebook bundle")
                    }
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
                    columns = GridCells.Adaptive(if (compact) 130.dp else 180.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notebooks, key = { it.id }) { nb ->
                        val paper = nb.coverColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                        OutlinedCard(
                            Modifier.aspectRatio(0.78f).combinedClickable(onClick = { onOpen(nb.id) }, onLongClick = { toDelete = nb }),
                            colors = CardDefaults.outlinedCardColors(containerColor = paper ?: MaterialTheme.colorScheme.surface, contentColor = if (nb.coverColor == "#1F1F22") Color.White else MaterialTheme.colorScheme.onSurface),
                            border = if (nb.id == selectedId) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
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
            title = { Text(nb.title) },
            text = { Text("${nb.pageCount} pages") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteNotebook(nb.id) }; toDelete = null }) { Text("Delete") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { paperFor = nb; toDelete = null }) { Text("Paper colour") }
                    TextButton(onClick = { toDelete = null }) { Text("Cancel") }
                }
            },
        )
    }
    paperFor?.let { nb ->
        AlertDialog(
            onDismissRequest = { paperFor = null },
            title = { Text("Paper colour") },
            text = { Column { PAPER_COLORS.forEach { (hex, label) -> TextButton(onClick = { scope.launch { repo.setNotebookPaper(nb.id, hex) }; paperFor = null }, modifier = Modifier.fillMaxWidth()) { Text(label) } } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { paperFor = null }) { Text("Cancel") } },
        )
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Appearance") },
            text = {
                Column {
                    ThemeMode.entries.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { onThemeChange(m) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = themeMode == m, onClick = { onThemeChange(m) }); Text(m.label)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("System accent colour"); Switch(checked = dynamicColor, onCheckedChange = onDynamicChange)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
        )
    }
}
