package com.hashim.notepad.ink

import androidx.ink.strokes.Stroke

object SelectionClipboard {
    private var contents: List<Stroke> = emptyList()

    val isEmpty: Boolean get() = contents.isEmpty()

    internal fun copy(strokes: Collection<Stroke>) {
        contents = strokes.toList()
    }

    internal fun read(): List<Stroke> = contents
}
