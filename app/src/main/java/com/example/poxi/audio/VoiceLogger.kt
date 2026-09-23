package com.example.poxi.audio

import android.util.Log

/**
 * Centralized, safe logger for Poxi voice & audio operations.
 * Strictly avoids logging sensitive user data, API keys, phone numbers, contact data,
 * private messages, or audio contents.
 */
object VoiceLogger {

    private const val TAG = "PoxiVoice"

    fun logSessionStart() {
        Log.i(TAG, "VOICE_SESSION_START")
    }

    fun logSessionStop() {
        Log.i(TAG, "VOICE_SESSION_STOP")
    }

    fun logListenStart() {
        Log.d(TAG, "LISTEN_START")
    }

    fun logListenStop() {
        Log.d(TAG, "LISTEN_STOP")
    }

    fun logSpeechTimeout() {
        Log.d(TAG, "SPEECH_TIMEOUT")
    }

    fun logUserInterrupted() {
        Log.i(TAG, "USER_INTERRUPTED")
    }

    fun logAudioInputStart() {
        Log.d(TAG, "AUDIO_INPUT_START")
    }

    fun logAudioInputStop() {
        Log.d(TAG, "AUDIO_INPUT_STOP")
    }

    fun logAudioOutputStart() {
        Log.d(TAG, "AUDIO_OUTPUT_START")
    }

    fun logAudioOutputStop() {
        Log.d(TAG, "AUDIO_OUTPUT_STOP")
    }

    fun logLanguageDetected(language: String) {
        // Safe: logs only the category (Hindi, English, Hinglish, etc.), not the private text
        Log.d(TAG, "LANGUAGE_DETECTED: $language")
    }

    fun logResponseLanguage(language: String) {
        Log.d(TAG, "RESPONSE_LANGUAGE: $language")
    }

    fun logGeminiSessionState(state: String) {
        Log.d(TAG, "GEMINI_SESSION_STATE: $state")
    }
}
