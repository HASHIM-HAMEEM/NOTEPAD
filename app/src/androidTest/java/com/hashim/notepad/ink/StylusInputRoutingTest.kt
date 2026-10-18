package com.hashim.notepad.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hashim.notepad.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun hoverButtonStateRunsConfiguredActionOnPressEvenWhenHeld() {
        withView { view ->
            val actions = mutableListOf<StylusButtonAction>()
            view.secondaryButtonAction = StylusButtonAction.PREVIOUS_PEN
            view.onStylusToolSwitch = actions::add

            hover(view, 1000, MotionEvent.BUTTON_STYLUS_SECONDARY)
            assertEquals(listOf(StylusButtonAction.PREVIOUS_PEN), actions)

            hover(view, 1600, 0)
            assertEquals(listOf(StylusButtonAction.PREVIOUS_PEN), actions)
        }
    }

    @Test
    fun actionButtonPressOnHoverSwitchesSelectedToolThroughTheViewCallback() {
        withView { view ->
            val actions = mutableListOf<StylusButtonAction>()
            view.brushSpec = BrushSpec(InkTool.PEN, 0xFF1A1A1A.toInt(), 2.5f)
            view.primaryButtonAction = StylusButtonAction.SWITCH_TO_ERASER
            view.onStylusToolSwitch = { action ->
                actions += action
                view.brushSpec = BrushSpec(InkTool.ERASER, 0xFF1A1A1A.toInt(), 2.5f)
            }

            hover(view, 2000, 0)
            val press = pointerEvent(
                eventTime = 2010,
                action = MotionEvent.ACTION_BUTTON_PRESS,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            )
            try {
                view.onHoverEvent(press)
            } finally {
                press.recycle()
            }

            assertEquals(listOf(StylusButtonAction.SWITCH_TO_ERASER), actions)
            assertEquals(InkTool.ERASER, view.brushSpec.tool)
        }
    }

    @Test
    fun primaryButtonPressDuringStylusContactDoesNotSwitchTool() {
        withView { view ->
            val actions = mutableListOf<StylusButtonAction>()
            view.brushSpec = BrushSpec(InkTool.PEN, 0xFF1A1A1A.toInt(), 2.5f)
            view.primaryButtonAction = StylusButtonAction.SWITCH_TO_ERASER
            view.onStylusToolSwitch = actions::add

            val down = pointerEvent(3000, MotionEvent.ACTION_DOWN, buttonState = 0)
            val press = pointerEvent(
                eventTime = 3010,
                action = MotionEvent.ACTION_BUTTON_PRESS,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            )
            try {
                view.onTouchEvent(down)
                view.onTouchEvent(press)
            } finally {
                down.recycle()
                press.recycle()
            }

            assertTrue(actions.isEmpty())
            assertEquals(InkTool.PEN, view.brushSpec.tool)
        }
    }

    @Test
    fun activityRoutesMappedStylusKeysWithoutFocusedInkView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val actions = mutableListOf<StylusButtonAction>()
            scenario.onActivity { activity ->
                val view = InkSurfaceView(activity)
                view.primaryButtonAction = StylusButtonAction.SWITCH_TO_ERASER
                view.secondaryButtonAction = StylusButtonAction.PREVIOUS_PEN
                view.onStylusToolSwitch = actions::add
                StylusInputDiagnostics.onStylusKeyEvent = view::handleStylusKeyEvent
                try {
                    assertFalse(view.isFocused)
                    assertTrue(
                        activity.dispatchKeyEvent(
                            KeyEvent(4000, 4000, KeyEvent.ACTION_DOWN, 308, 0),
                        ),
                    )
                    assertTrue(
                        activity.dispatchKeyEvent(
                            KeyEvent(5000, 5000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_2, 0),
                        ),
                    )
                } finally {
                    StylusInputDiagnostics.onStylusKeyEvent = null
                }
            }
            assertEquals(
                listOf(
                    StylusButtonAction.SWITCH_TO_ERASER,
                    StylusButtonAction.PREVIOUS_PEN,
                ),
                actions,
            )
        }
    }

    private fun hover(view: InkSurfaceView, eventTime: Long, buttonState: Int) {
        val event = pointerEvent(eventTime, MotionEvent.ACTION_HOVER_MOVE, buttonState)
        try {
            view.onHoverEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun pointerEvent(eventTime: Long, action: Int, buttonState: Int): MotionEvent {
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
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
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
    }

    private fun withView(block: (InkSurfaceView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> block(InkSurfaceView(activity)) }
        }
    }
}
