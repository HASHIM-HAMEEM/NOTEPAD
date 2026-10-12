package com.hashim.notepad.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.pdf.PdfImporter

sealed interface Screen {
    data object Notebooks : Screen
    data class Pages(val notebookId: String) : Screen
    data class Editor(val notebookId: String, val pageId: String) : Screen
    data object Search : Screen
    data object Account : Screen
}

@Composable
fun NotePadApp(initialPdf: Uri?, themeMode: ThemeMode, dynamicColor: Boolean, onThemeChange: (ThemeMode) -> Unit, onDynamicChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val repo = remember { NotePadRepository.get(context) }
    val importer = remember { PdfImporter(context, repo) }
    val exporter = remember { com.hashim.notepad.export.PdfExporter(context, repo) }
    val recognizer = remember { com.hashim.notepad.recognition.InkRecognizer() }
    val bundles = remember { com.hashim.notepad.export.BundleCodec(context, repo) }
    var screen by remember { mutableStateOf<Screen>(Screen.Notebooks) }
    var importing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialPdf) {
        initialPdf ?: return@LaunchedEffect
        importing = true
        runCatching { importer.importAsNotebook(initialPdf) }
            .onSuccess { screen = Screen.Pages(it) }
            .onFailure { Toast.makeText(context, "Could not import PDF: ${it.message}", Toast.LENGTH_LONG).show() }
        importing = false
    }

    BackHandler(enabled = screen != Screen.Notebooks) {
        screen = when (val s = screen) {
            is Screen.Editor -> Screen.Pages(s.notebookId)
            else -> Screen.Notebooks
        }
    }

    when (val s = screen) {
        Screen.Notebooks -> NotebooksScreen(
            repo = repo, importer = importer, bundles = bundles, importing = importing,
            onImportingChanged = { importing = it },
            onOpen = { screen = Screen.Pages(it) },
            onSearch = { screen = Screen.Search }, onAccount = { screen = Screen.Account },
            themeMode = themeMode, dynamicColor = dynamicColor, onThemeChange = onThemeChange, onDynamicChange = onDynamicChange,
        )
        Screen.Account -> AccountScreen(onBack = { screen = Screen.Notebooks })
        Screen.Search -> SearchScreen(repo = repo, onBack = { screen = Screen.Notebooks }, onOpenPage = { nb, page -> screen = Screen.Editor(nb, page) })
        is Screen.Pages -> androidx.compose.foundation.layout.BoxWithConstraints {
            val twoPane = maxWidth >= 840.dp
            if (twoPane) androidx.compose.foundation.layout.Row {
                androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                    NotebooksScreen(
                        repo = repo, importer = importer, bundles = bundles, importing = importing, onImportingChanged = { importing = it },
                        onOpen = { screen = Screen.Pages(it) }, onSearch = { screen = Screen.Search }, onAccount = { screen = Screen.Account }, selectedId = s.notebookId, compact = true,
                        themeMode = themeMode, dynamicColor = dynamicColor, onThemeChange = onThemeChange, onDynamicChange = onDynamicChange,
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
