package com.example.poxi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service for Poxi Voice Assistant.
 * Holds the microphone foreground-service type to allow continuous voice interaction
 * even when the user switches apps or background activities.
 */
class PoxiVoiceService : Service() {

    companion object {
        private const val TAG = "PoxiVoiceService"
        const val CHANNEL_ID = "poxi_voice_assistant_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.poxi.action.START_VOICE_SERVICE"
        const val ACTION_STOP = "com.example.poxi.action.STOP_VOICE_SERVICE"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        var onStopActionTriggered: (() -> Unit)? = null

        fun start(context: Context) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                Log.w(TAG, "Cannot start PoxiVoiceService: RECORD_AUDIO permission not granted")
                return
            }

            try {
                val intent = Intent(context, PoxiVoiceService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting PoxiVoiceService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, PoxiVoiceService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping PoxiVoiceService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.d(TAG, "Stop action received in PoxiVoiceService")
            _isServiceActive.value = false
            onStopActionTriggered?.invoke()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Starting foreground PoxiVoiceService")
        _isServiceActive.value = true

        val notification = buildForegroundNotification()

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground with microphone type, falling back", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Poxi Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Poxi voice assistant is actively listening"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PoxiVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Poxi Voice Assistant Active")
            .setContentText("Listening for your voice commands • Tap to open")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Voice Mode",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        stopForegroundCompat()
        Log.d(TAG, "PoxiVoiceService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
