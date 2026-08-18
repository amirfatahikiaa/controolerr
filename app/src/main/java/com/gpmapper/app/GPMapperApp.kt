package com.gpmapper.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
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
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku listeners", e)
        }
    }

    fun checkShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            } else {
                shizukuAuthorized = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuRunning = true
        Log.i(TAG, "Shizuku binder received")
        try {
            shizukuAuthorized = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            shizukuAuthorized = false
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuRunning = false
        shizukuAuthorized = false
        Log.w(TAG, "Shizuku binder dead")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            shizukuAuthorized = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku permission result: granted=$shizukuAuthorized (requestCode=$requestCode)")
        }
}
