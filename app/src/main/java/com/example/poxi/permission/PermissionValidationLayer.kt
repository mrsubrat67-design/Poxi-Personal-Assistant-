package com.example.poxi.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Dedicated permission validation layer for Poxi.
 * Strictly validates RECORD_AUDIO and other critical device capabilities before
 * initiating audio/Gemini Live sessions or executing native bridge actions.
 */
object PermissionValidationLayer {

    private const val TAG = "PermissionValidation"

    /**
     * Verifies if RECORD_AUDIO permission is currently granted.
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "hasRecordAudioPermission: $granted")
        return granted
    }

    /**
     * Verifies if READ_CONTACTS permission is currently granted.
     */
    fun hasContactsPermission(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        return granted
    }

    /**
     * Verifies if CALL_PHONE permission is currently granted.
     */
    fun hasCallPhonePermission(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        return granted
    }

    sealed class AudioPermissionState {
        object Granted : AudioPermissionState()
        data class Denied(val isPermanentlyDenied: Boolean, val canShowRationale: Boolean) : AudioPermissionState()
    }

    /**
     * Validates RECORD_AUDIO permission state with Activity rationale context.
     */
    fun checkAudioPermissionState(context: Context, activity: Activity? = null): AudioPermissionState {
        if (hasRecordAudioPermission(context)) {
            return AudioPermissionState.Granted
        }

        if (activity != null) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            // If rationale is false but permission is not granted, user likely selected "Don't ask again"
            return AudioPermissionState.Denied(
                isPermanentlyDenied = !shouldShowRationale,
                canShowRationale = shouldShowRationale
            )
        }

        return AudioPermissionState.Denied(
            isPermanentlyDenied = false,
            canShowRationale = true
        )
    }

    /**
     * Opens the application's system settings page so the user can easily toggle permissions.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app settings", e)
        }
    }
}
