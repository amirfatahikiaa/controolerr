package com.gpmapper.app.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

class AnalogProcessor {

    data class StickOutput(
        val x: Float = 0f,
        val y: Float = 0f,
        val magnitude: Float = 0f,
        val angle: Float = 0f
    )

    private var deadzoneInner = 0.08f
    private var deadzoneOuter = 1.0f
    private var responseExponent = 2.0f
    private var sensitivity = 1.0f
    private var smoothingFactor = 0.7f

    private var filteredX = 0f
    private var filteredY = 0f

    fun configure(
        deadzoneInner: Float = 0.08f,
        deadzoneOuter: Float = 1.0f,
        responseExponent: Float = 2.0f,
        sensitivity: Float = 1f,
        smoothingFactor: Float = 0.7f
    ) {
        this.deadzoneInner = deadzoneInner
        this.deadzoneOuter = deadzoneOuter
        this.responseExponent = responseExponent
        this.sensitivity = sensitivity
        this.smoothingFactor = smoothingFactor
    }

    fun process(rawX: Float, rawY: Float): StickOutput {
        val dx = applyDeadzone(rawX)
        val dy = applyDeadzone(rawY)

        val curvedX = applyResponseCurve(dx) * sensitivity
        val curvedY = applyResponseCurve(dy) * sensitivity

        val clampedX = curvedX.coerceIn(-1f, 1f)
        val clampedY = curvedY.coerceIn(-1f, 1f)

        filteredX = smooth(clampedX, filteredX, smoothingFactor)
        filteredY = smooth(clampedY, filteredY, smoothingFactor)

        val magnitude = sqrt(filteredX * filteredX + filteredY * filteredY).coerceAtMost(1f)
        val angle = kotlin.math.atan2(filteredY.toDouble(), filteredX.toDouble()).toFloat()

        return StickOutput(filteredX, filteredY, magnitude, angle)
    }

    fun processLeftStick(rawX: Float, rawY: Float): StickOutput = process(rawX, rawY)
    fun processRightStick(rawX: Float, rawY: Float): StickOutput = process(rawX, rawY)

    fun reset() {
        filteredX = 0f
        filteredY = 0f
    }

    private fun applyDeadzone(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude < deadzoneInner) return 0f
        if (magnitude > deadzoneOuter) return value.sign
        val normalized = (magnitude - deadzoneInner) / (deadzoneOuter - deadzoneInner)
        return normalized.sign * normalized.coerceAtMost(1f)
    }

    private fun applyResponseCurve(value: Float): Float {
        val magnitude = abs(value)
        val curved = magnitude.pow(responseExponent)
        return curved.sign * value.sign
    }

    private fun smooth(current: Float, previous: Float, factor: Float): Float {
        return previous * factor + current * (1f - factor)
    }

    companion object {
        fun lerp(start: Float, end: Float, t: Float): Float {
            return start + (end - start) * t.coerceIn(0f, 1f)
        }

        fun lerpPoint(
            startX: Float, startY: Float,
            endX: Float, endY: Float,
            t: Float
        ): Pair<Float, Float> {
            return Pair(lerp(startX, endX, t), lerp(startY, endY, t))
        }

        fun cubicBezier(
            p0: Float, p1: Float, p2: Float, p3: Float,
            t: Float
        ): Float {
            val u = 1f - t
            val tt = t * t
            val uu = u * u
            val uuu = uu * u
            val ttt = tt * t
            return uuu * p0 + 3f * uu * t * p1 + 3f * u * tt * p2 + ttt * p3
        }

        fun clampMagnitude(x: Float, y: Float, maxMagnitude: Float): Pair<Float, Float> {
            val magnitude = sqrt(x * x + y * y)
            if (magnitude <= maxMagnitude) return Pair(x, y)
            val scale = maxMagnitude / magnitude
            return Pair(x * scale, y * scale)
        }
    }
}
