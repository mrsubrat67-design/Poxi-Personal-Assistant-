package com.example.poxi.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.poxi.permission.PermissionValidationLayer

/**
 * Manages speech recognition for Poxi.
 * Implements stable continuous voice input without repeated ON/OFF microphone toggling,
 * duplicate sessions, or disruptive notification sounds.
 */
class SpeechInputManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInputManager"
        private const val SILENCE_COMPLETE_MS = 4000L
        private const val SILENCE_POSSIBLY_COMPLETE_MS = 3000L
        private const val SILENCE_MINIMUM_MS = 3000L
    }

    enum class RecognizerState {
        IDLE,
        INITIALIZING,
        LISTENING,
        PROCESSING,
        STOPPED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var state: RecognizerState = RecognizerState.IDLE
    private var consecutiveNonFatalErrors = 0

    @Volatile
    private var isContinuousMode = false

    @Volatile
    private var isPaused = false

    private var lastLanguagePreference: String = "hi-IN"

    val isContinuousSessionActive: Boolean
        get() = isContinuousMode && state != RecognizerState.STOPPED

    var onSpeechResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeechTimeout: (() -> Unit)? = null

    val isListening: Boolean
        get() = state == RecognizerState.LISTENING

    val isSessionAlive: Boolean
        get() = state == RecognizerState.LISTENING || state == RecognizerState.PROCESSING || state == RecognizerState.INITIALIZING

    private val recoveryRunnable = Runnable {
        if (isContinuousMode && state != RecognizerState.STOPPED && !isPaused) {
            startListeningInternal(lastLanguagePreference)
        }
    }

    private fun scheduleSilentRecovery(delayMs: Long) {
        mainHandler.removeCallbacks(recoveryRunnable)
        mainHandler.postDelayed(recoveryRunnable, delayMs)
    }

    init {
        runOnMainThread {
            initRecognizer()
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun initRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create SpeechRecognizer directly: ${t.javaClass.simpleName}: ${t.message}")
            speechRecognizer = null
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                state = RecognizerState.LISTENING
                consecutiveNonFatalErrors = 0
                VoiceLogger.logListenStart()
                VoiceLogger.logAudioInputStart()
                onListeningStarted?.invoke()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                state = RecognizerState.LISTENING
                consecutiveNonFatalErrors = 0
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (state == RecognizerState.LISTENING) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    onRmsChanged?.invoke(normalized)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                state = RecognizerState.PROCESSING
                VoiceLogger.logListenStop()
                // Do not prematurely notify UI finished if processing speech
            }

            override fun onError(error: Int) {
                Log.w(TAG, "SpeechRecognizer onError: code=$error, currentState=$state, isContinuousMode=$isContinuousMode")

                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        isContinuousMode = false
                        isPaused = false
                        state = RecognizerState.STOPPED
                        consecutiveNonFatalErrors = 0
                        VoiceLogger.logAudioInputStop()
                        onError?.invoke("Microphone permission required")
                        onListeningFinished?.invoke()
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // User paused or brief silence: normal in continuous conversation
                        state = RecognizerState.IDLE
                        consecutiveNonFatalErrors = 0
                        VoiceLogger.logSpeechTimeout()
                        if (isContinuousMode && !isPaused) {
                            scheduleSilentRecovery(200)
                        } else {
                            onSpeechTimeout?.invoke()
                        }
                    }
                    else -> {
                        // Transient client, network, or recognizer busy events: recover silently
                        state = RecognizerState.IDLE
                        try {
                            speechRecognizer?.cancel()
                        } catch (_: Exception) {}

                        if (isContinuousMode && !isPaused) {
                            scheduleSilentRecovery(300)
                        } else {
                            onListeningFinished?.invoke()
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                state = RecognizerState.PROCESSING
                consecutiveNonFatalErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()
                Log.d(TAG, "SpeechRecognizer onResults: hasInput=${!recognized.isNullOrBlank()}")

                if (!recognized.isNullOrBlank()) {
                    onSpeechResult?.invoke(recognized)
                } else {
                    VoiceLogger.logSpeechTimeout()
                    onSpeechTimeout?.invoke()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    onPartialResult?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Starts continuous listening session.
     * Prevents duplicate sessions or re-arming while already active.
     */
    fun startListening(languagePreference: String = "hi-IN") {
        runOnMainThread {
            isContinuousMode = true
            isPaused = false
            lastLanguagePreference = languagePreference
            startListeningInternal(languagePreference)
        }
    }

    /**
     * Seamlessly resumes listening after Poxi finishes speaking or user interrupts.
     */
    fun resumeListening() {
        runOnMainThread {
            if (!isContinuousMode) return@runOnMainThread
            isPaused = false
            mainHandler.removeCallbacks(recoveryRunnable)
            if (state != RecognizerState.LISTENING && state != RecognizerState.INITIALIZING) {
                startListeningInternal(lastLanguagePreference)
            }
        }
    }

    private fun startListeningInternal(languagePreference: String) {
        if (!PermissionValidationLayer.hasRecordAudioPermission(context)) {
            Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission missing")
            state = RecognizerState.STOPPED
            isContinuousMode = false
            onError?.invoke("Microphone permission required")
            return
        }

        // Prevent duplicate sessions
        if (state == RecognizerState.LISTENING || state == RecognizerState.INITIALIZING) {
            Log.d(TAG, "Already listening or initializing; skipping duplicate startListening")
            return
        }

        if (speechRecognizer == null) {
            initRecognizer()
        }

        if (speechRecognizer == null) {
            Log.w(TAG, "SpeechRecognizer is null after init in current runtime")
            state = RecognizerState.LISTENING
            return
        }

        state = RecognizerState.INITIALIZING

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languagePreference)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // Silence parameters to prevent premature timeouts and repeated "ton-ton" beeps
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_POSSIBLY_COMPLETE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SILENCE_MINIMUM_MS)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception starting speech recognition", t)
            state = RecognizerState.IDLE
            if (isContinuousMode && !isPaused) {
                scheduleSilentRecovery(400)
            } else {
                onError?.invoke("Could not start microphone: ${t.message}")
            }
        }
    }

    /**
     * Pauses listening between conversational turns without destroying the continuous session.
     */
    fun pauseListening() {
        runOnMainThread {
            isPaused = true
            mainHandler.removeCallbacks(recoveryRunnable)
            if (state == RecognizerState.LISTENING || state == RecognizerState.INITIALIZING) {
                state = RecognizerState.IDLE
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {}
                VoiceLogger.logListenStop()
            }
        }
    }

    /**
     * Stops continuous listening session and halts microphone input.
     * Only called when the user explicitly stops the session.
     */
    fun stopListening() {
        runOnMainThread {
            isContinuousMode = false
            isPaused = false
            mainHandler.removeCallbacks(recoveryRunnable)
            if (state == RecognizerState.STOPPED) return@runOnMainThread
            state = RecognizerState.STOPPED
            consecutiveNonFatalErrors = 0
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Exception cancelling speech recognition", e)
            }
            VoiceLogger.logListenStop()
            VoiceLogger.logAudioInputStop()
            onListeningFinished?.invoke()
        }
    }

    /**
     * Completely destroys recognizer and releases microphone hardware.
     */
    fun destroy() {
        runOnMainThread {
            isContinuousMode = false
            isPaused = false
            mainHandler.removeCallbacks(recoveryRunnable)
            state = RecognizerState.STOPPED
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            VoiceLogger.logAudioInputStop()
        }
    }
}
