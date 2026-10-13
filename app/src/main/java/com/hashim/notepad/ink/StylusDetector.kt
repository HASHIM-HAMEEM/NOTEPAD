package com.hashim.notepad.ink

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent

object StylusDetector {
    // Detection rules adapted from xnotes-android (MIT).
    fun hasStylus(context: Context): Boolean = try {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager != null && inputManager.inputDeviceIds.any { id ->
            inputManager.getInputDevice(id)?.let(::isStylusDevice) == true
        }
    } catch (_: Exception) {
        false
    }

    private fun isStylusDevice(device: InputDevice): Boolean {
        if (device.supportsSource(InputDevice.SOURCE_BLUETOOTH_STYLUS)) return true
        if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) return false
        if (!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return true
        return device.motionRanges.any { range ->
            (range.axis == MotionEvent.AXIS_DISTANCE || range.axis == MotionEvent.AXIS_TILT) &&
                (range.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        }
    }
}
