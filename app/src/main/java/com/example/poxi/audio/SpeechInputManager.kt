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

class SpeechInputManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInputManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    var onSpeechResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeechTimeout: (() -> Unit)? = null

    var isListening: Boolean = false
        private set

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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
            }
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                isListening = true
                onListeningStarted?.invoke()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsChanged?.invoke(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                isListening = false
                onListeningFinished?.invoke()
            }

            override fun onError(error: Int) {
                isListening = false
                onListeningFinished?.invoke()

                Log.w(TAG, "SpeechRecognizer onError: code=$error")

                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // User paused or no speech recognized during continuous session
                        Log.d(TAG, "Speech timeout/no match -> trigger retry for continuous session")
                        onSpeechTimeout?.invoke()
                    }
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Client or busy error: cleanly re-create the recognizer on main thread
                        Log.w(TAG, "Recognizer client/busy state ($error) -> reinitializing recognizer")
                        runOnMainThread {
                            initRecognizer()
                        }
                        // Notify timeout callback to gracefully retry without displaying an error
                        onSpeechTimeout?.invoke()
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        onError?.invoke("Microphone permission required")
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        onError?.invoke("Microphone audio capture error")
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        onError?.invoke("Network connection issue")
                    }
                    SpeechRecognizer.ERROR_SERVER -> {
                        onError?.invoke("Voice recognition service unavailable")
                    }
                    else -> {
                        // Generic error
                        Log.w(TAG, "Unhandled speech recognizer error ($error)")
                        onSpeechTimeout?.invoke()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningFinished?.invoke()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()
                Log.d(TAG, "SpeechRecognizer onResults: recognized=$recognized")
                if (!recognized.isNullOrBlank()) {
                    onSpeechResult?.invoke(recognized)
                } else {
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

    fun startListening(languagePreference: String = "hi-IN") {
        runOnMainThread {
            if (isListening) {
                Log.d(TAG, "startListening called but already listening")
                return@runOnMainThread
            }

            if (speechRecognizer == null) {
                initRecognizer()
            }

            if (speechRecognizer == null) {
                Log.e(TAG, "SpeechRecognizer is null after init")
                onError?.invoke("Speech recognition is not available on this device")
                return@runOnMainThread
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languagePreference)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US", "mr-IN", "bn-IN"))
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            try {
                Log.d(TAG, "Invoking speechRecognizer.startListening")
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting speech recognition", e)
                onError?.invoke("Could not start microphone: ${e.message}")
            }
        }
    }

    fun stopListening() {
        runOnMainThread {
            if (!isListening) return@runOnMainThread
            isListening = false
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping speech recognition", e)
            }
            onListeningFinished?.invoke()
        }
    }

    fun destroy() {
        runOnMainThread {
            isListening = false
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }
}
