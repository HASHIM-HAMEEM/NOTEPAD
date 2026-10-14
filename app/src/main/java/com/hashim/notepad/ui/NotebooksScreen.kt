package com.hashim.notepad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.Folder
import com.hashim.notepad.data.Notebook
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.Tag
import com.hashim.notepad.data.displayWidthPt
import com.hashim.notepad.data.displayHeightPt
import com.hashim.notepad.export.BundleCodec
import com.hashim.notepad.export.PdfExporter
import com.hashim.notepad.pdf.PdfImporter
import com.hashim.notepad.pdf.FileImporter
import com.hashim.notepad.pdf.ImportProgress
import com.hashim.notepad.pdf.ImportStorage
import com.hashim.notepad.pdf.PdfPasswordRequiredException
import com.hashim.notepad.pdf.PdfPasswordIncorrectException
import com.hashim.notepad.pdf.PageImageImporter
import com.hashim.notepad.data.ImportedImageContent
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.hashim.notepad.recognition.InkRecognizer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.DateFormat
import java.util.Date

internal const val ALL_FOLDERS = "all"

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun showScannerFallback(host: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, choosePhotos: () -> Unit) {
    scope.launch {
        if (host.showSnackbar(
                message = "Scanner isn't available on this device",
                actionLabel = "Choose photos",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            ) == SnackbarResult.ActionPerformed
        ) choosePhotos()
    }
}

private data class FolderSnapshot(val value: List<Folder>?)

