package com.example.poxi.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Manages voice output for Poxi.
 * Prioritizes the native Gemini Live PCM audio pipeline (24kHz Mono).
 * Uses a single unified, consistent voice profile for fallback TTS without competing engines.
 */
class VoiceOutputManager(
    private val context: Context,
    val audioPlayer: AudioPlayer = AudioPlayer(context)
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceOutputManager"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null

    init {
        audioPlayer.onPlaybackStarted = {
            onSpeakingStarted?.invoke()
        }
        audioPlayer.onPlaybackFinished = {
            onSpeakingFinished?.invoke()
        }
        initializeTts()
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    VoiceLogger.logAudioOutputStart()
                    onSpeakingStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    VoiceLogger.logAudioOutputStop()
                    onSpeakingFinished?.invoke()
                }

                override fun onError(utteranceId: String?) {
                    VoiceLogger.logAudioOutputStop()
                    onSpeakingFinished?.invoke()
                }
            })

            // Unified, consistent voice configuration:
            // Indian English (en-IN) handles English, Hinglish, and Hindi names seamlessly
            // without competing TTS engines or jarring voice/pitch transitions
            configureUnifiedVoice()
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
        }
    }

    private fun configureUnifiedVoice() {
        try {
            val defaultLocale = Locale.forLanguageTag("en-IN")
            val result = tts?.setLanguage(defaultLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.ENGLISH
            }
            // Calibrate natural cadence and warmth
            tts?.setSpeechRate(0.98f)
            tts?.setPitch(1.02f)
        } catch (e: Exception) {
            Log.w(TAG, "Voice configuration fallback", e)
        }
    }

    /**
     * Speaks out Poxi's response.
     * Uses native Gemini Live audio PCM bytes if available;
     * falls back to the unified local voice engine only when audio bytes are absent.
     */
    fun speak(text: String, audioBytes: ByteArray? = null, languageHint: String? = null) {
        stop()

        if (audioBytes != null && audioBytes.isNotEmpty()) {
            // Native Gemini Live voice pipeline
            audioPlayer.playPcm(audioBytes, sampleRate = 24000)
        } else {
            // Unified local voice fallback
            if (isTtsReady && tts != null && text.isNotBlank()) {
                // If text contains pure Devanagari script, switch gently to hi-IN, else keep en-IN
                val isDevanagari = text.any { it in '\u0900'..'\u097F' }
                if (isDevanagari) {
                    try {
                        tts?.language = Locale.forLanguageTag("hi-IN")
                    } catch (_: Exception) {}
                } else {
                    try {
                        tts?.language = Locale.forLanguageTag("en-IN")
                    } catch (_: Exception) {}
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poxi_voice_${System.currentTimeMillis()}")
            } else {
                // If TTS isn't ready or text is blank, ensure finished callback is called
                onSpeakingFinished?.invoke()
            }
        }
    }

    /**
     * Immediately silences and halts speech (interruption support).
     */
    fun stop() {
        val wasPlaying = audioPlayer.isPlaying || (tts?.isSpeaking == true)
        audioPlayer.stop()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }

        if (wasPlaying) {
            VoiceLogger.logAudioOutputStop()
            onSpeakingFinished?.invoke()
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}
