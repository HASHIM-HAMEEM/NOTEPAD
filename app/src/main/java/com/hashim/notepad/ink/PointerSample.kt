package com.hashim.notepad.ink

data class PointerSample(
    val toolType: String = "-",
    val pressure: Float = 0f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val handlerLatencyMs: Long = 0,
    val historySize: Int = 0,
    val strokeCount: Int = 0,
)
