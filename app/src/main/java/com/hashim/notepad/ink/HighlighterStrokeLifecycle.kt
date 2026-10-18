package com.hashim.notepad.ink

import kotlin.math.PI
import kotlin.math.hypot

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

internal object HighlighterInputPolicy {
    fun brushSize(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(1f, 10f) ?: 1f

    fun opacity(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(0.1f, 1f) ?: 0.1f

    fun pressure(value: Float): Float =
        value.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 1f

    fun tilt(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(0f, (PI / 2).toFloat()) ?: 0f

    fun orientation(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(-PI.toFloat(), PI.toFloat()) ?: 0f

    fun hasLength(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) return false
        val length = hypot(endX - startX, endY - startY)
        return length.isFinite() && length > MIN_STROKE_LENGTH_PT
    }

    private const val MIN_STROKE_LENGTH_PT = 0.01f
}
