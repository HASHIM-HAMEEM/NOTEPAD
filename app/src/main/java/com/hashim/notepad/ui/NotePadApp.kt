package com.hashim.notepad.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.pdf.PdfImporter

sealed interface Screen {
    data object Notebooks : Screen
    data class Pages(val notebookId: String) : Screen
    data class Editor(val notebookId: String, val pageId: String) : Screen
    data object Search : Screen
}

@Composable
fun NotePadApp(initialPdf: Uri?) {
    val context = LocalContext.current
    val repo = remember { NotePadRepository(context) }
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
            onSearch = { screen = Screen.Search },
        )
        Screen.Search -> SearchScreen(repo = repo, onBack = { screen = Screen.Notebooks }, onOpenPage = { nb, page -> screen = Screen.Editor(nb, page) })
        is Screen.Pages -> PagesScreen(
            repo = repo, notebookId = s.notebookId, exporter = exporter, recognizer = recognizer, bundles = bundles,
            onBack = { screen = Screen.Notebooks },
            onOpenPage = { screen = Screen.Editor(s.notebookId, it) },
        )
        is Screen.Editor -> EditorScreen(
            repo = repo, notebookId = s.notebookId, pageId = s.pageId, recognizer = recognizer,
            onBack = { screen = Screen.Pages(s.notebookId) },
            onNavigateToPage = { screen = Screen.Editor(s.notebookId, it) },
        )
    }
}
