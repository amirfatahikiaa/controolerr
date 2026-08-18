package com.gpmapper.app.input

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuDaemonBackend : InjectionBackend {

    companion object {
        private const val TAG = "ShizukuDaemonBackend"
    }

    override val name: String = "Shizuku Daemon"
    override var isAvailable: Boolean = false
        private set

    private var totalInjected = 0L
    private var failedInjected = 0L
    private var lastError: String? = null
    private var startTimeMs = 0L
    private var daemonProcess: Process? = null

    fun checkAvailability(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available", e)
            false
        }
    }

    override fun initialize(context: Context): Boolean {
        startTimeMs = System.currentTimeMillis()
        return try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku not running"
                isAvailable = false
                return false
            }

            val permission = Shizuku.checkSelfPermission()
            if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastError = "Shizuku permission not granted"
                isAvailable = false
                return false
            }

            isAvailable = true
            lastError = null
            Log.i(TAG, "Shizuku backend initialized")
            true
        } catch (e: Exception) {
            lastError = e.message
            isAvailable = false
            Log.e(TAG, "Failed to initialize Shizuku backend", e)
            false
        }
    }

    override fun injectTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return injectViaShizuku("touch_down", pointerId, x, y, pressure)
    }

    override fun injectTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return injectViaShizuku("touch_move", pointerId, x, y, pressure)
    }

    override fun injectTouchUp(pointerId: Int): Boolean {
        return injectViaShizuku("touch_up", pointerId, 0f, 0f, 0f)
    }

    override fun injectMultiTouch(events: Array<InjectionBackend.TouchEvent>): Boolean {
        var allSuccess = true
        for (event in events) {
            val success = if (event.active) {
                injectTouchMove(event.id, event.x, event.y, event.pressure)
            } else {
                injectTouchUp(event.id)
            }
            if (!success) allSuccess = false
        }
        return allSuccess
    }

    private fun injectViaShizuku(action: String, pointerId: Int, x: Float, y: Float, pressure: Float): Boolean {
        return try {
            val cmd = arrayOf(
                "sh", "-c",
                "input touchscreen tap ${(x * 1080).toInt()} ${(y * 2340).toInt()}"
            )
            val process = Shizuku.newProcess(cmd, null, null)
            val exitCode = process.waitFor()
            totalInjected++
            if (exitCode != 0) {
                failedInjected++
                lastError = "Shell command exited with code $exitCode"
                false
            } else {
                true
            }
        } catch (e: Exception) {
            failedInjected++
            lastError = e.message
            Log.e(TAG, "Shizuku injection failed", e)
            false
        }
    }

    override fun shutdown() {
        isAvailable = false
        daemonProcess?.destroy()
        daemonProcess = null
        Log.i(TAG, "Shizuku backend shut down")
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
