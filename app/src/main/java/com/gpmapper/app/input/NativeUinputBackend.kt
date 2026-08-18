package com.gpmapper.app.input

import android.content.Context
import android.util.Log
import java.io.File

class NativeUinputBackend : InjectionBackend {

    companion object {
        private const val TAG = "NativeUinputBackend"
        private const val UINPUT_PATH = "/dev/uinput"

        init {
            try {
                System.loadLibrary("gpmapper_native")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    override val name: String = "Native uinput"
    override var isAvailable: Boolean = false
        private set

    private var nativeHandle: Long = 0L
    private var totalInjected = 0L
    private var failedInjected = 0L
    private var lastError: String? = null
    private var startTimeMs = 0L

    fun checkAvailability(): Boolean {
        return try {
            val file = File(UINPUT_PATH)
            file.exists() && file.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    override fun initialize(context: Context): Boolean {
        startTimeMs = System.currentTimeMillis()
        return try {
            if (!checkAvailability()) {
                lastError = "/dev/uinput not accessible (requires root or shell UID)"
                isAvailable = false
                return false
            }

            nativeHandle = nativeCreate()
            if (nativeHandle == 0L) {
                lastError = "Failed to create native core"
                isAvailable = false
                return false
            }

            if (!nativeInitialize(nativeHandle)) {
                lastError = "Failed to initialize uinput device"
                nativeDestroy(nativeHandle)
                nativeHandle = 0L
                isAvailable = false
                return false
            }

            val display = context.resources.displayMetrics
            nativeSetScreenDimensions(nativeHandle, display.widthPixels, display.heightPixels)

            isAvailable = true
            lastError = null
            Log.i(TAG, "Native uinput backend initialized (${display.widthPixels}x${display.heightPixels})")
            true
        } catch (e: Exception) {
            lastError = e.message
            isAvailable = false
            Log.e(TAG, "Failed to initialize native uinput backend", e)
            false
        }
    }

    override fun injectTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        if (nativeHandle == 0L) return false
        return try {
            val result = nativeInjectTouch(nativeHandle, pointerId, x, y, pressure)
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
        if (nativeHandle == 0L) return false
        return try {
            val result = nativeInjectTouchUp(nativeHandle, pointerId)
            if (result) totalInjected++ else failedInjected++
            result
        } catch (e: Exception) {
            failedInjected++
            lastError = e.message
            false
        }
    }

    override fun injectMultiTouch(events: Array<InjectionBackend.TouchEvent>): Boolean {
        if (nativeHandle == 0L) return false
        return try {
            val nativeEvents = events.map {
                NativeTouchEvent(it.id, it.x, it.y, it.pressure, it.active)
            }.toTypedArray()
            val result = nativeInjectMultiTouch(nativeHandle, nativeEvents)
            if (result) totalInjected++ else failedInjected++
            result
        } catch (e: Exception) {
            failedInjected++
            lastError = e.message
            false
        }
    }

    override fun shutdown() {
        if (nativeHandle != 0L) {
            try {
                nativeShutdown(nativeHandle)
                nativeDestroy(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down native core", e)
            }
            nativeHandle = 0L
        }
        isAvailable = false
        Log.i(TAG, "Native uinput backend shut down")
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

    private external fun nativeCreate(): Long
    private external fun nativeInitialize(handle: Long): Boolean
    private external fun nativeShutdown(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeInjectTouch(handle: Long, pointerId: Int, x: Float, y: Float, pressure: Float): Boolean
    private external fun nativeInjectTouchUp(handle: Long, pointerId: Int): Boolean
    private external fun nativeSetScreenDimensions(handle: Long, width: Int, height: Int)
    private external fun nativeInjectMultiTouch(handle: Long, events: Array<NativeTouchEvent>): Boolean
}

data class NativeTouchEvent(
    val id: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val active: Boolean
)
