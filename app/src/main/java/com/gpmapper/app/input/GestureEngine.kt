package com.gpmapper.app.input

import android.util.Log
import com.gpmapper.app.model.*
import java.util.concurrent.ConcurrentHashMap

class GestureEngine(private val touchInjector: TouchInjector) {

    companion object {
        private const val TAG = "GestureEngine"
    }

    private var currentProfile: MappingProfile? = null
    private val activeModifiers = ConcurrentHashMap.newKeySet<String>()
    private val comboTracker = ComboTracker()

    fun setProfile(profile: MappingProfile) {
        currentProfile = profile
        activeModifiers.clear()
        comboTracker.reset()
        Log.i(TAG, "Profile set: ${profile.name}")
    }

    fun handleButtonPress(button: String) {
        val profile = currentProfile ?: return
        activeModifiers.add(button)

        checkAndExecuteCombos(profile, button)
    }

    fun handleButtonRelease(button: String) {
        activeModifiers.remove(button)
    }

    fun handleAnalogInput(stick: String, x: Float, y: Float) {
        val profile = currentProfile ?: return

        if (stick == "left_stick") {
            handleLeftStick(profile, x, y)
        } else if (stick == "right_stick") {
            handleRightStick(profile, x, y)
        }
    }

    fun handleDpadInput(x: Float, y: Float) {
        val profile = currentProfile ?: return
        val mapping = profile.mappings.find {
            it.sourceButton == "dpad" && it.enabled
        } ?: return

        if (mapping.actionType == "swipe" && mapping.swipeConfig != null) {
            val adjustedConfig = mapping.swipeConfig.copy(
                endX = mapping.swipeConfig.startX + x * 0.1f,
                endY = mapping.swipeConfig.startY + y * 0.1f
            )
            touchInjector.smoothSwipe(
                adjustedConfig.startX, adjustedConfig.startY,
                adjustedConfig.endX, adjustedConfig.endY,
                adjustedConfig.durationMs,
                pointerId = 97
            )
        }
    }

    private fun checkAndExecuteCombos(profile: MappingProfile, pressedButton: String) {
        for (modifier in profile.gestureModifiers) {
            if (modifier.modifierButton == pressedButton && modifier.primaryButton in activeModifiers) {
                executeComboGesture(modifier)
                return
            }
            if (modifier.primaryButton == pressedButton && modifier.modifierButton in activeModifiers) {
                executeComboGesture(modifier)
                return
            }
        }
    }

    private fun executeComboGesture(modifier: GestureModifier) {
        Log.i(TAG, "Executing combo gesture: ${modifier.gestureName}")
        touchInjector.smoothSwipe(
            modifier.swipeOverride.startX,
            modifier.swipeOverride.startY,
            modifier.swipeOverride.endX,
            modifier.swipeOverride.endY,
            modifier.swipeOverride.durationMs,
            pointerId = 96
        )
    }

    private fun handleLeftStick(profile: MappingProfile, x: Float, y: Float) {
        val moveX = profile.mappings.find { it.sourceButton == "left_stick_x" && it.enabled }
        val moveY = profile.mappings.find { it.sourceButton == "left_stick_y" && it.enabled }

        if (moveX != null && moveY != null) {
            val targetX = moveX.target.normalizedX + x * 0.15f
            val targetY = moveY.target.normalizedY + y * 0.15f
            touchInjector.touchMove(10, targetX.coerceIn(0.05f, 0.95f), targetY.coerceIn(0.05f, 0.95f))
        }
    }

    private fun handleRightStick(profile: MappingProfile, x: Float, y: Float) {
        val camX = profile.mappings.find { it.sourceButton == "right_stick_x" && it.enabled }
        val camY = profile.mappings.find { it.sourceButton == "right_stick_y" && it.enabled }

        if (camX != null && camY != null) {
            val targetX = camX.target.normalizedX + x * 0.2f
            val targetY = camY.target.normalizedY + y * 0.2f
            touchInjector.touchMove(11, targetX.coerceIn(0.05f, 0.95f), targetY.coerceIn(0.05f, 0.95f))
        }
    }

    fun handleButtonTap(button: String) {
        val profile = currentProfile ?: return
        val mapping = profile.mappings.find {
            it.sourceButton == button && it.enabled
        } ?: return

        when (mapping.actionType) {
            "tap" -> {
                touchInjector.tap(
                    mapping.target.normalizedX,
                    mapping.target.normalizedY,
                    50,
                    pointerId = button.hashCode() % 90
                )
            }
            "swipe" -> {
                mapping.swipeConfig?.let { config ->
                    touchInjector.smoothSwipe(
                        config.startX, config.startY,
                        config.endX, config.endY,
                        config.durationMs,
                        pointerId = button.hashCode() % 90
                    )
                }
            }
            "hold" -> {
                touchInjector.touchDown(
                    button.hashCode() % 90,
                    mapping.target.normalizedX,
                    mapping.target.normalizedY
                )
            }
            "macro" -> {
                mapping.macroConfig?.let { config ->
                    executeMacro(config)
                }
            }
        }
    }

    private fun executeMacro(config: MacroConfig) {
        var delay = 0L
        for ((index, step) in config.steps.withIndex()) {
            delay += step.delayMs
            val pointerId = 80 + index
            when (step.actionType) {
                "tap" -> {
                    touchInjector.tap(
                        step.target.normalizedX,
                        step.target.normalizedY,
                        30,
                        pointerId
                    )
                }
                "swipe" -> {
                    touchInjector.smoothSwipe(
                        step.target.normalizedX,
                        step.target.normalizedY,
                        step.target.normalizedX + 0.05f,
                        step.target.normalizedY - 0.05f,
                        40,
                        pointerId
                    )
                }
            }
        }
    }

    fun reset() {
        activeModifiers.clear()
        comboTracker.reset()
    }

    private class ComboTracker {
        private var lastPrimaryTime = 0L
        private var lastModifierTime = 0L
        private val comboWindowMs = 200L

        fun trackPrimary(button: String) {
            lastPrimaryTime = System.currentTimeMillis()
        }

        fun trackModifier(button: String) {
            lastModifierTime = System.currentTimeMillis()
        }

        fun isInComboWindow(): Boolean {
            val now = System.currentTimeMillis()
            return (now - lastPrimaryTime < comboWindowMs) || (now - lastModifierTime < comboWindowMs)
        }

        fun reset() {
            lastPrimaryTime = 0L
            lastModifierTime = 0L
        }
    }
}
