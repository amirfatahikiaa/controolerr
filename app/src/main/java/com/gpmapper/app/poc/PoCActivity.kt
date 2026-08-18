package com.gpmapper.app.poc

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*

class PoCActivity : Activity() {

    companion object {
        private const val TAG = "PoCActivity"
        private const val SHIZUKI_PERMISSION_REQUEST = 100
    }

    private lateinit var canvas: VisualTouchCanvas
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var ds4LogView: TextView
    private lateinit var latencyView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var testRunner: InjectionTestRunner
    private lateinit var latencyRecorder: LatencyRecorder
    private val handler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()
    private val ds4LogBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var shizukuBound = false
    private var shizukuAuthorized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latencyRecorder = LatencyRecorder()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121218"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        val titleText = TextView(this).apply {
            text = "IInputManager Shizuku PoC"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        headerLayout.addView(titleText)

        statusView = TextView(this).apply {
            text = buildDeviceInfoString()
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 11f
            setPadding(0, 8, 0, 0)
        }
        headerLayout.addView(statusView)
        rootLayout.addView(headerLayout)

        val controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        val runTestBtn = Button(this).apply {
            text = "Run Injection Test"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setOnClickListener { runTests() }
        }
        controlLayout.addView(runTestBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = 8 })

        val clearBtn = Button(this).apply {
            text = "Clear"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#424242"))
            setOnClickListener { clearAll() }
        }
        controlLayout.addView(clearBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        rootLayout.addView(controlLayout)

        canvas = VisualTouchCanvas(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A14"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rootLayout.addView(canvas)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }

        val testLogHeader = TextView(this).apply {
            text = "--- Injection Test Log ---"
            setTextColor(Color.parseColor("#FF5722"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        logContainer.addView(testLogHeader)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logContainer.addView(logView)

        val ds4Header = TextView(this).apply {
            text = "\n--- DS4 Controller Log ---"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 0)
        }
        logContainer.addView(ds4Header)

        ds4LogView = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logContainer.addView(ds4LogView)

        val latencyHeader = TextView(this).apply {
            text = "\n--- Latency Measurements ---"
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 0)
        }
        logContainer.addView(latencyHeader)

        latencyView = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "No latency data yet."
        }
        logContainer.addView(latencyView)

        scrollView.addView(logContainer)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        testRunner = InjectionTestRunner(
            canvas = canvas,
            onResult = { result -> handler.post { onTestResult(result) } },
            onLatencySample = { sample -> handler.post { onLatencySample(sample) } }
        )

        canvas.onTouchReceived = { record ->
            val tag = if (record.isInjected) "INJ" else "PHY"
            appendDs4Log("[$tag] P${record.pointerId} ${record.action} " +
                    "(%.1f,%.1f) eventTime=${record.eventTimeMs}".format(record.x, record.y))
        }

        initShizuku()
    }

    private fun buildDeviceInfoString(): String {
        return buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Process UID: ${android.os.Process.myUid()}")
            appendLine("Shizuku bound: $shizukuBound | authorized: $shizukuAuthorized")
            append("Build: ${Build.DISPLAY}")
        }
    }

    private fun initShizuku() {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)

            shizukuBound = try { Shizuku.pingBinder() } catch (e: Exception) { false }
            if (shizukuBound) {
                shizukuAuthorized = try {
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) { false }
            }
        } catch (e: Exception) {
            appendLog("Shizuku init failed: ${e.message}")
        }
        updateStatus()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuBound = true
        appendLog("Shizuku binder received")
        updateStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBound = false
        shizukuAuthorized = false
        appendLog("Shizuku binder dead")
        updateStatus()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuAuthorized = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            appendLog("Shizuku permission: ${if (shizukuAuthorized) "GRANTED" else "DENIED"}")
            updateStatus()
        }

    @Deprecated("Use registerForActivityResult")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKI_PERMISSION_REQUEST) {
            shizukuAuthorized = grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            appendLog("Shizuku permission result: $shizukuAuthorized")
            updateStatus()
        }
    }

    private fun updateStatus() {
        statusView.text = buildDeviceInfoString()
    }

    private fun runTests() {
        Log.i(TAG, "runTests() called, shizukuBound=$shizukuBound, shizukuAuthorized=$shizukuAuthorized")

        if (testRunner.isRunning()) {
            appendLog("Tests already running...")
            return
        }

        if (!shizukuBound) {
            appendLog("ERROR: Shizuku not bound. Install and start Shizuku first.")
            return
        }

        if (!shizukuAuthorized) {
            appendLog("Requesting Shizuku permission...")
            try {
                Shizuku.requestPermission(SHIZUKI_PERMISSION_REQUEST)
            } catch (e: Exception) {
                appendLog("Failed to request Shizuku permission: ${e.message}")
            }
            return
        }

        appendLog("=== Starting Injection Tests ===")
        appendLog("Shizuku: bound=$shizukuBound authorized=$shizukuAuthorized")
        try {
            testRunner.runAllTests()
        } catch (e: Exception) {
            Log.e(TAG, "runAllTests threw synchronously", e)
            appendLog("FATAL: runAllTests threw: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun clearAll() {
        logBuffer.clear()
        ds4LogBuffer.clear()
        logView.text = ""
        ds4LogView.text = ""
        latencyView.text = "No latency data yet."
        canvas.clearRecords()
        latencyRecorder.clear()
        appendLog("Cleared all logs and canvas")
    }

    private fun onTestResult(result: InjectionTestRunner.TestResult) {
        appendLog("\n--- ${result.testName} ---")
        appendLog("Classification: ${result.classification}")
        appendLog("Overall success: ${result.overallSuccess}")

        for (step in result.steps) {
            val status = if (step.success) "OK" else "FAIL"
            appendLog("  [$status] ${step.name}: ${step.details}")
            if (step.exception != null) {
                appendLog("    Exception: ${step.exception.javaClass.name}: ${step.exception.message}")
            }
        }
    }

    private fun onLatencySample(sample: InjectionTestRunner.LatencySample) {
        latencyRecorder.recordFromTestResult(sample)
        updateLatencyDisplay()
    }

    private fun updateLatencyDisplay() {
        val stats = latencyRecorder.getStats()
        if (stats.isEmpty()) {
            latencyView.text = "No latency data yet."
            return
        }

        latencyView.text = buildString {
            for (stat in stats) {
                appendLine(stat.toString())
            }
            appendLine("=== IMPORTANT ===")
            appendLine("Binder-return latency (time for injectInputEvent() to return)")
            appendLine("is NOT the same as end-to-end input-to-screen latency.")
            appendLine("Actual touch-to-display latency includes Android input pipeline,")
            appendLine("window manager dispatch, and application rendering.")
            appendLine("Physical measurement on the target device is required.")
        }
    }

    private fun appendLog(msg: String) {
        val time = dateFormat.format(Date())
        logBuffer.appendLine("[$time] $msg")
        if (logBuffer.length > 8000) {
            logBuffer.delete(0, logBuffer.length - 6000)
        }
        logView.text = logBuffer.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        Log.d(TAG, msg)
    }

    private fun appendDs4Log(msg: String) {
        val time = dateFormat.format(Date())
        ds4LogBuffer.appendLine("[$time] $msg")
        if (ds4LogBuffer.length > 4000) {
            ds4LogBuffer.delete(0, ds4LogBuffer.length - 3000)
        }
        ds4LogView.text = ds4LogBuffer.toString()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        val deviceName = device?.name ?: "unknown"
        val vendorId = device?.vendorId ?: 0
        val productId = device?.productId ?: 0
        val source = event.source

        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "OTHER(${event.action})"
        }

        appendDs4Log("KEY $actionName code=$keyName(0x${Integer.toHexString(event.keyCode)}) " +
                "device=$deviceName vendor=0x${vendorId.toString(16)} " +
                "product=0x${productId.toString(16)} source=0x${Integer.toHexString(source)} " +
                "repeat=${event.repeatCount}")

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A && event.action == KeyEvent.ACTION_DOWN) {
            appendLog("DS4 Cross pressed - triggering injection test")
            runTests()
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) {

            val device = event.device
            val deviceName = device?.name ?: "unknown"
            val vendorId = device?.vendorId ?: 0
            val productId = device?.productId ?: 0

            val axisValues = buildString {
                val axes = listOf(
                    "AXIS_X" to MotionEvent.AXIS_X,
                    "AXIS_Y" to MotionEvent.AXIS_Y,
                    "AXIS_Z" to MotionEvent.AXIS_Z,
                    "AXIS_RZ" to MotionEvent.AXIS_RZ,
                    "AXIS_HAT_X" to MotionEvent.AXIS_HAT_X,
                    "AXIS_HAT_Y" to MotionEvent.AXIS_HAT_Y,
                    "AXIS_LTRIGGER" to MotionEvent.AXIS_LTRIGGER,
                    "AXIS_RTRIGGER" to MotionEvent.AXIS_RTRIGGER
                )
                for ((name, axis) in axes) {
                    val value = event.getAxisValue(axis)
                    if (kotlin.math.abs(value) > 0.01f) {
                        append("$name=%.2f ".format(value))
                    }
                }
            }

            if (axisValues.isNotBlank()) {
                appendDs4Log("MOTION device=$deviceName vendor=0x${vendorId.toString(16)} " +
                        "product=0x${productId.toString(16)} axes=[$axisValues]")
            }
        }

        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
