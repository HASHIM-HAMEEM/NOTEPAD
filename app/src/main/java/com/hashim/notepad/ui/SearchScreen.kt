package com.hashim.notepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.SearchHit
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(repo: NotePadRepository, onBack: () -> Unit, onOpenPage: (notebookId: String, pageId: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    LaunchedEffect(query) { delay(250); hits = repo.search(query) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search handwriting and text") }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            },
        )
    }) { padding ->
        if (query.isNotBlank() && hits.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) { Text("No matches.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(hits, key = { it.pageId + it.source }) { h ->
                ListItem(
                    headlineContent = { Text(h.snippet, maxLines = 2) },
                    supportingContent = { Text("${h.notebookTitle} · page ${h.pageIndex} · ${h.source}") },
                    modifier = Modifier.clickable { onOpenPage(h.notebookId, h.pageId) },
                )
                HorizontalDivider()
            }
        }
    }
}
