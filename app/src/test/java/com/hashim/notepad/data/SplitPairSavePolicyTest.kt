package com.hashim.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitPairSavePolicyTest {
    @Test
    fun reusesSavedRatioAndSwapStateRegardlessOfRequestedOrder() {
        val savedPair = SplitPair("saved", "left", "right", 0.67f, true)

        assertEquals(savedPair, existingSplitPairForSave(savedPair, "right", "left"))
    }

    @Test
    fun doesNotReuseStateForADifferentPair() {
        val savedPair = SplitPair("saved", "left", "right", 0.67f, true)

        assertNull(existingSplitPairForSave(savedPair, "left", "other"))
    }
}
