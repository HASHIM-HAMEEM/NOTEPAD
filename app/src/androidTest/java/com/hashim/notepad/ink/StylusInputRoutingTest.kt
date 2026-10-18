package com.hashim.notepad.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hashim.notepad.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusInputRoutingTest {
    @Test
    fun stylusPrimaryKeyClickRunsConfiguredAction() {
        withView { view ->
            val actions = mutableListOf<StylusButtonAction>()
            view.primaryButtonAction = StylusButtonAction.SWITCH_TO_ERASER
            view.onStylusToolSwitch = actions::add

            view.handleStylusKeyEvent(KeyEvent(1000, 1000, KeyEvent.ACTION_DOWN, 308, 0))
            view.handleStylusKeyEvent(KeyEvent(1000, 1010, KeyEvent.ACTION_UP, 308, 0))

            assertEquals(listOf(StylusButtonAction.SWITCH_TO_ERASER), actions)
        }
    }

    @Test
    fun standardButtonKeyCodesAreRoutedAsStylusButtons() {
        assertTrue(StylusInputDiagnostics.isStylusKey(KeyEvent(1000, 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_1, 0)))
        assertTrue(StylusInputDiagnostics.isStylusKey(KeyEvent(1000, 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_2, 0)))
    }

    @Test
    fun hoverButtonStateRunsConfiguredActionOnRelease() {
        withView { view ->
            val actions = mutableListOf<StylusButtonAction>()
            view.secondaryButtonAction = StylusButtonAction.PREVIOUS_PEN
            view.onStylusToolSwitch = actions::add

            hover(view, 1000, MotionEvent.BUTTON_STYLUS_SECONDARY)
            hover(view, 1010, 0)

            assertEquals(listOf(StylusButtonAction.PREVIOUS_PEN), actions)
        }
    }

    private fun hover(view: InkSurfaceView, eventTime: Long, buttonState: Int) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = 100f
            y = 100f
            pressure = 0f
            size = 0f
        }
        val event = MotionEvent.obtain(
            eventTime,
            eventTime,
            MotionEvent.ACTION_HOVER_MOVE,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            buttonState,
            1f,
            1f,
            1,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
        try {
            view.onHoverEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun withView(block: (InkSurfaceView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> block(InkSurfaceView(activity)) }
        }
    }
}
