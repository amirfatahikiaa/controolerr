package com.gpmapper.app.model

data class MappingProfile(
    val id: String,
    val name: String,
    val packageName: String,
    val mappings: List<ButtonMapping>,
    val gestureModifiers: List<GestureModifier> = emptyList(),
    val analogConfig: AnalogConfig = AnalogConfig()
) {
    companion object {
        fun createDefault(): MappingProfile {
            return MappingProfile(
                id = "fc_mobile_default",
                name = "FC Mobile Default",
                packageName = "com.ea.gp.fifamobile",
                mappings = listOf(
                    ButtonMapping("cross", "tap", ScreenTarget(0.85f, 0.82f, 0.06f, 0.06f)),
                    ButtonMapping("circle", "tap", ScreenTarget(0.90f, 0.75f, 0.06f, 0.06f)),
                    ButtonMapping("square", "tap", ScreenTarget(0.78f, 0.75f, 0.06f, 0.06f)),
                    ButtonMapping("triangle", "tap", ScreenTarget(0.85f, 0.68f, 0.06f, 0.06f)),
                    ButtonMapping("dpad_up", "swipe", ScreenTarget(0.15f, 0.85f, 0.04f, 0.04f),
                        SwipeConfig(0.15f, 0.85f, 0.15f, 0.75f, 30)),
                    ButtonMapping("dpad_down", "swipe", ScreenTarget(0.15f, 0.85f, 0.04f, 0.04f),
                        SwipeConfig(0.15f, 0.85f, 0.15f, 0.95f, 30)),
                    ButtonMapping("dpad_left", "swipe", ScreenTarget(0.15f, 0.85f, 0.04f, 0.04f),
                        SwipeConfig(0.15f, 0.85f, 0.05f, 0.85f, 30)),
                    ButtonMapping("dpad_right", "swipe", ScreenTarget(0.15f, 0.85f, 0.04f, 0.04f),
                        SwipeConfig(0.15f, 0.85f, 0.25f, 0.85f, 30)),
                    ButtonMapping("right_stick_x", "camera_pan_x", ScreenTarget(0.5f, 0.5f, 0.0f, 0.0f)),
                    ButtonMapping("right_stick_y", "camera_pan_y", ScreenTarget(0.5f, 0.5f, 0.0f, 0.0f)),
                    ButtonMapping("l2", "hold", ScreenTarget(0.08f, 0.80f, 0.08f, 0.04f)),
                    ButtonMapping("r2", "hold", ScreenTarget(0.92f, 0.80f, 0.08f, 0.04f)),
                    ButtonMapping("l1", "tap", ScreenTarget(0.08f, 0.60f, 0.08f, 0.04f)),
                    ButtonMapping("r1", "tap", ScreenTarget(0.92f, 0.60f, 0.08f, 0.04f))
                ),
                gestureModifiers = listOf(
                    GestureModifier("l1", "circle", "chip_shot",
                        SwipeConfig(0.90f, 0.75f, 0.90f, 0.65f, 40)),
                    GestureModifier("r1", "circle", "finesse_shot",
                        SwipeConfig(0.90f, 0.75f, 0.80f, 0.65f, 40)),
                    GestureModifier("right_stick", "cross", "knock_header",
                        SwipeConfig(0.85f, 0.82f, 0.85f, 0.72f, 35))
                )
            )
        }

        fun createMobaDefault(): MappingProfile {
            return MappingProfile(
                id = "moba_default",
                name = "MOBA Default",
                packageName = "com.tencent.lolm",
                mappings = listOf(
                    ButtonMapping("left_stick_x", "move_x", ScreenTarget(0.15f, 0.75f, 0.0f, 0.0f)),
                    ButtonMapping("left_stick_y", "move_y", ScreenTarget(0.15f, 0.75f, 0.0f, 0.0f)),
                    ButtonMapping("cross", "tap", ScreenTarget(0.85f, 0.78f, 0.06f, 0.06f)),
                    ButtonMapping("circle", "tap", ScreenTarget(0.78f, 0.85f, 0.06f, 0.06f)),
                    ButtonMapping("square", "tap", ScreenTarget(0.92f, 0.85f, 0.06f, 0.06f)),
                    ButtonMapping("triangle", "tap", ScreenTarget(0.85f, 0.92f, 0.06f, 0.06f)),
                    ButtonMapping("r1", "tap", ScreenTarget(0.92f, 0.70f, 0.06f, 0.06f)),
                    ButtonMapping("r2", "tap", ScreenTarget(0.85f, 0.63f, 0.06f, 0.06f)),
                    ButtonMapping("right_stick_x", "aim_x", ScreenTarget(0.5f, 0.5f, 0.0f, 0.0f)),
                    ButtonMapping("right_stick_y", "aim_y", ScreenTarget(0.5f, 0.5f, 0.0f, 0.0f))
                )
            )
        }
    }
}

data class ButtonMapping(
    val sourceButton: String,
    val actionType: String,
    val target: ScreenTarget,
    val swipeConfig: SwipeConfig? = null,
    val macroConfig: MacroConfig? = null,
    val holdDurationMs: Int = 0,
    val enabled: Boolean = true
)

data class ScreenTarget(
    val normalizedX: Float,
    val normalizedY: Float,
    val widthFraction: Float,
    val heightFraction: Float
)

data class SwipeConfig(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Int = 40
)

data class MacroConfig(
    val steps: List<MacroStep>,
    val intervalMs: Int = 30
)

data class MacroStep(
    val actionType: String,
    val target: ScreenTarget,
    val delayMs: Int = 0
)

data class GestureModifier(
    val primaryButton: String,
    val modifierButton: String,
    val gestureName: String,
    val swipeOverride: SwipeConfig
)

data class AnalogConfig(
    val leftStickDeadzone: Float = 0.08f,
    val rightStickDeadzone: Float = 0.08f,
    val leftStickExponent: Float = 2.0f,
    val rightStickExponent: Float = 2.0f,
    val leftStickSensitivity: Float = 1.0f,
    val rightStickSensitivity: Float = 1.0f,
    val smoothingFactor: Float = 0.7f
)
