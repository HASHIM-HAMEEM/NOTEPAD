package com.hashim.notepad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitViewStateTest {
    @Test
    fun dividerSnapsToTheNearestThirdHalfOrTwoThirds() {
        assertEquals(0.33f, snapSplitDividerRatio(0.29f), 0f)
        assertEquals(0.5f, snapSplitDividerRatio(0.56f), 0f)
        assertEquals(0.67f, snapSplitDividerRatio(0.72f), 0f)
    }
}
