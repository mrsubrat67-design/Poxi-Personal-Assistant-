package com.example.poxi.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechInputManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInputManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    var onSpeechResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isListening: Boolean = false
        private set

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningStarted?.invoke()
            }

            override fun onBeginningOfSpeech() {
                isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize roughly from -2 to 10 dB to 0f..1f
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsChanged?.invoke(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                onListeningFinished?.invoke()
            }

            override fun onError(error: Int) {
                isListening = false
                onListeningFinished?.invoke()
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy"
                    SpeechRecognizer.ERROR_SERVER -> "Voice server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Speech recognition error ($error)"
                }
                Log.w(TAG, "SpeechRecognizer error: $message")
                onError?.invoke(message)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningFinished?.invoke()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()
                if (!recognized.isNullOrBlank()) {
                    onSpeechResult?.invoke(recognized)
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
        stopListening()

        if (speechRecognizer == null) {
            initRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Support Indian multi-lingual speech (Hindi, English, Hinglish)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languagePreference)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US", "mr-IN", "bn-IN"))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            onError?.invoke("Could not start microphone: ${e.message}")
        }
    }

    fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
        onListeningFinished?.invoke()
    }

    fun destroy() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }
}
