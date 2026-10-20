package com.hashim.notepad.ui

import android.net.Uri
import android.widget.Toast
import com.hashim.notepad.InboundShareEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.pdf.PdfImporter
import com.hashim.notepad.pdf.ImportStorage
import com.hashim.notepad.pdf.FileImporter
import com.hashim.notepad.export.BundleFileClassifier
import com.hashim.notepad.export.ImportFileKind
import com.hashim.notepad.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Notebooks : Screen
    data class Pages(val notebookId: String) : Screen
    data class Editor(val notebookId: String, val pageId: String, val returnToRead: Boolean = false) : Screen
    data class Read(val notebookId: String, val pageId: String, val searchQuery: String? = null) : Screen
    data class Split(val notebookId: String, val pageId: String, val searchQuery: String? = null) : Screen
    data object Search : Screen
    data object Account : Screen
}

internal fun screenContentKey(screen: Screen): Any =
    when (screen) {
        is Screen.Editor -> "editor:${screen.notebookId}"
        is Screen.Read -> "read:${screen.notebookId}"
        is Screen.Split -> "split:${screen.notebookId}"
        else -> screen
    }

private data class SplitRequest(val notebookId: String, val pageId: String)

private val screenSaver = listSaver<Screen, String>(
    save = { screen ->
        when (screen) {
            Screen.Notebooks -> listOf("notebooks")
            is Screen.Pages -> listOf("pages", screen.notebookId)
            is Screen.Editor -> listOf("editor", screen.notebookId, screen.pageId, screen.returnToRead.toString())
            is Screen.Read -> listOf("read", screen.notebookId, screen.pageId, screen.searchQuery.orEmpty())
            is Screen.Split -> listOf("split", screen.notebookId, screen.pageId, screen.searchQuery.orEmpty())
            Screen.Search -> listOf("search")
            Screen.Account -> listOf("account")
        }
    },
    restore = { state ->
        when (state.firstOrNull()) {
            "pages" -> state.getOrNull(1)?.let(Screen::Pages) ?: Screen.Notebooks
            "editor" -> {
                val notebookId = state.getOrNull(1)
                val pageId = state.getOrNull(2)
                if (notebookId == null || pageId == null) Screen.Notebooks
                else Screen.Editor(notebookId, pageId, state.getOrNull(3).toBoolean())
            }
            "read" -> {
                val notebookId = state.getOrNull(1)
                val pageId = state.getOrNull(2)
                if (notebookId == null || pageId == null) Screen.Notebooks
                else Screen.Read(notebookId, pageId, state.getOrNull(3)?.takeIf(String::isNotEmpty))
            }
            "split" -> {
                val notebookId = state.getOrNull(1)
                val pageId = state.getOrNull(2)
                if (notebookId == null || pageId == null) Screen.Notebooks
                else Screen.Split(notebookId, pageId, state.getOrNull(3)?.takeIf(String::isNotEmpty))
            }
            "search" -> Screen.Search
            "account" -> Screen.Account
            else -> Screen.Notebooks
        }
    },
)

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun NotePadApp(
    initialPdf: Uri?,
    initialAuthCallback: Uri?,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    initialShare: InboundShareEvent? = null,
    onShareConsumed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { NotePadRepository.get(context) }
    val libraryPrefs = remember(context) { context.getSharedPreferences("notepad", 0) }
    var selectedFolderId by rememberSaveable {
        mutableStateOf(libraryPrefs.getString("library_folder", ALL_FOLDERS) ?: ALL_FOLDERS)
    }
    LaunchedEffect(selectedFolderId) { libraryPrefs.edit().putString("library_folder", selectedFolderId).apply() }
    val appScope = rememberCoroutineScope()
    var newRequest by remember { mutableIntStateOf(0) }
    var importRequest by remember { mutableIntStateOf(0) }
    var folderRequest by remember { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    val importer = remember { PdfImporter(context, repo) }
    val fileImporter = remember { FileImporter(context, repo) }
    val exporter = remember { com.hashim.notepad.export.PdfExporter(context, repo) }
    val recognizer = remember { com.hashim.notepad.recognition.InkRecognizer() }
    val bundles = remember { com.hashim.notepad.export.BundleCodec(context, repo) }
    var screen by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<Screen>(Screen.Notebooks) }
    var splitRequest by remember { mutableStateOf<SplitRequest?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }

    suspend fun destinationForPage(notebookId: String, pageId: String, searchQuery: String? = null): Screen {
        if (repo.splitPair(notebookId) != null) {
            val page = repo.page(pageId)
            return Screen.Split(
                notebookId,
                pageId,
                searchQuery.takeIf { page?.kind == com.hashim.notepad.data.PageKind.PDF },
            )
        }
        val page = repo.page(pageId) ?: return Screen.Pages(notebookId)
        return if (page.kind == com.hashim.notepad.data.PageKind.PDF) {
            Screen.Read(notebookId, pageId, searchQuery)
        } else {
            Screen.Editor(notebookId, pageId)
        }
    }

    fun continueNotebook(notebookId: String, pageIndex: Int) {
        appScope.launch {
            val targetPage = repo.pages(notebookId).first().getOrNull(pageIndex)
            screen = targetPage?.let { destinationForPage(notebookId, it.id) } ?: Screen.Pages(notebookId)
        }
    }

    fun openPage(notebookId: String, pageId: String, searchQuery: String? = null) {
        appScope.launch { screen = destinationForPage(notebookId, pageId, searchQuery) }
    }

    fun openSinglePage(notebookId: String, pageId: String) {
        appScope.launch {
            val page = repo.page(pageId)
            screen = if (page?.kind == com.hashim.notepad.data.PageKind.PDF) {
                Screen.Read(notebookId, pageId)
            } else {
                Screen.Editor(notebookId, pageId)
            }
        }
    }

    LaunchedEffect(repo) {
        withContext(Dispatchers.IO) {
            com.hashim.notepad.pdf.PdfTextCache.clearStaleCacheVersions(context)
            val referenced = repo.helper.readableDatabase.rawQuery("select local_path from assets", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            ImportStorage.cleanOrphans(context, referenced)
        }
    }

    LaunchedEffect(initialPdf) {
        val uri = initialPdf ?: return@LaunchedEffect
        importing = true
        runCatching {
            val name = ImportStorage(context).displayName(uri)
            val kind = context.contentResolver.openInputStream(uri)!!.use { BundleFileClassifier.classify(name, it) }
            val id = when (kind) {
                ImportFileKind.NOTEPAD_BUNDLE -> context.contentResolver.openInputStream(uri)!!.use { bundles.import(it) }
                ImportFileKind.PDF -> importer.importAsNotebook(uri)
                ImportFileKind.ZIP, ImportFileKind.OTHER -> fileImporter.import(uri, null, null)
            }
            kind to id
        }.onSuccess { (kind, id) ->
            screen = if (kind == ImportFileKind.PDF || kind == ImportFileKind.NOTEPAD_BUNDLE) Screen.Pages(id)
            else Screen.Notebooks
        }.onFailure { android.util.Log.e("NotePad", "File import failed", it); Toast.makeText(context, "Could not import file: ${it.message}", Toast.LENGTH_LONG).show() }
        importing = false
    }

    LaunchedEffect(initialShare?.id) {
        if (initialShare != null) screen = Screen.Notebooks
    }

    LaunchedEffect(initialAuthCallback) {
        val callback = initialAuthCallback ?: return@LaunchedEffect
        screen = Screen.Account
        val parameters = parseAuthCallback(callback.encodedFragment.orEmpty())
        val error = parameters["error_description"] ?: parameters["error"]
        if (!error.isNullOrBlank()) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val accessToken = parameters["access_token"]
        val refreshToken = parameters["refresh_token"]
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            Toast.makeText(context, "The confirmation link is missing session details", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val expiresIn = parameters["expires_in"]?.toLongOrNull() ?: 3600L
        runCatching {
            SyncManager.get(context).completeEmailConfirmation(accessToken, refreshToken, expiresIn)
        }.onFailure {
            Toast.makeText(context, it.message ?: "Could not confirm the account", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = screen != Screen.Notebooks) {
        screen = when (val s = screen) {
            is Screen.Editor -> if (s.returnToRead) Screen.Read(s.notebookId, s.pageId) else Screen.Pages(s.notebookId)
            is Screen.Read -> Screen.Notebooks
            is Screen.Split -> {
                openSinglePage(s.notebookId, s.pageId)
                s
            }
            else -> Screen.Notebooks
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showSidebar = maxWidth >= 840.dp &&
            (screen == Screen.Notebooks || screen == Screen.Search || screen == Screen.Account || screen is Screen.Pages)
        val showNavigationBar = maxWidth < 600.dp &&
            (screen == Screen.Notebooks || screen == Screen.Search || screen == Screen.Account)
        val screenContent: @Composable () -> Unit = {
            SharedTransitionLayout {
                val sharedScope = this
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                contentKey = ::screenContentKey,
                transitionSpec = {
                    val forward = depth(targetState) >= depth(initialState)
                    val openingNotebook = (
                        targetState is Screen.Pages || targetState is Screen.Editor || targetState is Screen.Read
                    ) && initialState is Screen.Notebooks
                    val enter = if (openingNotebook) {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.shared, easing = Motion.emphasized)) +
                            androidx.compose.animation.scaleIn(
                                initialScale = 0.96f,
                                animationSpec = androidx.compose.animation.core.tween(Motion.shared, easing = Motion.emphasized),
                            )
                    } else {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.medium, delayMillis = 40, easing = Motion.emphasizedDecelerate)) +
                            androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(Motion.long, easing = Motion.emphasized)) { if (forward) it / 10 else -it / 10 }
                    }
                    val exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.short, easing = Motion.emphasizedAccelerate)) +
                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(Motion.long, easing = Motion.emphasized)) { if (forward) -it / 10 else it / 10 }
                    (enter togetherWith exit).using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "screen",
            ) { s ->
                val visibilityScope = this
                when (s) {
                    Screen.Notebooks -> NotebooksScreen(
                        repo = repo, importer = importer, bundles = bundles, exporter = exporter, importing = importing,
                        sharedItems = initialShare?.share?.uris.orEmpty(),
                        sharedMimeType = initialShare?.share?.mimeType,
                        sharedIntentId = initialShare?.id,
                        onSharedIntentConsumed = onShareConsumed,
                        onImportingChanged = { importing = it },
                        onOpen = { screen = Screen.Pages(it) },
                        onContinueAtPosition = { notebookId, pageIndex ->
                            appScope.launch {
                                val targetPage = repo.pages(notebookId).first().getOrNull(pageIndex)
                                screen = targetPage?.let { destinationForPage(notebookId, it.id) } ?: Screen.Pages(notebookId)
                            }
                        },
                        onSearch = { screen = Screen.Search }, onAccount = { screen = Screen.Account },
                        selectedFolderId = selectedFolderId,
                        onSelectedFolderChange = { selectedFolderId = it },
                        themeMode = themeMode, dynamicColor = dynamicColor, onThemeChange = onThemeChange, onDynamicChange = onDynamicChange,
                        showAccountButton = !showSidebar && !showNavigationBar,
                        sharedTransitionScope = sharedScope,
                        animatedVisibilityScope = visibilityScope,
                        onMenu = if (showNavigationBar) { { drawerOpen = true } } else null,
                        externalNewRequest = newRequest,
                        externalImportRequest = importRequest,
                        externalFolderRequest = folderRequest,
                    )
                    Screen.Account -> AccountScreen(onBack = { screen = Screen.Notebooks })
                    Screen.Search -> SearchScreen(repo = repo, onBack = { screen = Screen.Notebooks }, onOpenPage = ::openPage)
                    is Screen.Pages -> {
                        val sharedModifier = with(sharedScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("notebook-cover-${s.notebookId}"),
                                animatedVisibilityScope = visibilityScope,
                                boundsTransform = { _, _ ->
                                    androidx.compose.animation.core.tween(Motion.shared, easing = Motion.emphasized)
                                },
                            )
                        }
                        Box(sharedModifier.fillMaxSize()) {
                            PagesScreen(
                                repo = repo, notebookId = s.notebookId, exporter = exporter, recognizer = recognizer, bundles = bundles,
                                onBack = { screen = Screen.Notebooks },
                                onOpenPage = { openPage(s.notebookId, it) },
                            )
                        }
                    }
                    is Screen.Editor -> {
                        val sharedModifier = with(sharedScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("notebook-cover-${s.notebookId}"),
                                animatedVisibilityScope = visibilityScope,
                                boundsTransform = { _, _ ->
                                    androidx.compose.animation.core.tween(Motion.shared, easing = Motion.emphasized)
                                },
                            )
                        }
                        Box(sharedModifier.fillMaxSize()) {
                            EditorScreen(
                                repo = repo, notebookId = s.notebookId, pageId = s.pageId, recognizer = recognizer,
                                onBack = { screen = if (s.returnToRead) Screen.Read(s.notebookId, s.pageId) else Screen.Pages(s.notebookId) },
                                onNavigateToPage = { screen = Screen.Editor(s.notebookId, it, s.returnToRead) },
                                onOpenSideBySide = { notebookId, pageId -> splitRequest = SplitRequest(notebookId, pageId) },
                            )
                        }
                    }
                    is Screen.Read -> {
                        val sharedModifier = with(sharedScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("notebook-cover-${s.notebookId}"),
                                animatedVisibilityScope = visibilityScope,
                                boundsTransform = { _, _ ->
                                    androidx.compose.animation.core.tween(Motion.shared, easing = Motion.emphasized)
                                },
                            )
                        }
                        Box(sharedModifier.fillMaxSize()) {
                            PdfReadScreen(
                                repo = repo,
                                notebookId = s.notebookId,
                                startPageId = s.pageId,
                                onBack = { screen = Screen.Notebooks },
                                onWrite = { screen = Screen.Editor(s.notebookId, it, returnToRead = true) },
                                onOpenSideBySide = { notebookId, pageId -> splitRequest = SplitRequest(notebookId, pageId) },
                                initialSearchQuery = s.searchQuery,
                                onInitialSearchApplied = { query ->
                                    if (screen == s && s.searchQuery == query) screen = s.copy(searchQuery = null)
                                },
                            )
                        }
                    }
                    is Screen.Split -> SplitViewScreen(
                        repo = repo,
                        recognizer = recognizer,
                        persistScope = appScope,
                        openedNotebookId = s.notebookId,
                        openedPageId = s.pageId,
                        initialSearchQuery = s.searchQuery,
                        onInitialSearchApplied = { query ->
                            if (screen == s && s.searchQuery == query) screen = s.copy(searchQuery = null)
                        },
                        onClose = ::openSinglePage,
                    )
                }
            }
            }
        }

        val sidebarFolders by repo.folders().collectAsState(initial = emptyList())
        val sidebarNotebooks by repo.notebooks().collectAsState(initial = emptyList())
        val folderCounts = remember(sidebarNotebooks) { sidebarNotebooks.groupingBy { it.folderId }.eachCount() }
        val recents = remember(sidebarNotebooks) {
            sidebarNotebooks.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt }.take(8).map { SidebarRecent(it.id, it.title, it.lastPageIndex) }
        }
        val sidebar: @Composable (collapsed: Boolean, onToggle: () -> Unit, width: androidx.compose.ui.unit.Dp, afterNavigate: () -> Unit) -> Unit =
            { collapsed, onToggle, width, afterNavigate ->
                AppSidebar(
                    screen = screen,
                    onNavigate = { screen = it; afterNavigate() },
                    folders = sidebarFolders, folderCounts = folderCounts,
                    selectedFolderId = selectedFolderId, onSelectFolder = { selectedFolderId = it },
                    onAddFolder = { screen = Screen.Notebooks; folderRequest++; afterNavigate() },
                    recents = recents, onOpenRecent = { continueNotebook(it.id, it.pageIndex); afterNavigate() },
                    onNew = { screen = Screen.Notebooks; newRequest++; afterNavigate() },
                    onImport = { screen = Screen.Notebooks; importRequest++; afterNavigate() },
                    onAppearance = { showAppearance = true; afterNavigate() },
                    collapsed = collapsed, onToggleCollapsed = onToggle,
                    expandedWidth = width,
                )
            }
        if (showSidebar) {
            var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxSize()) {
                sidebar(sidebarCollapsed, { sidebarCollapsed = !sidebarCollapsed }, 264.dp) {}
                Box(Modifier.weight(1f).fillMaxSize()) {
                    screenContent()
                }
            }
        } else if (showNavigationBar) {
            val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
            LaunchedEffect(drawerOpen) { if (drawerOpen) { drawerState.open(); drawerOpen = false } }
            val closeDrawer: () -> Unit = { appScope.launch { drawerState.close() } }
            androidx.compose.material3.ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f),
                drawerContent = {
                    androidx.compose.material3.ModalDrawerSheet(
                        modifier = Modifier.width(300.dp),
                        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                        drawerContainerColor = NotePadTheme.tokens.bg,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    ) { sidebar(false, closeDrawer, 300.dp, closeDrawer) }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    screenContent()
                    if (screen == Screen.Notebooks) ComposerSearch(
                        placeholder = "Search notes, PDFs and handwriting",
                        onSearch = { screen = Screen.Search },
                        onNew = { newRequest++ },
                        onImport = { importRequest++ },
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        } else {
            screenContent()
        }

        if (showAppearance) AppearanceSheet(themeMode, dynamicColor, onThemeChange, onDynamicChange) { showAppearance = false }

        splitRequest?.let { request ->
            SplitPairPickerSheet(
                repo = repo,
                primaryNotebookId = request.notebookId,
                onDismiss = { splitRequest = null },
                onSelectNotebook = { secondaryId ->
                    appScope.launch {
                        val notebooks = repo.notebooks().first()
                        val primary = notebooks.firstOrNull { it.id == request.notebookId }
                        val secondaryPage = repo.pages(secondaryId).first().firstOrNull()
                        if (primary != null && secondaryPage != null) {
                            repo.saveSplitPair(primary.id, secondaryId)
                            screen = Screen.Split(request.notebookId, request.pageId)
                        }
                        splitRequest = null
                    }
                },
                onNewLectureNotes = {
                    appScope.launch {
                        val primary = repo.notebooks().first().firstOrNull { it.id == request.notebookId }
                        if (primary != null) {
                            val notesId = repo.createNotebook("${primary.title} — notes", primary.coverColor, primary.folderId)
                            repo.setNotebookLanguage(notesId, primary.language)
                            repo.addPage(notesId, com.hashim.notepad.data.PageKind.BLANK)
                            repo.saveSplitPair(primary.id, notesId)
                            screen = Screen.Split(request.notebookId, request.pageId)
                        }
                        splitRequest = null
                    }
                },
            )
        }
    }
}

private fun depth(s: Screen) = when (s) { Screen.Notebooks -> 0; Screen.Search, Screen.Account -> 1; is Screen.Pages -> 1; is Screen.Editor, is Screen.Read, is Screen.Split -> 2 }

private fun parseAuthCallback(fragment: String): Map<String, String> =
    fragment.split('&').mapNotNull { parameter ->
        val separator = parameter.indexOf('=')
        if (separator < 0) return@mapNotNull null
        val key = Uri.decode(parameter.substring(0, separator).replace("+", "%20"))
        val value = Uri.decode(parameter.substring(separator + 1).replace("+", "%20"))
        key to value
    }.toMap()
