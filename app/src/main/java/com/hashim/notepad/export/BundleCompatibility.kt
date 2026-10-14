package com.hashim.notepad.export

internal data class LegacyNotebookDefaults(
    val documentKind: String,
    val lastPageIndex: Int = 0,
)

internal fun legacyNotebookDefaults(pageKinds: List<String>): LegacyNotebookDefaults =
    LegacyNotebookDefaults(if (pageKinds.any { it == "pdf" }) "pdf" else "notes")
