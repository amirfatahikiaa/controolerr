package com.gpmapper.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class GPMapperApp : Application() {

    companion object {
        const val TAG = "GPMapperApp"
        const val MAPPING_CHANNEL_ID = "mapping_service"
        const val OVERLAY_CHANNEL_ID = "overlay_service"

        var shizukuRunning: Boolean = false
            private set
        var shizukuAuthorized: Boolean = false
            private set
    }

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.Initializing)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    sealed class ShizukuState {
        data object Initializing : ShizukuState()
        data class NotRunning(val msg: String = "Shizuku service not running") : ShizukuState()
        data class RunningNotAuthorized(val msg: String = "Binder connected, permission not granted") : ShizukuState()
        data class Active(val msg: String = "Shizuku active and authorized") : ShizukuState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        initializeShizuku()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val mappingChannel = NotificationChannel(
            MAPPING_CHANNEL_ID,
            "Mapping Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Gamepad mapping service"
            setShowBadge(false)
        }

        val overlayChannel = NotificationChannel(
            OVERLAY_CHANNEL_ID,
            "Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Overlay configurator"
            setShowBadge(false)
        }

        manager.createNotificationChannel(mappingChannel)
        manager.createNotificationChannel(overlayChannel)
    }

    private fun initializeShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Log.i(TAG, "Shizuku listeners registered (sticky binder)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku listeners", e)
            _shizukuState.value = ShizukuState.NotRunning("Listener registration failed: ${e.message}")
        }
    }

    fun checkShizukuRunning(): Boolean {
        return try {
            val result = Shizuku.pingBinder()
            Log.i(TAG, "pingBinder() returned $result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "pingBinder() threw: ${e.message}")
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            Log.i(TAG, "requestShizukuPermission called, checking permission...")
            val checkResult = Shizuku.checkSelfPermission()
            Log.i(TAG, "checkSelfPermission returned: $checkResult (GRANTED=${PackageManager.PERMISSION_GRANTED})")

            if (checkResult != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Calling Shizuku.requestPermission(requestCode=$requestCode)")
                Shizuku.requestPermission(requestCode)
            } else {
                Log.i(TAG, "Permission already granted, setting authorized=true")
                shizukuAuthorized = true
                _shizukuState.value = ShizukuState.Active()
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Shizuku binder not connected — cannot request permission: ${e.message}")
            _shizukuState.value = ShizukuState.NotRunning("Binder not connected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission: ${e.message}", e)
            _shizukuState.value = ShizukuState.NotRunning("Error: ${e.message}")
        }
    }

    fun refreshShizukuState() {
        val running = checkShizukuRunning()
        shizukuRunning = running

        if (running) {
            try {
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                shizukuAuthorized = granted
                _shizukuState.value = if (granted) ShizukuState.Active() else ShizukuState.RunningNotAuthorized()
            } catch (e: Exception) {
                shizukuAuthorized = false
                _shizukuState.value = ShizukuState.RunningNotAuthorized("checkSelfPermission failed: ${e.message}")
            }
        } else {
            shizukuAuthorized = false
            _shizukuState.value = ShizukuState.NotRunning()
        }
        Log.i(TAG, "refreshShizukuState: running=$running authorized=$shizukuAuthorized state=${_shizukuState.value}")
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — checking permission...")
        shizukuRunning = true
        try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            shizukuAuthorized = granted
            _shizukuState.value = if (granted) ShizukuState.Active() else ShizukuState.RunningNotAuthorized()
            Log.i(TAG, "Binder received: authorized=$granted state=${_shizukuState.value}")
        } catch (e: Exception) {
            shizukuAuthorized = false
            _shizukuState.value = ShizukuState.RunningNotAuthorized("checkSelfPermission threw: ${e.message}")
            Log.e(TAG, "Binder received but checkSelfPermission failed", e)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuRunning = false
        shizukuAuthorized = false
        _shizukuState.value = ShizukuState.NotRunning("Binder died")
        Log.w(TAG, "Shizuku binder dead")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            shizukuAuthorized = granted
            if (granted) {
                _shizukuState.value = ShizukuState.Active()
            } else {
                _shizukuState.value = ShizukuState.RunningNotAuthorized("Permission denied by user")
            }
            Log.i(TAG, "Permission result: granted=$granted requestCode=$requestCode state=${_shizukuState.value}")
        }
}
