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

    /**
     * MotionEvent reports orientation in [-π, π] (0 = tip pointing up), but Ink's StrokeInput
     * requires [0, 2π) with 0 = tip pointing left. Real styluses report negative values whenever
     * the pen leans left; emulators and fingers always report 0, so an out-of-range value only
     * surfaces on hardware, where StrokeInputBatch rejects it and the stroke crashes.
     */
    fun orientation(value: Float): Float {
        if (!value.isFinite()) return 0f
        val twoPi = (2 * PI).toFloat()
        val converted = (value + 2.5f * PI.toFloat()).mod(twoPi)
        return if (converted.isFinite() && converted >= 0f && converted < twoPi) converted else 0f
    }

    fun hasLength(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) return false
        val length = hypot(endX - startX, endY - startY)
        return length.isFinite() && length > MIN_STROKE_LENGTH_PT
    }

    private const val MIN_STROKE_LENGTH_PT = 0.01f
}
