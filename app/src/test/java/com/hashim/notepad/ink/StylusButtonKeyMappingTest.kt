package com.hashim.notepad.ink

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusButtonKeyMappingTest {
    @Test
    fun mapsSecondaryBeforePrimaryWithoutDependingOnPressOrder() {
        assertEquals(
            listOf(MotionEvent.BUTTON_STYLUS_SECONDARY, MotionEvent.BUTTON_STYLUS_PRIMARY),
            listOf(309, 308).mapNotNull(StylusButtonKeyMapping::buttonForKeyCode),
        )
    }

    @Test
    fun mapsButtonAliasesAndRejectsUnmappedKeys() {
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, StylusButtonKeyMapping.buttonForKeyCode(310))
        assertEquals(MotionEvent.BUTTON_STYLUS_SECONDARY, StylusButtonKeyMapping.buttonForKeyCode(311))
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, StylusButtonKeyMapping.buttonForKeyCode(KeyEvent.KEYCODE_BUTTON_1))
        assertEquals(MotionEvent.BUTTON_STYLUS_SECONDARY, StylusButtonKeyMapping.buttonForKeyCode(KeyEvent.KEYCODE_BUTTON_2))
        assertNull(StylusButtonKeyMapping.buttonForKeyCode(KeyEvent.KEYCODE_A))
    }

    @Test
    fun forwardsOnlyTheInitialKeyDown() {
        assertTrue(StylusButtonKeyMapping.shouldDispatch(KeyEvent.ACTION_DOWN, 0))
        assertFalse(StylusButtonKeyMapping.shouldDispatch(KeyEvent.ACTION_DOWN, 1))
        assertFalse(StylusButtonKeyMapping.shouldDispatch(KeyEvent.ACTION_UP, 0))
    }
}
