package com.gpmapper.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.gpmapper.app.GPMapperApp
import com.gpmapper.app.MainActivity
import com.gpmapper.app.R
import com.gpmapper.app.overlay.OverlayManager

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.gpmapper.app.ACTION_START_OVERLAY"
        private const val ACTION_STOP = "com.gpmapper.app.ACTION_STOP_OVERLAY"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted")
                return
            }
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var overlayManager: OverlayManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OverlayService created")
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                showOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
        Log.i(TAG, "OverlayService destroyed")
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show overlay: permission not granted")
            return
        }

        if (overlayManager == null) {
            overlayManager = OverlayManager(this)
        }

        overlayManager?.show()
        Log.i(TAG, "Overlay shown")
    }

    private fun removeOverlay() {
        overlayManager?.hide()
        overlayManager = null
        Log.i(TAG, "Overlay removed")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GPMapperApp.OVERLAY_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
