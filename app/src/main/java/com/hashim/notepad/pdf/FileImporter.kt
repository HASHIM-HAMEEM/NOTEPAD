package com.hashim.notepad.pdf

import android.content.Context
import android.net.Uri
import com.hashim.notepad.data.NotePadRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class FileImporter(private val context: Context, private val repo: NotePadRepository) {
    suspend fun import(
        uri: Uri,
        folderId: String?,
        title: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): String {
        val staged = ImportStorage(context).copy(uri, "file", "Imported file", onProgress)
        var committed = false
        try {
            val asset = staged.promote()
            currentCoroutineContext().ensureActive()
            val id = withContext(NonCancellable) {
                repo.createFileNotebook(
                    title?.trim()?.takeIf(String::isNotEmpty) ?: staged.name.substringBeforeLast('.', staged.name),
                    asset,
                    staged.mimeType,
                    staged.name,
                    folderId,
                ).also { committed = true }
            }
            onProgress(ImportProgress(staged.name, 100))
            return id
        } catch (error: Throwable) {
            if (!committed) staged.discard()
            throw error
        }
    }
}
