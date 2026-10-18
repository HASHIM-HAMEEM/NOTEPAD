package com.hashim.notepad.ink

internal class HighlighterStrokeLifecycle {
    private var active = false

    fun begin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun acceptsInput(): Boolean = active

    fun finish(): Boolean {
        if (!active) return false
        active = false
        return true
    }

    fun cancel() {
        active = false
    }
}
