package com.hashim.notepad.audio

import com.hashim.notepad.data.InkTimestamp

data class AudioSegmentPosition(val segmentIndex: Int, val offsetMs: Long)

object AudioTimeline {
    fun segmentAt(durationsMs: List<Long>, positionMs: Long): AudioSegmentPosition? {
        if (durationsMs.isEmpty()) return null
        var remaining = positionMs.coerceAtLeast(0)
        durationsMs.forEachIndexed { index, duration ->
            val length = duration.coerceAtLeast(0)
            if (remaining < length || index == durationsMs.lastIndex) {
                return AudioSegmentPosition(index, remaining.coerceAtMost(length))
            }
            remaining -= length
        }
        return null
    }

    fun isDimmed(itemTimeMs: Long, playheadMs: Long): Boolean = itemTimeMs > playheadMs

    fun latestVisible(items: List<InkTimestamp>, pageId: String, playheadMs: Long): InkTimestamp? =
        items.asSequence()
            .filter { it.pageId == pageId && it.timeMs <= playheadMs }
            .maxByOrNull(InkTimestamp::timeMs)

    fun pageMarkers(items: List<InkTimestamp>, pageId: String): List<Long> =
        items.asSequence().filter { it.pageId == pageId }.map(InkTimestamp::timeMs).distinct().sorted().toList()
}
