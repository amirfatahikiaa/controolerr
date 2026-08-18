package com.gpmapper.app.input

import android.content.Context
import android.util.Log

class AccessibilityServiceBackend : InjectionBackend {

    companion object {
        private const val TAG = "AccessibilityServiceBackend"
    }

    override val name: String = "AccessibilityService"
    override var isAvailable: Boolean = false
        private set

    private var totalInjected = 0L
    private var failedInjected = 0L
    private var lastError: String? = null
    private var startTimeMs = 0L
    private var gestureCallback: GestureCallback? = null

    interface GestureCallback {
        fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean
        fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
        fun dispatchMultiGesture(events: Array<InjectionBackend.TouchEvent>): Boolean
    }

    fun setGestureCallback(callback: GestureCallback) {
        gestureCallback = callback
        isAvailable = true
    }

    override fun initialize(context: Context): Boolean {
        startTimeMs = System.currentTimeMillis()
        isAvailable = gestureCallback != null
        if (!isAvailable) {
            lastError = "No GestureCallback registered (AccessibilityService not connected)"
        }
        return isAvailable
    }

    override fun injectTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        val cb = gestureCallback ?: return false
        return try {
            val result = cb.dispatchTap(x, y, 50)
            if (result) totalInjected++ else failedInjected++
            result
        } catch (e: Exception) {
            failedInjected++
            lastError = e.message
            false
        }
    }

    override fun injectTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return injectTouchDown(pointerId, x, y, pressure)
    }

    override fun injectTouchUp(pointerId: Int): Boolean {
        return true
    }

    override fun injectMultiTouch(events: Array<InjectionBackend.TouchEvent>): Boolean {
        val cb = gestureCallback ?: return false
        return try {
            val result = cb.dispatchMultiGesture(events)
            if (result) totalInjected++ else failedInjected++
            result
        } catch (e: Exception) {
            failedInjected++
            lastError = e.message
            false
        }
    }

    override fun shutdown() {
        isAvailable = false
        gestureCallback = null
        Log.i(TAG, "AccessibilityService backend shut down")
    }

    override fun getDiagnostics(): InjectionBackend.BackendDiagnostics {
        return InjectionBackend.BackendDiagnostics(
            backendName = name,
            available = isAvailable,
            totalInjected = totalInjected,
            failedInjected = failedInjected,
            lastError = lastError,
            uptimeMs = System.currentTimeMillis() - startTimeMs
        )
    }
}
