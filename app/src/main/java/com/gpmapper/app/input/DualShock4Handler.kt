package com.gpmapper.app.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.gpmapper.app.model.AnalogConfig
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

class DualShock4Handler {

    companion object {
        const val DS4_V1_PRODUCT_ID = 0x05C4
        const val DS4_V2_PRODUCT_ID = 0x09CC
        const val DUALSENSE_PRODUCT_ID = 0x0CE6
        const val SONY_VENDOR_ID = 0x054C

        private val DS4_BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to "cross",
            KeyEvent.KEYCODE_BUTTON_B to "circle",
            KeyEvent.KEYCODE_BUTTON_X to "square",
            KeyEvent.KEYCODE_BUTTON_Y to "triangle",
            KeyEvent.KEYCODE_BUTTON_L1 to "l1",
            KeyEvent.KEYCODE_BUTTON_R1 to "r1",
            KeyEvent.KEYCODE_BUTTON_L2 to "l2",
            KeyEvent.KEYCODE_BUTTON_R2 to "r2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "l3",
            KeyEvent.KEYCODE_BUTTON_THUMBR to "r3",
            KeyEvent.KEYCODE_BUTTON_START to "options",
            KeyEvent.KEYCODE_BUTTON_SELECT to "share",
            KeyEvent.KEYCODE_BUTTON_MODE to "ps_button",
            KeyEvent.KEYCODE_DPAD_UP to "dpad_up",
            KeyEvent.KEYCODE_DPAD_DOWN to "dpad_down",
            KeyEvent.KEYCODE_DPAD_LEFT to "dpad_left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "dpad_right"
        )
    }

    data class StickOutput(
        val x: Float = 0f,
        val y: Float = 0f,
        val magnitude: Float = 0f,
        val angle: Float = 0f
    )

    data class TriggerOutput(
        val value: Float = 0f,
        val pressed: Boolean = false
    )

    data class ControllerState(
        val leftStick: StickOutput = StickOutput(),
        val rightStick: StickOutput = StickOutput(),
        val leftTrigger: TriggerOutput = TriggerOutput(),
        val rightTrigger: TriggerOutput = TriggerOutput(),
        val hatX: Float = 0f,
        val hatY: Float = 0f,
        val buttons: Set<String> = emptySet(),
        val pressedButtons: Set<String> = emptySet(),
        val releasedButtons: Set<String> = emptySet(),
        val timestampMs: Long = System.currentTimeMillis()
    )

    private var analogConfig = AnalogConfig()
    private var previousButtons = setOf<String>()
    private var filteredLX = 0f
    private var filteredLY = 0f
    private var filteredRX = 0f
    private var filteredRY = 0f

    fun setAnalogConfig(config: AnalogConfig) {
        analogConfig = config
    }

    fun isDS4Device(device: InputDevice): Boolean {
        val isSony = device.vendorId == SONY_VENDOR_ID
        val isDS4Model = device.productId == DS4_V1_PRODUCT_ID ||
                device.productId == DS4_V2_PRODUCT_ID ||
                device.productId == DUALSENSE_PRODUCT_ID
        return isSony && isDS4Model
    }

    fun getDeviceDescription(device: InputDevice): String {
        return "${device.name} vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)} sources=0x${device.sources.toString(16)}"
    }

    fun processMotionEvent(event: MotionEvent): ControllerState {
        val lx = processStickAxis(event, MotionEvent.AXIS_X, analogConfig.leftStickDeadzone)
        val ly = processStickAxis(event, MotionEvent.AXIS_Y, analogConfig.leftStickDeadzone)
        val rx = processStickAxis(event, MotionEvent.AXIS_Z, analogConfig.rightStickDeadzone)
        val ry = processStickAxis(event, MotionEvent.AXIS_RZ, analogConfig.rightStickDeadzone)

        filteredLX = smooth(lx, filteredLX, analogConfig.smoothingFactor)
        filteredLY = smooth(ly, filteredLY, analogConfig.smoothingFactor)
        filteredRX = smooth(rx, filteredRX, analogConfig.smoothingFactor)
        filteredRY = smooth(ry, filteredRY, analogConfig.smoothingFactor)

        val leftStick = buildStickOutput(filteredLX, filteredLY, analogConfig.leftStickExponent, analogConfig.leftStickSensitivity)
        val rightStick = buildStickOutput(filteredRX, filteredRY, analogConfig.rightStickExponent, analogConfig.rightStickSensitivity)

        val ltValue = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rtValue = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val currentButtons = mutableSetOf<String>()
        val pressedButtons = mutableSetOf<String>()
        val releasedButtons = mutableSetOf<String>()

        for ((keyCode, name) in DS4_BUTTON_MAP) {
            if (event.isButtonPressed(keyCode)) {
                currentButtons.add(name)
                if (name !in previousButtons) {
                    pressedButtons.add(name)
                }
            } else if (name in previousButtons) {
                releasedButtons.add(name)
            }
        }

        previousButtons = currentButtons

        return ControllerState(
            leftStick = leftStick,
            rightStick = rightStick,
            leftTrigger = TriggerOutput(ltValue, ltValue > 0.5f),
            rightTrigger = TriggerOutput(rtValue, rtValue > 0.5f),
            hatX = hatX,
            hatY = hatY,
            buttons = currentButtons,
            pressedButtons = pressedButtons,
            releasedButtons = releasedButtons,
            timestampMs = event.eventTime
        )
    }

    fun processKeyEvent(event: KeyEvent): String? {
        val name = DS4_BUTTON_MAP[event.keyCode] ?: return null
        return if (event.action == KeyEvent.ACTION_DOWN) name else null
    }

    fun getButtonName(keyCode: Int): String? {
        return DS4_BUTTON_MAP[keyCode]
    }

    fun getMotionRanges(device: InputDevice): Map<String, MotionRangeInfo> {
        val ranges = mutableMapOf<String, MotionRangeInfo>()
        val axes = mapOf(
            "left_x" to Pair(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK),
            "left_y" to Pair(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK),
            "right_x" to Pair(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK),
            "right_y" to Pair(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK),
            "hat_x" to Pair(MotionEvent.AXIS_HAT_X, InputDevice.SOURCE_DPAD),
            "hat_y" to Pair(MotionEvent.AXIS_HAT_Y, InputDevice.SOURCE_DPAD),
            "lt" to Pair(MotionEvent.AXIS_LTRIGGER, InputDevice.SOURCE_JOYSTICK),
            "rt" to Pair(MotionEvent.AXIS_RTRIGGER, InputDevice.SOURCE_JOYSTICK)
        )

        for ((name, pair) in axes) {
            val range = device.getMotionRange(pair.first, pair.second)
            if (range != null) {
                ranges[name] = MotionRangeInfo(
                    axis = pair.first,
                    source = pair.second,
                    min = range.min,
                    max = range.max,
                    flat = range.flat,
                    range = range.range
                )
            }
        }
        return ranges
    }

    data class MotionRangeInfo(
        val axis: Int,
        val source: Int,
        val min: Float,
        val max: Float,
        val flat: Float,
        val range: Float
    )

    private fun processStickAxis(event: MotionEvent, axis: Int, deadzone: Float): Float {
        val raw = event.getAxisValue(axis)
        return applyDeadzone(raw, deadzone)
    }

    private fun applyDeadzone(value: Float, innerDeadzone: Float): Float {
        val magnitude = abs(value)
        if (magnitude < innerDeadzone) return 0f
        val normalized = (magnitude - innerDeadzone) / (1.0f - innerDeadzone)
        return normalized.sign * normalized.coerceAtMost(1.0f)
    }

    private fun buildStickOutput(x: Float, y: Float, exponent: Float, sensitivity: Float): StickOutput {
        val curvedX = applyResponseCurve(x, exponent) * sensitivity
        val curvedY = applyResponseCurve(y, exponent) * sensitivity
        val clampedX = curvedX.coerceIn(-1f, 1f)
        val clampedY = curvedY.coerceIn(-1f, 1f)
        val magnitude = sqrt(clampedX * clampedX + clampedY * clampedY).coerceAtMost(1f)
        val angle = kotlin.math.atan2(clampedY.toDouble(), clampedX.toDouble()).toFloat()
        return StickOutput(clampedX, clampedY, magnitude, angle)
    }

    private fun applyResponseCurve(value: Float, exponent: Float): Float {
        val magnitude = abs(value)
        val curved = magnitude.pow(exponent)
        return curved.sign * value.sign
    }

    private fun smooth(current: Float, previous: Float, factor: Float): Float {
        return previous * factor + current * (1f - factor)
    }

    fun reset() {
        filteredLX = 0f
        filteredLY = 0f
        filteredRX = 0f
        filteredRY = 0f
        previousButtons = emptySet()
    }
}
