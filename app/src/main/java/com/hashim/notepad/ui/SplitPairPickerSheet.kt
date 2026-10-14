package com.hashim.notepad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SplitPairPickerSheet(
    repo: NotePadRepository,
    primaryNotebookId: String,
    onDismiss: () -> Unit,
    onSelectNotebook: (String) -> Unit,
    onNewLectureNotes: () -> Unit,
) {
    val notebooks by repo.notebooks().collectAsState(initial = emptyList())
    val primary = notebooks.firstOrNull { it.id == primaryNotebookId }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Open side by side", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Choose a lecture document to pair with ${primary?.title.orEmpty()}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(
                onClick = onNewLectureNotes,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Outlined.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Column {
                        Text("New lecture notes", style = MaterialTheme.typography.titleMedium)
                        Text("Create notes in the same subject", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val recent = notebooks.asSequence()
                .filter { it.id != primaryNotebookId }
                .sortedWith(
                    compareByDescending<com.hashim.notepad.data.Notebook> { it.folderId == primary?.folderId }
                        .thenByDescending { it.lastOpenedAt ?: it.updatedAt },
                )
                .toList()
            recent.forEach { notebook ->
                TextButton(
                    onClick = { onSelectNotebook(notebook.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            if (notebook.documentKind == "pdf") Icons.Outlined.PictureAsPdf else Icons.Outlined.NoteAdd,
                            contentDescription = null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(notebook.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${notebook.pageCount} pages · ${notebook.language}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (recent.isEmpty()) {
                Text("No other documents yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
