package com.hashim.notepad.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val STYLUS_BUTTON_PRIMARY = 308
private const val STYLUS_BUTTON_SECONDARY = 309
private const val STYLUS_BUTTON_TERTIARY = 310
private const val STYLUS_BUTTON_TAIL = 311

enum class StylusButtonAction {
    SWITCH_TO_ERASER,
    PREVIOUS_PEN,
}

internal object StylusButtonKeyMapping {
    fun buttonForKeyCode(keyCode: Int): Int? = when (keyCode) {
        STYLUS_BUTTON_PRIMARY, STYLUS_BUTTON_TERTIARY, KeyEvent.KEYCODE_BUTTON_1 ->
            MotionEvent.BUTTON_STYLUS_PRIMARY
        STYLUS_BUTTON_SECONDARY, STYLUS_BUTTON_TAIL, KeyEvent.KEYCODE_BUTTON_2 ->
            MotionEvent.BUTTON_STYLUS_SECONDARY
        else -> null
    }

    fun shouldDispatch(action: Int, repeatCount: Int): Boolean =
        action == KeyEvent.ACTION_DOWN && repeatCount == 0
}

internal class StylusButtonDebouncer(private val windowMs: Long = 250L) {
    private val lastAcceptedEventByButton = mutableMapOf<Int, Long>()

    fun accept(button: Int, eventTimeMs: Long): Boolean {
        val previousEventTime = lastAcceptedEventByButton[button]
        if (previousEventTime != null && eventTimeMs - previousEventTime in 0L..windowMs) {
            return false
        }
        lastAcceptedEventByButton[button] = eventTimeMs
        return true
    }
}

internal data class StylusButtonChanges(val pressed: Int, val released: Int)

internal object StylusButtonMotionTransitions {
    fun changes(previous: Int, current: Int, action: Int, actionButton: Int): StylusButtonChanges {
        val pressed = (current and previous.inv()) or
            if (action == MotionEvent.ACTION_BUTTON_PRESS) actionButton else 0
        val released = (previous and current.inv()) or
            if (action == MotionEvent.ACTION_BUTTON_RELEASE) actionButton else 0
        return StylusButtonChanges(pressed, released)
    }
}

internal object StylusButtonDispatchPolicy {
    fun shouldSwitchTool(contactActive: Boolean): Boolean = !contactActive
}

object StylusInputDiagnostics {
    private val _lastEvent = MutableStateFlow("Last stylus event: none")
    val lastEvent = _lastEvent.asStateFlow()

    @Volatile
    var onStylusKeyEvent: ((KeyEvent) -> Unit)? = null

    fun isStylusKey(event: KeyEvent): Boolean =
        event.keyCode in STYLUS_BUTTON_PRIMARY..STYLUS_BUTTON_TAIL ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_2 ||
            (event.device?.sources?.and(InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS)

    fun isMappedButtonKey(event: KeyEvent): Boolean =
        StylusButtonKeyMapping.buttonForKeyCode(event.keyCode) != null

    /** Returns true when an open editor consumed the key, so it must not fall back to system actions like Back. */
    fun recordKeyEvent(event: KeyEvent): Boolean {
        _lastEvent.value =
            "keycode=${KeyEvent.keyCodeToString(event.keyCode)} device=${event.device?.name ?: "unknown"} " +
                "source=0x${event.source.toString(16)} buttonState=n/a"
        if (!isMappedButtonKey(event)) return false
        val listener = onStylusKeyEvent ?: return false
        if (StylusButtonKeyMapping.shouldDispatch(event.action, event.repeatCount)) {
            listener(event)
        }
        return true
    }

    fun recordMotionEvent(event: MotionEvent) {
        _lastEvent.value =
            "keycode=none device=${event.device?.name ?: "unknown"} source=0x${event.source.toString(16)} " +
                "buttonState=0x${event.buttonState.toString(16)} ${MotionEvent.actionToString(event.actionMasked)}"
    }

}
