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

    @Test
    fun suppressesMotionButtonEventAfterMatchingKeyEvent() {
        val debouncer = StylusButtonDebouncer()
        val secondaryButton = MotionEvent.BUTTON_STYLUS_SECONDARY

        assertTrue(debouncer.accept(secondaryButton, 1_000L))
        assertFalse(debouncer.accept(secondaryButton, 1_200L))
    }

    @Test
    fun acceptsDifferentButtonsWithinTheSameWindow() {
        val debouncer = StylusButtonDebouncer()

        assertTrue(debouncer.accept(MotionEvent.BUTTON_STYLUS_SECONDARY, 1_000L))
        assertTrue(debouncer.accept(MotionEvent.BUTTON_STYLUS_PRIMARY, 1_100L))
    }

    @Test
    fun acceptsTheSameButtonAgainAfterTheDebounceWindow() {
        val debouncer = StylusButtonDebouncer()
        val primaryButton = MotionEvent.BUTTON_STYLUS_PRIMARY

        assertTrue(debouncer.accept(primaryButton, 1_000L))
        assertTrue(debouncer.accept(primaryButton, 1_251L))
    }

    @Test
    fun buttonStateStartsSwitchOnPressWithoutWaitingForRelease() {
        val changes = StylusButtonMotionTransitions.changes(
            previous = 0,
            current = MotionEvent.BUTTON_STYLUS_PRIMARY,
            action = MotionEvent.ACTION_HOVER_MOVE,
            actionButton = 0,
        )

        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, changes.pressed)
        assertEquals(0, changes.released)
    }

    @Test
    fun actionButtonPressStartsSwitchEvenWhenStateWasAlreadyObserved() {
        val changes = StylusButtonMotionTransitions.changes(
            previous = MotionEvent.BUTTON_STYLUS_PRIMARY,
            current = MotionEvent.BUTTON_STYLUS_PRIMARY,
            action = MotionEvent.ACTION_BUTTON_PRESS,
            actionButton = MotionEvent.BUTTON_STYLUS_PRIMARY,
        )

        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, changes.pressed)
        assertEquals(0, changes.released)
    }

    @Test
    fun contactPreventsToolSwitchButIdlePressCanSwitch() {
        assertTrue(StylusButtonDispatchPolicy.shouldSwitchTool(contactActive = false))
        assertFalse(StylusButtonDispatchPolicy.shouldSwitchTool(contactActive = true))
    }
}
