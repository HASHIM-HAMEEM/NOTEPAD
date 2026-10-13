package com.hashim.notepad.ui

import android.net.Uri
import android.widget.Toast
import com.hashim.notepad.InboundShare
import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.pdf.PdfImporter
import com.hashim.notepad.pdf.ImportStorage
import com.hashim.notepad.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Notebooks : Screen
    data class Pages(val notebookId: String) : Screen
    data class Editor(val notebookId: String, val pageId: String) : Screen
    data object Search : Screen
    data object Account : Screen
}

@Composable
fun NotePadApp(
    initialPdf: Uri?,
    initialAuthCallback: Uri?,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    initialShare: InboundShare? = null,
) {
    val context = LocalContext.current
    val repo = remember { NotePadRepository.get(context) }
    val appScope = rememberCoroutineScope()
    val importer = remember { PdfImporter(context, repo) }
    val exporter = remember { com.hashim.notepad.export.PdfExporter(context, repo) }
    val recognizer = remember { com.hashim.notepad.recognition.InkRecognizer() }
    val bundles = remember { com.hashim.notepad.export.BundleCodec(context, repo) }
    var screen by remember { mutableStateOf<Screen>(Screen.Notebooks) }
    var importing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(repo) {
        withContext(Dispatchers.IO) {
            val referenced = repo.helper.readableDatabase.rawQuery("select local_path from assets", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            ImportStorage.cleanOrphans(context, referenced)
        }
    }

    LaunchedEffect(initialPdf) {
        initialPdf ?: return@LaunchedEffect
        importing = true
        runCatching { importer.importAsNotebook(initialPdf) }
            .onSuccess { screen = Screen.Pages(it) }
            .onFailure { android.util.Log.e("NotePad", "PDF import failed", it); Toast.makeText(context, "Could not import PDF: ${it.message}", Toast.LENGTH_LONG).show() }
        importing = false
    }

    LaunchedEffect(initialShare) {
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
            is Screen.Editor -> Screen.Pages(s.notebookId)
            else -> Screen.Notebooks
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showNavigationRail = maxWidth >= 840.dp && screen !is Screen.Editor
        val screenContent: @Composable () -> Unit = {
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val forward = depth(targetState) >= depth(initialState)
                    val openingNotebook = (targetState is Screen.Pages || targetState is Screen.Editor) && initialState is Screen.Notebooks
                    val enter = if (openingNotebook) {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.medium, delayMillis = 40, easing = Motion.emphasizedDecelerate)) +
                            androidx.compose.animation.scaleIn(
                                initialScale = 0.96f,
                                animationSpec = androidx.compose.animation.core.tween(Motion.medium, easing = Motion.emphasizedDecelerate),
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
                when (s) {
                    Screen.Notebooks -> NotebooksScreen(
                        repo = repo, importer = importer, bundles = bundles, exporter = exporter, importing = importing,
                        sharedItems = initialShare?.uris.orEmpty(), sharedMimeType = initialShare?.mimeType,
                        onImportingChanged = { importing = it },
                        onOpen = { screen = Screen.Pages(it) },
                        onContinueAtPosition = { notebookId, pageIndex ->
                            appScope.launch {
                                val targetPage = repo.pages(notebookId).first().getOrNull(pageIndex)
                                screen = targetPage?.let { Screen.Editor(notebookId, it.id) } ?: Screen.Pages(notebookId)
                            }
                        },
                        onSearch = { screen = Screen.Search }, onAccount = { screen = Screen.Account },
                        themeMode = themeMode, dynamicColor = dynamicColor, onThemeChange = onThemeChange, onDynamicChange = onDynamicChange,
                        showAccountButton = !showNavigationRail,
                    )
                    Screen.Account -> AccountScreen(onBack = { screen = Screen.Notebooks })
                    Screen.Search -> SearchScreen(repo = repo, onBack = { screen = Screen.Notebooks }, onOpenPage = { nb, page -> screen = Screen.Editor(nb, page) })
                    is Screen.Pages -> androidx.compose.foundation.layout.BoxWithConstraints {
                        val twoPane = maxWidth >= 840.dp
                        if (twoPane) androidx.compose.foundation.layout.Row {
                            androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                                NotebooksScreen(
                                    repo = repo, importer = importer, bundles = bundles, exporter = exporter, importing = importing, onImportingChanged = { importing = it },
                                    sharedItems = initialShare?.uris.orEmpty(), sharedMimeType = initialShare?.mimeType,
                                    onOpen = { screen = Screen.Pages(it) }, onSearch = { screen = Screen.Search }, onAccount = { screen = Screen.Account }, selectedId = s.notebookId, compact = true,
                                    themeMode = themeMode, dynamicColor = dynamicColor, onThemeChange = onThemeChange, onDynamicChange = onDynamicChange,
                                    showAccountButton = false,
                                )
                            }
                            androidx.compose.material3.VerticalDivider()
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                PagesScreen(repo = repo, notebookId = s.notebookId, exporter = exporter, recognizer = recognizer, bundles = bundles,
                                    onBack = { screen = Screen.Notebooks }, onOpenPage = { screen = Screen.Editor(s.notebookId, it) }, showBack = false)
                            }
                        } else PagesScreen(
                            repo = repo, notebookId = s.notebookId, exporter = exporter, recognizer = recognizer, bundles = bundles,
                            onBack = { screen = Screen.Notebooks },
                            onOpenPage = { screen = Screen.Editor(s.notebookId, it) },
                        )
                    }
                    is Screen.Editor -> EditorScreen(
                        repo = repo, notebookId = s.notebookId, pageId = s.pageId, recognizer = recognizer,
                        onBack = { screen = Screen.Pages(s.notebookId) },
                        onNavigateToPage = { screen = Screen.Editor(s.notebookId, it) },
                    )
                }
            }
        }

        if (showNavigationRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = screen == Screen.Notebooks || screen is Screen.Pages,
                        onClick = { screen = Screen.Notebooks },
                        icon = { Icon(Icons.Outlined.Book, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationRailItem(
                        selected = screen == Screen.Search,
                        onClick = { screen = Screen.Search },
                        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationRailItem(
                        selected = screen == Screen.Account,
                        onClick = { screen = Screen.Account },
                        icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                        label = { Text("Sync") },
                    )
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    screenContent()
                }
            }
        } else {
            screenContent()
        }
    }
}

private fun depth(s: Screen) = when (s) { Screen.Notebooks -> 0; Screen.Search, Screen.Account -> 1; is Screen.Pages -> 1; is Screen.Editor -> 2 }

private fun parseAuthCallback(fragment: String): Map<String, String> =
    fragment.split('&').mapNotNull { parameter ->
        val separator = parameter.indexOf('=')
        if (separator < 0) return@mapNotNull null
        val key = Uri.decode(parameter.substring(0, separator).replace("+", "%20"))
        val value = Uri.decode(parameter.substring(separator + 1).replace("+", "%20"))
        key to value
    }.toMap()
