package com.hashim.notepad.audio

import com.hashim.notepad.data.InkTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    @Test
    fun segmentPositionUsesCumulativeDurations() {
        assertEquals(AudioSegmentPosition(0, 29_999), AudioTimeline.segmentAt(listOf(30_000, 12_000), 29_999))
        assertEquals(AudioSegmentPosition(1, 5_000), AudioTimeline.segmentAt(listOf(30_000, 12_000), 35_000))
        assertEquals(AudioSegmentPosition(1, 12_000), AudioTimeline.segmentAt(listOf(30_000, 12_000), 99_000))
        assertNull(AudioTimeline.segmentAt(emptyList(), 1))
    }

    @Test
    fun visibilityAndLatestItemFollowPlayheadAndPage() {
        val items = listOf(
            timestamp("p1", "stroke", "s1", 100),
            timestamp("p1", "text", "t1", 250),
            timestamp("p1", "image", "i1", 400),
            timestamp("p2", "stroke", "s2", 200),
        )
        assertFalse(AudioTimeline.isDimmed(100, 250))
        assertTrue(AudioTimeline.isDimmed(400, 250))
        assertEquals("t1", AudioTimeline.latestVisible(items, "p1", 250)?.itemKey)
        assertEquals(listOf(100L, 250L, 400L), AudioTimeline.pageMarkers(items, "p1"))
        assertNull(AudioTimeline.latestVisible(items, "missing", 1000))
    }

    private fun timestamp(page: String, kind: String, key: String, time: Long) =
        InkTimestamp("recording", page, kind, key, time)
}