/** The library: every notebook as a true miniature of its first page. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NotebooksScreen(
    repo: NotePadRepository,
    importer: PdfImporter,
    bundles: BundleCodec,
    exporter: PdfExporter,
    importing: Boolean,
    onImportingChanged: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onContinueAtPosition: (String, Int) -> Unit = { id, _ -> onOpen(id) },
    onSearch: () -> Unit,
    onAccount: () -> Unit = {},
    selectedFolderId: String,
    onSelectedFolderChange: (String) -> Unit,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    compact: Boolean = false,
    showAccountButton: Boolean = true,
    sharedItems: List<Uri> = emptyList(),
    sharedMimeType: String? = null,
    sharedIntentId: String? = null,
    onSharedIntentConsumed: (String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val tabletLayout = screenWidthDp >= 840 && !compact
    val phoneLayout = screenWidthDp < 600 && !compact
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val foldersFlow = remember(repo) { repo.folders().map(::FolderSnapshot) }
    val folderSnapshot by foldersFlow.collectAsState(initial = FolderSnapshot(null))
    val folders = folderSnapshot.value.orEmpty()
    val tags by repo.tags().collectAsState(initial = emptyList())
    val gridState = rememberLazyGridState()
    val renderThumbnails = !gridState.isScrollInProgress
    val libraryPrefs = remember(context) { context.getSharedPreferences("notepad", 0) }
    var librarySort by remember {
        mutableStateOf(runCatching { LibrarySort.valueOf(libraryPrefs.getString("library_sort", "RECENT") ?: "RECENT") }.getOrDefault(LibrarySort.RECENT))
    }
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var showAppearance by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Notebook?>(null) }
    var renameFor by remember { mutableStateOf<Notebook?>(null) }
    var deleteFor by remember { mutableStateOf<Notebook?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderForNotebook by remember { mutableStateOf<Notebook?>(null) }
    var folderActionsFor by remember { mutableStateOf<Folder?>(null) }
    var renameFolderFor by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderFor by remember { mutableStateOf<Folder?>(null) }
    var moveNotebookFor by remember { mutableStateOf<Notebook?>(null) }
    var tagsForNotebook by remember { mutableStateOf<Notebook?>(null) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newTagName by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var showSaveShared by remember { mutableStateOf(false) }
    var handledSharedIntentId by rememberSaveable { mutableStateOf("") }
    var sharedTitle by remember { mutableStateOf("") }
    var sharedFolderId by remember { mutableStateOf<String?>(null) }
    var pendingImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingImportMimeType by remember { mutableStateOf<String?>(null) }
    var passwordUri by remember { mutableStateOf<Uri?>(null) }
    var passwordEntry by remember { mutableStateOf("") }
    var passwordFolderId by remember { mutableStateOf<String?>(null) }
    var passwordTitle by remember { mutableStateOf<String?>(null) }
    var passwordFolderPending by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val pageImageImporter = remember(context) { PageImageImporter(context) }
    val fileImporter = remember(context, repo) { FileImporter(context, repo) }
    fun requestImportDetails(uris: List<Uri>, title: String? = null, mimeType: String? = null) {
        if (uris.isEmpty()) return
        pendingImportUris = uris
        pendingImportMimeType = mimeType
        val filename = runCatching { ImportStorage(context).displayName(uris.first()) }.getOrDefault("Imported file")
        sharedTitle = title ?: filename.substringBeforeLast('.', filename)
        sharedFolderId = selectedFolderId.takeUnless { it == ALL_FOLDERS }
        showSaveShared = true
    }
    fun openStoredFile(notebook: Notebook) {
        scope.launch {
            val asset = notebook.fileAssetId?.let { repo.asset(it) }
            if (asset == null) {
                snackbarHostState.showSnackbar("This file is no longer available")
                return@launch
            }
            val contentUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(asset.localPath))
            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, notebook.fileMimeType ?: "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (context.packageManager.resolveActivity(viewIntent, 0) == null) {
                snackbarHostState.showSnackbar("No app can open this file")
            } else {
                runCatching { context.startActivity(android.content.Intent.createChooser(viewIntent, "Open file")) }
                    .onFailure { snackbarHostState.showSnackbar("No app can open this file") }
            }
        }
    }
    LaunchedEffect(sharedIntentId) {
        val intentId = sharedIntentId ?: return@LaunchedEffect
        if (sharedItems.isNotEmpty() && intentId != handledSharedIntentId) {
            handledSharedIntentId = intentId
            requestImportDetails(sharedItems, mimeType = sharedMimeType)
            onSharedIntentConsumed(intentId)
        }
    }
    LaunchedEffect(librarySort) { libraryPrefs.edit().putString("library_sort", librarySort.name).apply() }
    LaunchedEffect(folderSnapshot, selectedFolderId) {
        val loadedFolders = folderSnapshot.value ?: return@LaunchedEffect
        if (selectedFolderId != ALL_FOLDERS && loadedFolders.none { it.id == selectedFolderId }) {
            onSelectedFolderChange(ALL_FOLDERS)
        }
    }
    LaunchedEffect(tags, selectedTagId) {
        if (selectedTagId != null && tags.none { it.id == selectedTagId }) selectedTagId = null
    }
    val visibleNotebooks = remember(notebooks, selectedFolderId, selectedTagId) {
        notebooks.filter { notebook ->
            (selectedFolderId == ALL_FOLDERS || notebook.folderId == selectedFolderId) &&
                (selectedTagId == null || selectedTagId in notebook.tagIds)
        }
    }
    val sortedNotebooks = remember(visibleNotebooks, librarySort) { sortNotebooks(visibleNotebooks, librarySort) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        requestImportDetails(uris)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        requestImportDetails(uris)
    }
    val pickAnyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        requestImportDetails(uris)
    }
    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = scan?.pdf?.uri
            val imageUris = scan?.pages.orEmpty().map { it.imageUri }
            if (pdfUri != null) requestImportDetails(listOf(pdfUri), "Scanned notes")
            else if (imageUris.isNotEmpty()) requestImportDetails(imageUris, "Scanned notes")
            else showScannerFallback(snackbarHostState, scope) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        } else {
            showScannerFallback(snackbarHostState, scope) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
    }
    fun startScanner() {
        val activity = context.findActivity()
        if (activity == null) {
            showScannerFallback(snackbarHostState, scope) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(100)
            .setGalleryImportAllowed(true)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                showScannerFallback(snackbarHostState, scope) { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!compact) ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Import") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
        LazyVerticalGrid(
            state = gridState,
            columns = when {
                tabletLayout -> GridCells.Adaptive(168.dp)
                phoneLayout -> GridCells.Adaptive(150.dp)
                else -> GridCells.Fixed(1)
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = if (phoneLayout) {
                PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)
            } else {
                PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 184.dp)
            },
            horizontalArrangement = Arrangement.spacedBy(if (phoneLayout) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (phoneLayout) 16.dp else 20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "library-header") {
                LibraryHeader(
                    count = visibleNotebooks.size, compact = compact, importing = importing, progress = importProgress,
                    onCancelImport = { importJob?.cancel() }, showAccountButton = showAccountButton,
                    librarySort = librarySort, onSortChange = { librarySort = it },
                    tabletLayout = tabletLayout,
                    onSearch = onSearch, onAccount = onAccount, onAppearance = { showAppearance = true },
                    onNew = { showNew = true },
                    folders = folders, selectedFolderId = selectedFolderId,
                    onSelectFolder = onSelectedFolderChange,
                    onAddFolder = { showCreateFolder = true }, onFolderLongPress = { folderActionsFor = it },
                )
            }
            val recent = notebooks.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt }.take(3)
            if (recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "continue-reading") {
                    Column {
                        Text("Continue reading", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            recent.forEach { nb ->
                                ContinueReadingCard(
                                    repo = repo,
                                    notebook = nb,
                                    subjectColor = subjectColor(nb, folders),
                                    renderThumbnail = renderThumbnails,
                                    phoneLayout = phoneLayout,
                                    onOpen = { onContinueAtPosition(nb.id, nb.lastPageIndex) },
                                )
                            }
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "tag-row") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = selectedTagId == tag.id,
                            onClick = { selectedTagId = if (selectedTagId == tag.id) null else tag.id },
                            label = { Text("#${tag.name}", maxLines = 1) },
                            shape = CircleShape,
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "library-title") {
                Text("Your library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (notebooks.isEmpty() && !importing) item(span = { GridItemSpan(maxLineSpan) }, contentType = "empty-state") {
                EmptyLibrary(onNew = { showNew = true }, onImport = { pickPdf.launch(arrayOf("application/pdf")) })
            }
            if (notebooks.isNotEmpty() && visibleNotebooks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "filtered-empty-state") {
                Text("No notebooks match these filters.", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(sortedNotebooks, key = { it.id }, contentType = { "notebook-card" }) { nb ->
                NotebookCard(
                    repo = repo, notebook = nb, tags = tags, compact = compact,
                    modifier = Modifier.animateItem(),
                    subjectColor = subjectColor(nb, folders),
                    renderThumbnail = renderThumbnails,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onOpen = { if (nb.documentKind == "file") openStoredFile(nb) else onContinueAtPosition(nb.id, nb.lastPageIndex) },
                    onMore = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); actionsFor = nb },
                )
            }
        }
        }
    }

    // ---- sheets & dialogs
    if (showNew) ModalBottomSheet(onDismissRequest = { showNew = false }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SheetTitle("Import")
            SheetRow(Icons.Outlined.PictureAsPdf, "PDF", "Add one or more lecture notes and books") { showNew = false; pickPdf.launch(arrayOf("application/pdf")) }
            SheetRow(Icons.Outlined.Image, "Images", "Turn photos into notebook pages") { showNew = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            SheetRow(Icons.Outlined.DocumentScanner, "Scan", "Capture a multipage document") { showNew = false; startScanner() }
            SheetRow(Icons.Outlined.AttachFile, "Any file", "Keep a copy and open it in another app") { showNew = false; pickAnyFile.launch(arrayOf("*/*")) }
            SheetRow(Icons.Outlined.Description, "Blank notebook", "Plain, lined or grid pages") { showNew = false; showCreate = true }
            SheetRow(Icons.Outlined.Unarchive, "Import .notepad.zip", "A notebook exported from NotePad") { showNew = false; pickBundle.launch(arrayOf("application/zip", "application/octet-stream")) }
            Spacer(Modifier.height(24.dp))
        }
    }

    actionsFor?.let { nb ->
        val pages by repo.pages(nb.id).collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    nb.title, bookCoverColor(nb), pages.any { it.kind == PageKind.PDF },
                    Modifier.width(44.dp).aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)),
                    tags.filter { it.id in nb.tagIds }.take(2).map { it.name },
                )
                Spacer(Modifier.width(16.dp))
                Column { Text(nb.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = titleFontFamily(nb.title)), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(meta(nb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow(Icons.Outlined.GridView, "Browse pages", "Open the notebook page grid") {
                actionsFor = null
                onOpen(nb.id)
            }
            SheetRow(Icons.Outlined.FolderOpen, "Move to subject", "Choose a subject or create one") {
                actionsFor = null
                moveNotebookFor = nb
            }
            SheetRow(Icons.Outlined.Tag, "Tags", "Add or remove notebook tags") {
                actionsFor = null
                selectedTagIds = nb.tagIds
                newTagName = ""
                tagsForNotebook = nb
            }
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

    if (passwordUri != null) AlertDialog(
        onDismissRequest = { passwordUri = null; passwordFolderId = null; passwordTitle = null; passwordFolderPending = false },
        title = { Text("Password-protected PDF") },
        text = {
            OutlinedTextField(
                value = passwordEntry,
                onValueChange = { passwordEntry = it },
                label = { Text("PDF password") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(enabled = passwordEntry.isNotEmpty(), onClick = {
                val uri = passwordUri ?: return@TextButton
                importJob = scope.launch {
                    onImportingChanged(true)
                    try {
                        val id = importer.importAsNotebook(
                            uri,
                            if (passwordFolderPending) passwordFolderId else selectedFolderId.takeUnless { it == ALL_FOLDERS },
                            passwordTitle,
                            passwordEntry,
                        ) { importProgress = it }
                        passwordUri = null
                        passwordFolderId = null
                        passwordTitle = null
                        passwordFolderPending = false
                        onContinueAtPosition(id, 0)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (error is PdfPasswordIncorrectException) Toast.makeText(context, "Incorrect PDF password", Toast.LENGTH_LONG).show()
                        else Toast.makeText(context, error.message ?: "Could not open PDF", Toast.LENGTH_LONG).show()
                    } finally {
                        importProgress = null
                        onImportingChanged(false)
                    }
                }
            }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = { passwordUri = null; passwordFolderId = null; passwordTitle = null; passwordFolderPending = false }) { Text("Cancel") } },
    )

    val saveItems = pendingImportUris.ifEmpty { sharedItems }
    if (showSaveShared) ModalBottomSheet(onDismissRequest = {
        showSaveShared = false
        pendingImportUris = emptyList()
        pendingImportMimeType = null
    }) {
        SheetTitle("Save to NotePad")
        OutlinedTextField(
            value = sharedTitle,
            onValueChange = { sharedTitle = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Subject", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sharedFolderId == null, onClick = { sharedFolderId = null }, label = { Text("All") })
            folders.forEach { folder ->
                FilterChip(selected = sharedFolderId == folder.id, onClick = { sharedFolderId = folder.id }, label = { Text(folder.name) })
            }
        }
        Button(
            onClick = {
                val folder = sharedFolderId
                val itemsToSave = saveItems
                val mimeTypeForImport = pendingImportMimeType
                showSaveShared = false
                pendingImportUris = emptyList()
                pendingImportMimeType = null
                importJob = scope.launch {
                    onImportingChanged(true)
                    try {
                        var firstOpenedId: String? = null
                        val imageUris = itemsToSave.filter { uri ->
                            val name = ImportStorage(context).displayName(uri)
                            context.contentResolver.getType(uri)?.startsWith("image/") == true ||
                                (uri in sharedItems && sharedMimeType?.startsWith("image/") == true) ||
                                (uri in itemsToSave && mimeTypeForImport?.startsWith("image/") == true) ||
                                name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")
                        }
                        if (imageUris.isNotEmpty()) {
                            val images = mutableListOf<ImportedImageContent>()
                            var committed = false
                            try {
                                imageUris.forEachIndexed { index, uri ->
                                    importProgress = ImportProgress("image ${index + 1} of ${imageUris.size}", (index * 100 / imageUris.size).coerceIn(0, 99))
                                    val image = pageImageImporter.import(uri)
                                    val fit = PageImageImporter.fitToA4(image.widthPx, image.heightPx)
                                    images += ImportedImageContent(image.asset, fit[0], fit[1], fit[2], fit[3])
                                }
                                val id = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    repo.createImageNotebook(sharedTitle.ifBlank { "Image notes" }, images, folder).also { committed = true }
                                }
                                if (firstOpenedId == null) firstOpenedId = id
                            } catch (error: Throwable) {
                                if (!committed) images.forEach { File(it.asset.localPath).delete() }
                                throw error
                            }
                        }
                        itemsToSave.filterNot { it in imageUris }.forEachIndexed { index, uri ->
                            if (passwordUri != null) return@forEachIndexed
                            val name = ImportStorage(context).displayName(uri).ifBlank { "Shared file" }
                            val mime = context.contentResolver.getType(uri).orEmpty()
                                .ifBlank {
                                    sharedMimeType.takeIf { uri in sharedItems }
                                        ?: mimeTypeForImport.takeIf { uri in itemsToSave }
                                        .orEmpty()
                                }
                            val title = sharedTitle.takeIf { index == 0 && it.isNotBlank() }
                            val id = try {
                                when {
                                    mime == "application/pdf" || name.endsWith(".pdf", true) ->
                                        importer.importAsNotebook(uri, folder, title) { importProgress = it }
                                    else -> fileImporter.import(uri, folder, title) { importProgress = it }
                                }
                            } catch (error: PdfPasswordRequiredException) {
                                passwordUri = uri
                                passwordEntry = ""
                                passwordFolderId = folder
                                passwordTitle = title
                                passwordFolderPending = true
                                return@forEachIndexed
                            }
                            if (firstOpenedId == null && (mime == "application/pdf" || name.endsWith(".pdf", true))) firstOpenedId = id
                        }
                        if (passwordUri == null) {
                            firstOpenedId?.let { onContinueAtPosition(it, 0) }
                        }
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Toast.makeText(context, error.message ?: "Could not save shared items", Toast.LENGTH_LONG).show()
                    } finally {
                        importProgress = null
                        onImportingChanged(false)
                    }
                }
            },
            enabled = sharedTitle.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        ) { Text("Save ${saveItems.size} item${if (saveItems.size == 1) "" else "s"}") }
        Spacer(Modifier.height(24.dp))
    }

    if (showCreate) NameDialog(title = "New notebook", initial = "", confirm = "Create", onDismiss = { showCreate = false }) { name ->
        scope.launch {
            val id = repo.createNotebook(name, null, selectedFolderId.takeUnless { it == ALL_FOLDERS })
            repo.addPage(id, PageKind.BLANK)
            showCreate = false
            onOpen(id)
        }
    }
    if (showCreateFolder) SubjectDialog(
        title = "New subject", initial = "", initialColor = null,
        onDismiss = { showCreateFolder = false; newFolderForNotebook = null },
    ) { name, color ->
        scope.launch {
            val id = repo.createFolder(name, color)
            val notebook = newFolderForNotebook
            if (notebook == null) {
                onSelectedFolderChange(id)
            } else {
                repo.moveNotebookToFolder(notebook.id, id)
            }
            newFolderForNotebook = null
            showCreateFolder = false
        }
    }
    folderActionsFor?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderActionsFor = null },
            title = { Text(folder.name) },
            text = { Text("Subject actions") },
            confirmButton = {
                TextButton(onClick = { renameFolderFor = folder; folderActionsFor = null }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteFolderFor = folder; folderActionsFor = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                    TextButton(onClick = { folderActionsFor = null }) { Text("Cancel") }
                }
            },
        )
    }
    renameFolderFor?.let { folder ->
        SubjectDialog(title = "Rename subject", initial = folder.name, initialColor = folder.color, onDismiss = { renameFolderFor = null }) { name, color ->
            scope.launch { repo.renameFolder(folder.id, name, color) }
            renameFolderFor = null
        }
    }
    deleteFolderFor?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderFor = null },
            title = { Text("Delete “${folder.name}”?") },
            text = { Text("The notebooks in this subject will be kept and moved to All.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repo.deleteFolder(folder.id) }
                        if (selectedFolderId == folder.id) onSelectedFolderChange(ALL_FOLDERS)
                        deleteFolderFor = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFolderFor = null }) { Text("Cancel") } },
        )
    }
    moveNotebookFor?.let { notebook ->
        AlertDialog(
            onDismissRequest = { moveNotebookFor = null },
            title = { Text("Move to subject") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            scope.launch { repo.moveNotebookToFolder(notebook.id, null) }
                            moveNotebookFor = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("No subject", Modifier.fillMaxWidth()) }
                    folders.forEach { folder ->
                        TextButton(
                            onClick = {
                                scope.launch { repo.moveNotebookToFolder(notebook.id, folder.id) }
                                moveNotebookFor = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(folder.name, Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = {
                            moveNotebookFor = null
                            newFolderForNotebook = notebook
                            showCreateFolder = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("New subject…", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = { TextButton(onClick = { moveNotebookFor = null }) { Text("Cancel") } },
        )
    }
    tagsForNotebook?.let { notebook ->
        AlertDialog(
            onDismissRequest = { tagsForNotebook = null },
            title = { Text("Tags") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (tags.isEmpty()) {
                        Text("No tags yet. Add one below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    tags.forEach { tag ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = tag.id in selectedTagIds,
                                onCheckedChange = { checked ->
                                    selectedTagIds = if (checked) selectedTagIds + tag.id else selectedTagIds - tag.id
                                },
                            )
                            Text(tag.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTagName,
                            onValueChange = { newTagName = it },
                            singleLine = true,
                            label = { Text("New tag") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val name = newTagName.trim()
                                if (name.isNotEmpty()) scope.launch {
                                    val id = repo.createTag(name)
                                    selectedTagIds = selectedTagIds + id
                                    newTagName = ""
                                }
                            },
                            enabled = newTagName.isNotBlank(),
                        ) { Text("Add") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.setNotebookTags(notebook.id, selectedTagIds) }
                    tagsForNotebook = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { tagsForNotebook = null }) { Text("Cancel") } },
        )
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
    if (showAppearance) AppearanceSheet(themeMode, dynamicColor, onThemeChange, onDynamicChange) { showAppearance = false }
}

/* ---------------------------------------------------------------- pieces */

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppearanceSheet(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SheetTitle("Appearance")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeMode.entries.forEach { mode ->
                val selected = themeMode == mode
                Surface(
                    onClick = { onThemeChange(mode) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ThemeSwatch(mode)
                        Spacer(Modifier.height(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        ListItem(
            headlineContent = { Text("Use wallpaper colours") },
            supportingContent = { Text("Material You accent from your wallpaper") },
            trailingContent = { Switch(checked = dynamicColor, onCheckedChange = onDynamicChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibraryHeader(
    count: Int,
    compact: Boolean,
    importing: Boolean,
    progress: ImportProgress?,
    onCancelImport: () -> Unit,
    librarySort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    tabletLayout: Boolean,
    showAccountButton: Boolean,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
    onAppearance: () -> Unit,
    onNew: () -> Unit,
    folders: List<Folder>,
    selectedFolderId: String,
    onSelectFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    onFolderLongPress: (Folder) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    val phoneLayout = compact || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp)) {
        if (phoneLayout) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Library", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif))
                    Text(if (importing) "Importing…" else "$count items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { sortExpanded = true }) { Icon(Icons.Outlined.Sort, "Sort") }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf(LibrarySort.RECENT to "Recent", LibrarySort.NAME to "Name", LibrarySort.DATE_ADDED to "Date added").forEach { (sort, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { onSortChange(sort); sortExpanded = false })
                        }
                    }
                }
                IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Import") }
                IconButton(onClick = onAppearance) { Icon(Icons.Outlined.Palette, "Appearance") }
                if (showAccountButton) IconButton(onClick = onAccount) { Icon(Icons.Outlined.AccountCircle, "Sync & account") }
            }
            Spacer(Modifier.height(10.dp))
            LibrarySearchButton(onSearch, Modifier.fillMaxWidth())
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 16.dp)) {
                Text(
                    "Library",
                    style = (if (phoneLayout) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
                        .copy(fontFamily = FontFamily.Serif),
                )
                Text(if (importing) "Importing…" else "$count items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LibrarySearchButton(onSearch, Modifier.weight(1f).widthIn(max = 360.dp))
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { sortExpanded = true }) { Icon(Icons.Outlined.Sort, "Sort") }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf(LibrarySort.RECENT to "Recent", LibrarySort.NAME to "Name", LibrarySort.DATE_ADDED to "Date added").forEach { (sort, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { onSortChange(sort); sortExpanded = false })
                    }
                }
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Import") }
            IconButton(onClick = onAppearance) { Icon(Icons.Outlined.Palette, "Appearance") }
            if (showAccountButton) IconButton(onClick = onAccount) { Icon(Icons.Outlined.AccountCircle, "Sync & account") }
        }
        Spacer(Modifier.height(8.dp))
        if (!tabletLayout) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedFolderId == ALL_FOLDERS,
                onClick = { onSelectFolder(ALL_FOLDERS) },
                label = { Text("All") },
                shape = CircleShape,
            )
            folders.forEach { folder ->
                val selected = selectedFolderId == folder.id
                Surface(
                    shape = CircleShape,
                    color = folder.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }?.let { if (selected) it else it.copy(alpha = 0.18f) }
                        ?: if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = null,
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelectFolder(folder.id) },
                        onLongClick = { onFolderLongPress(folder) },
                    ),
                ) {
                    Text(
                        folder.name,
                        Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = folder.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                            ?.takeIf { selected && android.graphics.Color.luminance(it.toArgb()) < 0.56f }?.let { Color.White }
                            ?: if (selected && folder.color != null) Color(0xFF292421) else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            FilterChip(
                selected = false,
                onClick = onAddFolder,
                label = { Text("+ New subject") },
                shape = CircleShape,
            )
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(importing || progress != null, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                if (progress != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(importProgressLabel(progress.filename, progress.percent), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = onCancelImport, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Cancel import") }
                }
                LinearProgressIndicator(
                    progress = { progress?.percent?.div(100f) ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LibrarySearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                "Search books and notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySidebar(
    repo: NotePadRepository,
    selectedFolderId: String,
    onSelectFolder: (String) -> Unit,
    screen: Screen,
    onNavigate: (Screen) -> Unit,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
) {
    val folders by repo.folders().collectAsState(initial = emptyList())
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val counts = remember(notebooks) { notebooks.groupingBy { it.folderId }.eachCount() }
    val scope = rememberCoroutineScope()
    var folderActionsFor by remember { mutableStateOf<Folder?>(null) }
    var renameFolderFor by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderFor by remember { mutableStateOf<Folder?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    val selectedLibrary = screen == Screen.Notebooks || screen is Screen.Pages || screen is Screen.Read

    Column(
        Modifier.width(264.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            "NotePad",
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(8.dp))
        SidebarDestination("Library", Icons.Outlined.Book, selectedLibrary) { onNavigate(Screen.Notebooks) }
        SidebarDestination("Search", Icons.Outlined.Search, screen == Screen.Search) { onNavigate(Screen.Search) }
        SidebarDestination("Sync", Icons.Outlined.Cloud, screen == Screen.Account) { onNavigate(Screen.Account) }
        Spacer(Modifier.height(20.dp))
        Text(
            "Subjects",
            Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SidebarFolderRow(
                name = "All",
                count = notebooks.size,
                selected = selectedFolderId == ALL_FOLDERS,
                onClick = { onSelectFolder(ALL_FOLDERS) },
            )
            folders.forEach { folder ->
                val color = runCatching {
                    Color(android.graphics.Color.parseColor(folder.color ?: "#75885C"))
                }.getOrDefault(Color(0xFF75885C))
                SidebarFolderRow(
                    name = folder.name,
                    count = counts[folder.id] ?: 0,
                    selected = selectedFolderId == folder.id,
                    dotColor = color,
                    onClick = { onSelectFolder(folder.id) },
                    onLongClick = { folderActionsFor = folder },
                )
            }
            SidebarActionRow(Icons.Default.Add, "+ New subject") { showCreateFolder = true }
        }
        Spacer(Modifier.height(8.dp))
        SidebarActionRow(Icons.Outlined.Palette, "Appearance") { showAppearance = true }
    }

    if (showCreateFolder) SubjectDialog(
        title = "New subject",
        initial = "",
        initialColor = null,
        onDismiss = { showCreateFolder = false },
    ) { name, color ->
        scope.launch {
            onSelectFolder(repo.createFolder(name, color))
            showCreateFolder = false
        }
    }
    folderActionsFor?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderActionsFor = null },
            title = { Text(folder.name) },
            text = { Text("Subject actions") },
            confirmButton = {
                TextButton(onClick = { renameFolderFor = folder; folderActionsFor = null }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { deleteFolderFor = folder; folderActionsFor = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                    TextButton(onClick = { folderActionsFor = null }) { Text("Cancel") }
                }
            },
        )
    }
    renameFolderFor?.let { folder ->
        SubjectDialog(
            title = "Rename subject",
            initial = folder.name,
            initialColor = folder.color,
            onDismiss = { renameFolderFor = null },
        ) { name, color ->
            scope.launch { repo.renameFolder(folder.id, name, color) }
            renameFolderFor = null
        }
    }
    deleteFolderFor?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderFor = null },
            title = { Text("Delete “${folder.name}”?") },
            text = { Text("The notebooks in this subject will be kept and moved to All.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repo.deleteFolder(folder.id) }
                        if (selectedFolderId == folder.id) onSelectFolder(ALL_FOLDERS)
                        deleteFolderFor = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFolderFor = null }) { Text("Cancel") } },
        )
    }
    if (showAppearance) {
        AppearanceSheet(themeMode, dynamicColor, onThemeChange, onDynamicChange) { showAppearance = false }
    }
}

@Composable
private fun SidebarDestination(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = contentColor)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarFolderRow(
    name: String,
    count: Int,
    selected: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(22.dp))
        }
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SidebarActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ContinueReadingCard(
    repo: NotePadRepository,
    notebook: Notebook,
    subjectColor: Color,
    renderThumbnail: Boolean,
    phoneLayout: Boolean,
    onOpen: () -> Unit,
) {
    val pages by repo.pages(notebook.id).collectAsState(initial = emptyList())
    val page = pages.getOrNull(notebook.lastPageIndex)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        tween(Motion.press, easing = Motion.emphasized),
        label = "continue-press",
    )
    Surface(
        modifier = Modifier.width(if (phoneLayout) 280.dp else 360.dp).height(150.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (phoneLayout) 92.dp else 120.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
            ) {
                PagePreview(
                    repo = repo,
                    page = page,
                    paperColor = notebook.coverColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                        ?: MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize(),
                    renderEnabled = renderThumbnail,
                    cropScale = 2.2f,
                    cropToContent = true,
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.035f)))
            }
            Column(Modifier.fillMaxHeight().weight(1f).padding(horizontal = 12.dp, vertical = 14.dp)) {
                Text(notebook.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = titleFontFamily(notebook.title)), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Page ${notebook.lastPageIndex + 1} of ${notebook.pageCount.coerceAtLeast(1)} · ${recentlyOpenedLabel(notebook.lastOpenedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                LibraryProgressTrack(
                    ((notebook.lastPageIndex + 1f) / notebook.pageCount.coerceAtLeast(1)).coerceIn(0f, 1f),
                    subjectColor,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LibraryProgressTrack(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(CircleShape).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

private fun recentlyOpenedLabel(openedAt: Long?): String {
    val elapsed = (System.currentTimeMillis() - (openedAt ?: System.currentTimeMillis())).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NotebookCard(
    repo: NotePadRepository,
    notebook: Notebook,
    tags: List<Tag>,
    compact: Boolean,
    modifier: Modifier,
    subjectColor: Color,
    renderThumbnail: Boolean,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val pages by repo.pages(notebook.id).collectAsState(initial = emptyList())
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        tween(Motion.press, easing = Motion.emphasized),
        label = "press",
    )
    val cover = remember(notebook.id, notebook.coverColor) { bookCoverColor(notebook) }
    val tagNames = remember(tags, notebook.tagIds) { tags.filter { it.id in notebook.tagIds }.take(2).map { it.name } }
    val coverAspect = pages.firstOrNull()?.let { (it.displayWidthPt / it.displayHeightPt).coerceIn(0.58f, 1.3f) } ?: 0.72f
    val radius = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState("notebook-cover-${notebook.id}"),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> tween(Motion.shared, easing = Motion.emphasized) },
            )
        }
    } else Modifier
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(coverAspect)
                .shadow(4.dp, radius, spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(radius)
                .then(sharedModifier)
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onOpen, onLongClick = onMore),
        ) {
            when {
                notebook.documentKind == "file" -> FileCover(notebook, Modifier.fillMaxSize())
                pages.any { it.kind == PageKind.PDF } -> Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(start = 2.dp, top = 4.dp).background(Color(0xFFE7E1D7), radius))
                    Box(Modifier.fillMaxSize().padding(start = 1.dp, top = 2.dp).background(Color(0xFFF3F0E9), radius))
                    PagePreview(repo, pages.firstOrNull(), Color(0xFFFFFEFA), Modifier.fillMaxSize().padding(end = 2.dp, bottom = 4.dp), renderEnabled = renderThumbnail, cropScale = 2.2f)
                    Box(Modifier.fillMaxHeight().width(9.dp).background(Color.Black.copy(alpha = 0.12f)))
                    Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), color = Color(0xFFB4532A), shape = RoundedCornerShape(4.dp)) {
                        Text("PDF", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> BookCover(notebook.title, cover, false, Modifier.fillMaxSize(), tagNames)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(notebook.title, style = MaterialTheme.typography.titleSmall.copy(fontFamily = titleFontFamily(notebook.title)), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(subjectColor))
                    Spacer(Modifier.width(6.dp))
                    Text(meta(notebook), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (notebook.lastOpenedAt != null && notebook.pageCount > 0) {
                    Text(progressLabel(notebook.lastPageIndex, notebook.pageCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LibraryProgressTrack(
                        ((notebook.lastPageIndex + 1f) / notebook.pageCount).coerceIn(0f, 1f),
                        subjectColor,
                        Modifier.fillMaxWidth().padding(top = 5.dp),
                    )
                }
            }
            if (!compact) IconButton(onClick = onMore, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun subjectColor(notebook: Notebook, folders: List<Folder>): Color {
    val hex = folders.firstOrNull { it.id == notebook.folderId }?.color ?: return MaterialTheme.colorScheme.primary
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF75885C))
}

@Composable
private fun FileCover(notebook: Notebook, modifier: Modifier = Modifier) {
    val coverColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val titleColor = accessibleTextColor(coverColor, MaterialTheme.colorScheme.onSurface)
    val badgeColor = MaterialTheme.colorScheme.primaryContainer
    val badgeTextColor = accessibleTextColor(badgeColor, MaterialTheme.colorScheme.onPrimaryContainer)
    Box(modifier.background(coverColor)) {
        Text(notebook.originalFilename ?: notebook.title, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Surface(Modifier.align(Alignment.Center).padding(top = 12.dp), color = badgeColor, shape = RoundedCornerShape(18.dp)) {
            Text(
                fileTypeBadge(notebook.fileMimeType, notebook.originalFilename),
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = badgeTextColor,
            )
        }
    }
}

@Composable
private fun BookCover(title: String, color: Color, isPdf: Boolean, modifier: Modifier = Modifier, tagNames: List<String> = emptyList()) {
    val spine = Color(color.red * 0.62f, color.green * 0.62f, color.blue * 0.62f)
    val titleColor = if (android.graphics.Color.luminance(color.toArgb()) < 0.55f) {
        Color.White.copy(alpha = 0.95f)
    } else {
        Color(0xFF2B2620)
    }
    Box(modifier.background(color)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val spineWidth = 10.dp.toPx()
            drawRect(spine, size = androidx.compose.ui.geometry.Size(spineWidth, size.height))
            val highlightX = spineWidth + 0.5.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.12f), Offset(highlightX, 0f), Offset(highlightX, size.height), 1.dp.toPx())
        }
        Text(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, top = 20.dp, end = if (isPdf) 56.dp else 16.dp),
            color = titleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = titleFontFamily(title), fontWeight = FontWeight.SemiBold),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (tagNames.isNotEmpty()) {
            Text(
                tagNames.joinToString("  ") { "#$it" },
                Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                color = titleColor.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
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

private val subjectColors = listOf("#B96C5C", "#C48C50", "#75885C", "#4F8278", "#62809A", "#7E7195", "#A66F82", "#84796B")

@Composable
private fun SubjectDialog(
    title: String,
    initial: String,
    initialColor: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    var color by remember(initialColor) { mutableStateOf(initialColor ?: subjectColors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Subject name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    subjectColors.forEach { hex ->
                        val selected = color.equals(hex, ignoreCase = true)
                        val swatch = Color(android.graphics.Color.parseColor(hex))
                        Box(
                            Modifier.size(if (selected) 34.dp else 28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), color) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun paperOf(nb: Notebook): Color = nb.coverColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.White
private fun meta(nb: Notebook) = "${nb.pageCount} page${if (nb.pageCount == 1) "" else "s"} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nb.updatedAt))}"
