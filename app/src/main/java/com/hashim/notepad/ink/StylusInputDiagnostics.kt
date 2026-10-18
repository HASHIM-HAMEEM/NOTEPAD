package com.hashim.notepad.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val STYLUS_BUTTON_PRIMARY = 308
private const val STYLUS_BUTTON_TAIL = 311

enum class StylusButtonAction {
    SWITCH_TO_ERASER,
    PREVIOUS_PEN,
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

    fun recordKeyEvent(event: KeyEvent) {
        _lastEvent.value =
            "keycode=${KeyEvent.keyCodeToString(event.keyCode)} device=${event.device?.name ?: "unknown"} " +
                "source=0x${event.source.toString(16)} buttonState=n/a"
        onStylusKeyEvent?.invoke(event)
    }

    fun recordMotionEvent(event: MotionEvent) {
        _lastEvent.value =
            "keycode=none device=${event.device?.name ?: "unknown"} source=0x${event.source.toString(16)} " +
                "buttonState=0x${event.buttonState.toString(16)} ${MotionEvent.actionToString(event.actionMasked)}"
    }

}
