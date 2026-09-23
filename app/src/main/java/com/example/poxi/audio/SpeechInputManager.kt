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

        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "SpeechRecognizer isRecognitionAvailable: $isAvailable")

        if (isAvailable) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create SpeechRecognizer: ${t.javaClass.simpleName}: ${t.message}", t)
                speechRecognizer = null
            }
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
                Log.w(TAG, "SpeechRecognizer onError: code=$error, currentState=$state")

                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // User paused or brief silence: handle internally without toggling microphone off
                        state = RecognizerState.IDLE
                        consecutiveNonFatalErrors = 0
                        VoiceLogger.logSpeechTimeout()
                        onSpeechTimeout?.invoke()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        state = RecognizerState.IDLE
                        try {
                            speechRecognizer?.cancel()
                        } catch (_: Exception) {}
                        consecutiveNonFatalErrors++
                        if (consecutiveNonFatalErrors <= 3) {
                            onSpeechTimeout?.invoke()
                        } else {
                            consecutiveNonFatalErrors = 0
                            state = RecognizerState.STOPPED
                            onError?.invoke("Speech recognition paused. Tap mic to speak.")
                            onListeningFinished?.invoke()
                        }
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        state = RecognizerState.STOPPED
                        consecutiveNonFatalErrors = 0
                        VoiceLogger.logAudioInputStop()
                        onError?.invoke("Microphone permission required")
                        onListeningFinished?.invoke()
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        state = RecognizerState.IDLE
                        consecutiveNonFatalErrors = 0
                        VoiceLogger.logAudioInputStop()
                        onError?.invoke("Microphone audio capture error")
                        onListeningFinished?.invoke()
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        state = RecognizerState.IDLE
                        consecutiveNonFatalErrors = 0
                        onError?.invoke("Network connection issue")
                        onListeningFinished?.invoke()
                    }
                    else -> {
                        state = RecognizerState.IDLE
                        try {
                            speechRecognizer?.cancel()
                        } catch (_: Exception) {}
                        consecutiveNonFatalErrors++
                        if (consecutiveNonFatalErrors <= 3) {
                            onSpeechTimeout?.invoke()
                        } else {
                            consecutiveNonFatalErrors = 0
                            state = RecognizerState.STOPPED
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
     * Starts continuous listening.
     * Prevents duplicate sessions or re-arming while already active.
     */
    fun startListening(languagePreference: String = "hi-IN") {
        runOnMainThread {
            if (!PermissionValidationLayer.hasRecordAudioPermission(context)) {
                Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission missing")
                state = RecognizerState.STOPPED
                onError?.invoke("Microphone permission required")
                return@runOnMainThread
            }

            // Prevent duplicate sessions
            if (state == RecognizerState.LISTENING || state == RecognizerState.INITIALIZING) {
                Log.d(TAG, "Already listening or initializing; skipping duplicate startListening")
                return@runOnMainThread
            }

            if (speechRecognizer == null) {
                initRecognizer()
            }

            if (speechRecognizer == null) {
                Log.e(TAG, "SpeechRecognizer is null after init")
                state = RecognizerState.STOPPED
                onError?.invoke("Speech recognition is not available on this device")
                return@runOnMainThread
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
                onError?.invoke("Could not start microphone: ${t.message}")
            }
        }
    }

    /**
     * Pauses listening between conversational turns without destroying the underlying engine.
     */
    fun pauseListening() {
        runOnMainThread {
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
     * Stops listening and halts microphone input.
     */
    fun stopListening() {
        runOnMainThread {
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
