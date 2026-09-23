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
import com.example.R
import com.example.poxi.permission.PermissionValidationLayer
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
        @Volatile
        private var isStopRequested = false

        var onStopActionTriggered: (() -> Unit)? = null

        fun start(context: Context) {
            val hasMicPermission = PermissionValidationLayer.hasRecordAudioPermission(context)

            if (!hasMicPermission) {
                Log.w(TAG, "Cannot start PoxiVoiceService: RECORD_AUDIO permission not granted")
                return
            }

            if (_isServiceActive.value) {
                Log.d(TAG, "PoxiVoiceService is already active; skipping duplicate start")
                return
            }

            isStopRequested = false
            _isServiceActive.value = true
            try {
                val intent = Intent(context, PoxiVoiceService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting PoxiVoiceService: ${e.javaClass.simpleName}: ${e.message}", e)
                _isServiceActive.value = false
            }
        }

        fun stop(context: Context) {
            isStopRequested = true
            _isServiceActive.value = false
            try {
                val intent = Intent(context, PoxiVoiceService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping PoxiVoiceService: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action, isStopRequested=$isStopRequested")

        // If stop action received from notification action PendingIntent
        if (action == ACTION_STOP) {
            Log.d(TAG, "Stop action received in PoxiVoiceService notification")
            _isServiceActive.value = false
            onStopActionTriggered?.invoke()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        // CRITICAL ANDROID LIFECYCLE FIX:
        // When ContextCompat.startForegroundService() is called, the OS strictly mandates that
        // startForeground() MUST be called before returning or calling stopSelf().
        // Calling startForeground() first satisfies the Android OS watchdog and prevents
        // Fatal ForegroundServiceDidNotStartInTimeException crashes across Android 8 through 15.
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
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                Log.w(TAG, "Cannot start microphone foreground service on API 34+ without RECORD_AUDIO permission")
                _isServiceActive.value = false
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.javaClass.simpleName}: ${e.message}", e)
            _isServiceActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        // If stop was requested before service started, cleanly stop foreground and exit
        if (isStopRequested) {
            Log.d(TAG, "Stop requested in PoxiVoiceService")
            _isServiceActive.value = false
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_START) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        _isServiceActive.value = true
        return START_NOT_STICKY
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
            .setSmallIcon(R.drawable.ic_poxi_notification)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_poxi_stop,
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
