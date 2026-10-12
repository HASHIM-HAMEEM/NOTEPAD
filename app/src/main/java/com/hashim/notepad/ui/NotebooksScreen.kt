package com.hashim.notepad.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.Notebook
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.export.BundleCodec
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.pdf.PdfImporter
import com.hashim.notepad.recognition.InkRecognizer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The library: every notebook as a true miniature of its first page. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebooksScreen(
    repo: NotePadRepository,
    importer: PdfImporter,
    bundles: BundleCodec,
    exporter: PdfExporter,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    var showAppearance by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Notebook?>(null) }
    var renameFor by remember { mutableStateOf<Notebook?>(null) }
    var deleteFor by remember { mutableStateOf<Notebook?>(null) }

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
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!compact) ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 128.dp else 168.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(
                    count = notebooks.size, compact = compact, importing = importing,
                    onSearch = onSearch, onAccount = onAccount, onAppearance = { showAppearance = true },
                    onNew = { showNew = true },
                )
            }
            if (notebooks.isEmpty() && !importing) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyLibrary(onNew = { showNew = true }, onImport = { pickPdf.launch(arrayOf("application/pdf")) })
            }
            items(notebooks, key = { it.id }) { nb ->
                NotebookCard(
                    repo = repo, notebook = nb, selected = nb.id == selectedId, compact = compact,
                    modifier = Modifier.animateItem(),
                    onOpen = { onOpen(nb.id) },
                    onMore = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actionsFor = nb },
                )
            }
        }
    }

    // ---- sheets & dialogs
    if (showNew) ModalBottomSheet(onDismissRequest = { showNew = false }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        SheetTitle("New notebook")
        SheetRow(Icons.Outlined.Description, "Blank notebook", "Plain, lined or grid pages") { showNew = false; showCreate = true }
        SheetRow(Icons.Outlined.PictureAsPdf, "From a PDF", "Write directly on a textbook") { showNew = false; pickPdf.launch(arrayOf("application/pdf")) }
        SheetRow(Icons.Outlined.Unarchive, "Import .notepad.zip", "A notebook exported from NotePad") { showNew = false; pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) }
        Spacer(Modifier.height(24.dp))
    }

    actionsFor?.let { nb ->
        val pages by repo.pages(nb.id).collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PagePreview(repo, pages.firstOrNull(), paperOf(nb), Modifier.width(44.dp).aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(16.dp))
                Column { Text(nb.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(meta(nb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow(Icons.Outlined.DriveFileRenameOutline, "Rename") { actionsFor = null; renameFor = nb }
            PaperRow(nb.coverColor) { hex -> scope.launch { repo.setNotebookPaper(nb.id, hex) } }
            LanguageRow(nb.language) { tag -> scope.launch { repo.setNotebookLanguage(nb.id, tag) } }
            SheetRow(Icons.Outlined.IosShare, "Export as PDF", "Flattened, opens anywhere") {
                actionsFor = null
                scope.launch { runCatching { exporter.exportNotebook(nb.id, nb.title) }.onSuccess { exporter.share(it) }.onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() } }
            }
            SheetRow(Icons.Outlined.Archive, "Export notebook bundle", "Lossless .notepad.zip, re-importable") {
                actionsFor = null
                scope.launch {
                    runCatching {
                        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                        val f = java.io.File(dir, nb.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "notebook" } + ".notepad.zip")
                        f.outputStream().use { bundles.export(nb.id, it) }
                        f
                    }.onSuccess { f ->
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        context.startActivity(android.content.Intent.createChooser(send, "Export notebook bundle"))
                    }.onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
            SheetRow(Icons.Outlined.Delete, "Delete notebook", tint = MaterialTheme.colorScheme.error) { actionsFor = null; deleteFor = nb }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCreate) NameDialog(title = "New notebook", initial = "", confirm = "Create", onDismiss = { showCreate = false }) { name ->
        scope.launch { val id = repo.createNotebook(name, null); repo.addPage(id, PageKind.BLANK); showCreate = false; onOpen(id) }
    }
    renameFor?.let { nb ->
        NameDialog(title = "Rename notebook", initial = nb.title, confirm = "Save", onDismiss = { renameFor = null }) { name -> scope.launch { repo.renameNotebook(nb.id, name) }; renameFor = null }
    }
    deleteFor?.let { nb ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete “${nb.title}”?") },
            text = { Text("${nb.pageCount} pages and their ink will be removed from this device and from sync.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteNotebook(nb.id) }; deleteFor = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
    if (showAppearance) ModalBottomSheet(onDismissRequest = { showAppearance = false }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        SheetTitle("Appearance")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeMode.entries.forEach { m ->
                val selected = themeMode == m
                Surface(
                    onClick = { onThemeChange(m) }, shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ThemeSwatch(m)
                        Spacer(Modifier.height(8.dp))
                        Text(m.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        ListItem(
            headlineContent = { Text("Use wallpaper colours") }, supportingContent = { Text("Material You accent from your wallpaper") },
            trailingContent = { Switch(checked = dynamicColor, onCheckedChange = onDynamicChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------------------------------------------------------------- pieces */

@Composable
private fun LibraryHeader(count: Int, compact: Boolean, importing: Boolean, onSearch: () -> Unit, onAccount: () -> Unit, onAppearance: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall)
                Text(
                    if (importing) "Importing…" else if (count == 0) "Nothing here yet" else "$count notebook${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (compact) IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New") }
            IconButton(onClick = onAppearance) { Icon(Icons.Outlined.Palette, "Appearance") }
            IconButton(onClick = onAccount) { Icon(Icons.Outlined.AccountCircle, "Sync & account") }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            onClick = onSearch, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Search handwriting, text and PDFs", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(importing, enter = fadeIn(), exit = fadeOut()) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape)) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NotebookCard(repo: NotePadRepository, notebook: Notebook, selected: Boolean, compact: Boolean, modifier: Modifier, onOpen: () -> Unit, onMore: () -> Unit) {
    val pages by repo.pages(notebook.id).collectAsState(initial = emptyList())
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    val radius = 14.dp
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .shadow(if (selected) 10.dp else 4.dp, RoundedCornerShape(radius), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(radius))
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(radius))
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onOpen, onLongClick = onMore),
        ) {
            PagePreview(repo, pages.firstOrNull(), paperOf(notebook), Modifier.fillMaxSize())
            // spine
            Box(Modifier.fillMaxHeight().width(6.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)))
            if (notebook.coverColor == "#1F1F22") Unit
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(notebook.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(notebook), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!compact) IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EmptyLibrary(onNew: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("Start a blank notebook, or open a textbook PDF and write straight onto it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 320.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNew, shape = CircleShape) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New notebook") }
            OutlinedButton(onClick = onImport, shape = CircleShape) { Text("Open a PDF") }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
}

@Composable
internal fun SheetRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = tint) },
        headlineContent = { Text(title, color = tint) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun PaperRow(current: String?, onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Palette, null); Spacer(Modifier.width(16.dp))
        Text("Paper", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        PAPER_COLORS.forEach { (hex, label) ->
            val c = Color(android.graphics.Color.parseColor(hex))
            val sel = (current ?: "#FFFFFF") == hex
            Box(
                Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape).background(c)
                    .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) { if (sel) Icon(Icons.Default.Check, label, Modifier.size(16.dp), tint = if (hex == "#1F1F22") Color.White else MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun LanguageRow(current: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Translate, null); Spacer(Modifier.width(16.dp))
        Text("Handwriting", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        InkRecognizer.LANGUAGES.forEach { (tag, label) ->
            FilterChip(selected = current == tag, onClick = { onPick(tag) }, label = { Text(label) }, modifier = Modifier.padding(start = 6.dp), shape = CircleShape)
        }
    }
}

@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val (bg, fg) = when (mode) {
        ThemeMode.LIGHT -> Color(0xFFFAFAF7) to Color(0xFF2458C5)
        ThemeMode.DARK -> Color(0xFF1F1F22) to Color(0xFFADC6FF)
        ThemeMode.SEPIA -> Color(0xFFF6EFE0) to Color(0xFF7A4F2A)
        ThemeMode.SYSTEM -> MaterialTheme.colorScheme.surfaceContainerLowest to MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(40.dp, 28.dp).clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))) {
        Box(Modifier.padding(6.dp).size(14.dp, 3.dp).background(fg, CircleShape))
        Box(Modifier.padding(start = 6.dp, top = 13.dp).size(22.dp, 3.dp).background(fg.copy(alpha = 0.5f), CircleShape))
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Title") }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }, shape = CircleShape) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

internal fun paperOf(nb: Notebook): Color = nb.coverColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.White
private fun meta(nb: Notebook) = "${nb.pageCount} page${if (nb.pageCount == 1) "" else "s"} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt))}"
