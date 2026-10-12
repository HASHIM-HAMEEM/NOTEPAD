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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hashim.notepad.data.Notebook
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.export.BundleCodec
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.ink.PaperTexture
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
            if (notebooks.isNotEmpty()) {
                val recent = notebooks.sortedByDescending { it.updatedAt }.take(3)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Recent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            recent.forEach { nb ->
                                NotebookCard(
                                    repo, nb, nb.id == selectedId, compact, Modifier.width(208.dp),
                                    onOpen = { onOpen(nb.id) },
                                    onMore = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actionsFor = nb },
                                )
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("All notebooks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
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
    if (showNew) ModalBottomSheet(onDismissRequest = { showNew = false }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        SheetTitle("New notebook")
        SheetRow(Icons.Outlined.Description, "Blank notebook", "Plain, lined or grid pages") { showNew = false; showCreate = true }
        SheetRow(Icons.Outlined.PictureAsPdf, "From a PDF", "Write directly on a textbook") { showNew = false; pickPdf.launch(arrayOf("application/pdf")) }
        SheetRow(Icons.Outlined.Unarchive, "Import .notepad.zip", "A notebook exported from NotePad") { showNew = false; pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) }
        Spacer(Modifier.height(24.dp))
    }

    actionsFor?.let { nb ->
        val pages by repo.pages(nb.id).collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(nb.title, bookCoverColor(nb), pages.any { it.kind == PageKind.PDF }, Modifier.width(44.dp).aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(16.dp))
                Column { Text(nb.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = titleFontFamily(nb.title)), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(meta(nb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    if (showAppearance) ModalBottomSheet(onDismissRequest = { showAppearance = false }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
            onClick = onSearch, shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    val cover = remember(notebook.id, notebook.coverColor) { bookCoverColor(notebook) }
    val radius = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .shadow(if (selected) 12.dp else 7.dp, radius, spotColor = Color.Black.copy(alpha = 0.36f))
                .clip(radius)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else cover.copy(alpha = 0.65f), radius)
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onOpen, onLongClick = onMore),
        ) {
            BookCover(notebook.title, cover, pages.any { it.kind == PageKind.PDF }, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(notebook.title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = titleFontFamily(notebook.title)), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(notebook), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!compact) IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BookCover(title: String, color: Color, isPdf: Boolean, modifier: Modifier = Modifier) {
    val spine = Color(color.red * 0.62f, color.green * 0.62f, color.blue * 0.62f)
    val edge = Color(color.red * 0.91f, color.green * 0.91f, color.blue * 0.91f)
    Box(modifier.background(Brush.horizontalGradient(listOf(spine, color, edge)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = PaperTexture.shader()
                    alpha = 110
                    colorFilter = android.graphics.PorterDuffColorFilter(
                        android.graphics.Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()),
                        android.graphics.PorterDuff.Mode.MULTIPLY,
                    )
                }
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
            val spineWidth = 10.dp.toPx()
            drawRect(spine, size = androidx.compose.ui.geometry.Size(spineWidth, size.height))
            var y = 10.dp.toPx()
            while (y < size.height - 10.dp.toPx()) {
                drawLine(Color(0xFFF3E7D4).copy(alpha = 0.7f), Offset(spineWidth / 2f, y), Offset(spineWidth / 2f, y + 4.dp.toPx()), 1.dp.toPx())
                y += 10.dp.toPx()
            }
            drawLine(Color.White.copy(alpha = 0.18f), Offset(spineWidth + 2.dp.toPx(), 0f), Offset(spineWidth + 2.dp.toPx(), size.height), 1.dp.toPx())
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).width(7.dp).fillMaxHeight(0.92f)
                .background(spine.copy(alpha = 0.82f), RoundedCornerShape(4.dp)),
        )
        Surface(
            Modifier.fillMaxWidth(if (isPdf) 0.68f else 1f).padding(start = 18.dp, end = 18.dp, top = 18.dp),
            shape = RoundedCornerShape(3.dp),
            color = Color(0xFFF4EBDD).copy(alpha = 0.94f),
        ) {
            Text(
                title,
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                color = Color(0xFF382D22),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = titleFontFamily(title), fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPdf) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                color = Color(0xFFB4532A),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("PDF", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val coverPalette = listOf(
    Color(0xFF7A2E2A), Color(0xFF2F5D50), Color(0xFF233A5E), Color(0xFFC08A2E),
    Color(0xFF5B3A5E), Color(0xFF4B5563), Color(0xFFB4532A), Color(0xFF6B6B2F),
)

private fun bookCoverColor(notebook: Notebook): Color {
    notebook.coverColor?.let { hex -> runCatching { return Color(android.graphics.Color.parseColor(hex)) } }
    return coverPalette[Math.floorMod(notebook.id.hashCode(), coverPalette.size)]
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
    Text(text, style = MaterialTheme.typography.titleLarge.copy(fontFamily = titleFontFamily(text)), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
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
        ThemeMode.LIGHT -> Color(0xFFF3EFE7) to Color(0xFFB4532A)
        ThemeMode.DARK -> Color(0xFF1B1916) to Color(0xFFF0A47C)
        ThemeMode.SEPIA -> Color(0xFFF1E8D8) to Color(0xFFB4532A)
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
