package com.gpmapper.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import com.gpmapper.app.GPMapperApp
import com.gpmapper.app.MainActivity
import com.gpmapper.app.R
import com.gpmapper.app.input.DualShock4Handler
import com.gpmapper.app.input.GestureEngine
import com.gpmapper.app.input.InjectionBackend
import com.gpmapper.app.input.ShizukuDaemonBackend
import com.gpmapper.app.input.NativeUinputBackend
import com.gpmapper.app.input.AccessibilityServiceBackend
import com.gpmapper.app.input.TouchInjector
import com.gpmapper.app.model.MappingProfile
import com.gpmapper.app.model.ProfileManager
import kotlinx.coroutines.*

class MappingService : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "MappingService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.gpmapper.app.ACTION_START_MAPPING"
        private const val ACTION_STOP = "com.gpmapper.app.ACTION_STOP_MAPPING"

        fun start(context: Context) {
            val intent = Intent(context, MappingService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MappingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var inputManager: InputManager
    private lateinit var dualShock4Handler: DualShock4Handler
    private lateinit var profileManager: ProfileManager
    private lateinit var gestureEngine: GestureEngine
    private var currentProfile: MappingProfile? = null

    private var activeBackend: InjectionBackend? = null
    private var touchInjector: TouchInjector? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var backendDetectionJob: Job? = null
    private var latencyTracker = LatencyTracker()

    private var connectedDs4DeviceId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MappingService created")

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        dualShock4Handler = DualShock4Handler()
        profileManager = ProfileManager(this)

        inputManager.registerInputDeviceListener(this, null)

        val savedProfile = profileManager.getActiveProfileId()?.let { profileManager.loadProfile(it) }
            ?: MappingProfile.createDefault()
        setProfile(savedProfile)

        startForeground(NOTIFICATION_ID, createNotification())
        startBackendDetection()
        scanForConnectedControllers()

        Log.i(TAG, "MappingService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        backendDetectionJob?.cancel()
        inputManager.unregisterInputDeviceListener(this)
        activeBackend?.shutdown()
        touchInjector?.destroy()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "MappingService destroyed")
    }

    private fun startBackendDetection() {
        backendDetectionJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                val backend = detectBestBackend()
                if (backend != null && backend != activeBackend) {
                    switchBackend(backend)
                    break
                }
                delay(1000)
            }
        }
    }

    private fun detectBestBackend(): InjectionBackend? {
        val backends = InjectionBackend.detectAvailableBackends(this)
        return backends.firstOrNull { it.isAvailable }
    }

    private fun switchBackend(backend: InjectionBackend) {
        activeBackend?.shutdown()
        activeBackend = backend

        if (backend.initialize(this)) {
            touchInjector?.destroy()
            touchInjector = TouchInjector(backend)
            gestureEngine = GestureEngine(touchInjector!!)
            currentProfile?.let { gestureEngine.setProfile(it) }
            Log.i(TAG, "Switched to backend: ${backend.name}")
        } else {
            Log.e(TAG, "Failed to initialize backend: ${backend.name}")
            activeBackend = null
        }
    }

    fun registerAccessibilityCallback(callback: AccessibilityServiceBackend.GestureCallback) {
        val backend = AccessibilityServiceBackend()
        backend.setGestureCallback(callback)
        switchBackend(backend)
    }

    private fun setProfile(profile: MappingProfile) {
        currentProfile = profile
        touchInjector?.let { injector ->
            gestureEngine = GestureEngine(injector)
            gestureEngine.setProfile(profile)
        }
        Log.i(TAG, "Profile loaded: ${profile.name}")
    }

    private fun scanForConnectedControllers() {
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            if (dualShock4Handler.isDS4Device(device)) {
                connectedDs4DeviceId = id
                Log.i(TAG, "Found connected DS4: ${device.name} (id=$id)")
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        if (dualShock4Handler.isDS4Device(device)) {
            connectedDs4DeviceId = deviceId
            Log.i(TAG, "DS4 connected: ${device.name} vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (deviceId == connectedDs4DeviceId) {
            connectedDs4DeviceId = -1
            dualShock4Handler.reset()
            Log.i(TAG, "DS4 disconnected")
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (connectedDs4DeviceId < 0) return false
        if (event.deviceId != connectedDs4DeviceId) return false

        val controllerStart = System.nanoTime()
        val state = dualShock4Handler.processMotionEvent(event)
        val mappingStart = System.nanoTime()

        if (state.pressedButtons.isNotEmpty() || state.releasedButtons.isNotEmpty()) {
            for (button in state.pressedButtons) {
                gestureEngine.handleButtonPress(button)
            }
            for (button in state.releasedButtons) {
                gestureEngine.handleButtonRelease(button)
                gestureEngine.handleButtonTap(button)
            }
        }

        if (state.leftStick.magnitude > 0.01f || state.rightStick.magnitude > 0.01f) {
            gestureEngine.handleAnalogInput("left_stick", state.leftStick.x, state.leftStick.y)
            gestureEngine.handleAnalogInput("right_stick", state.rightStick.x, state.rightStick.y)
        }

        if (kotlin.math.abs(state.hatX) > 0.5f || kotlin.math.abs(state.hatY) > 0.5f) {
            gestureEngine.handleDpadInput(state.hatX, state.hatY)
        }

        val injectionStart = System.nanoTime()
        latencyTracker.record(controllerStart, mappingStart, injectionStart)

        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (connectedDs4DeviceId < 0) return false
        if (event.deviceId != connectedDs4DeviceId) return false

        val buttonName = dualShock4Handler.processKeyEvent(event) ?: return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            gestureEngine.handleButtonPress(buttonName)
        } else if (event.action == KeyEvent.ACTION_UP) {
            gestureEngine.handleButtonRelease(buttonName)
            gestureEngine.handleButtonTap(buttonName)
        }

        return true
    }

    fun getLatencyStats(): LatencyTracker.Stats = latencyTracker.getStats()
    fun getBackendDiagnostics(): InjectionBackend.BackendDiagnostics? = activeBackend?.getDiagnostics()

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GPMapperApp.MAPPING_CHANNEL_ID)
            .setContentTitle(getString(R.string.mapping_notification_title))
            .setContentText(getString(R.string.mapping_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    class LatencyTracker {
        private val samples = mutableListOf<Sample>()
        private val maxSamples = 1000

        data class Sample(
            val controllerTimestampNs: Long,
            val mappingTimestampNs: Long,
            val injectionTimestampNs: Long
        )

        data class Stats(
            val sampleCount: Int,
            val avgMappingUs: Float,
            val p50MappingUs: Float,
            val p95MappingUs: Float,
            val p99MappingUs: Float,
            val maxMappingUs: Float,
            val avgInjectionUs: Float,
            val p50InjectionUs: Float,
            val p95InjectionUs: Float,
            val avgE2EUs: Float,
            val p50E2EUs: Float,
            val p95E2EUs: Float
        )

        @Synchronized
        fun record(controllerNs: Long, mappingNs: Long, injectionNs: Long) {
            if (samples.size >= maxSamples) {
                samples.removeAt(0)
            }
            samples.add(Sample(controllerNs, mappingNs, injectionNs))
        }

        @Synchronized
        fun getStats(): Stats {
            if (samples.isEmpty()) {
                return Stats(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }

            val mappingDeltas = samples.map { ((it.mappingTimestampNs - it.controllerTimestampNs) / 1000.0).toFloat() }
            val injectionDeltas = samples.map { ((it.injectionTimestampNs - it.mappingTimestampNs) / 1000.0).toFloat() }
            val e2eDeltas = samples.map { ((it.injectionTimestampNs - it.controllerTimestampNs) / 1000.0).toFloat() }

            return Stats(
                sampleCount = samples.size,
                avgMappingUs = mappingDeltas.average().toFloat(),
                p50MappingUs = percentile(mappingDeltas, 50f),
                p95MappingUs = percentile(mappingDeltas, 95f),
                p99MappingUs = percentile(mappingDeltas, 99f),
                maxMappingUs = mappingDeltas.maxOrNull() ?: 0f,
                avgInjectionUs = injectionDeltas.average().toFloat(),
                p50InjectionUs = percentile(injectionDeltas, 50f),
                p95InjectionUs = percentile(injectionDeltas, 95f),
                avgE2EUs = e2eDeltas.average().toFloat(),
                p50E2EUs = percentile(e2eDeltas, 50f),
                p95E2EUs = percentile(e2eDeltas, 95f)
            )
        }

        private fun percentile(values: List<Float>, p: Float): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val index = (p / 100f * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }
}
