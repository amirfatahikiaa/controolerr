package com.gpmapper.app.input

import android.content.Context
import android.util.Log

interface InjectionBackend {
    val name: String
    val isAvailable: Boolean
    fun initialize(context: Context): Boolean
    fun injectTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean
    fun injectTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean
    fun injectTouchUp(pointerId: Int): Boolean
    fun injectMultiTouch(events: Array<TouchEvent>): Boolean
    fun shutdown()
    fun getDiagnostics(): BackendDiagnostics

    data class TouchEvent(
        val id: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val active: Boolean
    )

    data class BackendDiagnostics(
        val backendName: String,
        val available: Boolean,
        val totalInjected: Long,
        val failedInjected: Long,
        val lastError: String?,
        val uptimeMs: Long
    )

    companion object {
        private const val TAG = "InjectionBackend"

        fun detectAvailableBackends(context: Context): List<InjectionBackend> {
            val backends = mutableListOf<InjectionBackend>()

            val shizukuBackend = ShizukuDaemonBackend()
            if (shizukuBackend.isAvailable) {
                backends.add(shizukuBackend)
            }

            val nativeBackend = NativeUinputBackend()
            if (nativeBackend.checkAvailability()) {
                backends.add(nativeBackend)
            }

            backends.add(AccessibilityServiceBackend())

            Log.i(TAG, "Detected ${backends.size} backends: ${backends.map { it.name }}")
            return backends
        }
    }
}
