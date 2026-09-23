package com.example.poxi.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

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
                    onSpeakingStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingFinished?.invoke()
                }

                override fun onError(utteranceId: String?) {
                    onSpeakingFinished?.invoke()
                }
            })
            // Default to Indian English / Hinglish accent
            setLanguageByLocale(Locale.forLanguageTag("en-IN"))
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
        }
    }

    /**
     * Set TTS language according to detected language.
     */
    fun setLanguageByLocale(locale: Locale) {
        try {
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to English
                tts?.language = Locale.ENGLISH
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set TTS language: $locale", e)
        }
    }

    /**
     * Automatically sets language based on language string or text content.
     */
    fun detectAndSetLanguage(text: String, hintLanguage: String? = null) {
        val lowerHint = (hintLanguage ?: "").lowercase()
        when {
            lowerHint.contains("hindi") || isDevanagari(text) -> {
                setLanguageByLocale(Locale.forLanguageTag("hi-IN"))
            }
            lowerHint.contains("marathi") -> {
                setLanguageByLocale(Locale.forLanguageTag("mr-IN"))
            }
            lowerHint.contains("bengali") || lowerHint.contains("bangla") -> {
                setLanguageByLocale(Locale.forLanguageTag("bn-IN"))
            }
            lowerHint.contains("tamil") -> {
                setLanguageByLocale(Locale.forLanguageTag("ta-IN"))
            }
            lowerHint.contains("telugu") -> {
                setLanguageByLocale(Locale.forLanguageTag("te-IN"))
            }
            lowerHint.contains("gujarati") -> {
                setLanguageByLocale(Locale.forLanguageTag("gu-IN"))
            }
            lowerHint.contains("punjabi") -> {
                setLanguageByLocale(Locale.forLanguageTag("pa-IN"))
            }
            lowerHint.contains("kannada") -> {
                setLanguageByLocale(Locale.forLanguageTag("kn-IN"))
            }
            lowerHint.contains("malayalam") -> {
                setLanguageByLocale(Locale.forLanguageTag("ml-IN"))
            }
            lowerHint.contains("hinglish") || isHinglish(text) -> {
                // For Hinglish, hi-IN or en-IN voices articulate Hinglish phrases like "WhatsApp khol raha hoon" perfectly
                setLanguageByLocale(Locale.forLanguageTag("hi-IN"))
            }
            else -> {
                setLanguageByLocale(Locale.forLanguageTag("en-IN"))
            }
        }
    }

    private fun isDevanagari(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    private fun isHinglish(text: String): Boolean {
        val lower = text.lowercase()
        val hinglishTokens = listOf(
            "kholo", "karo", "karta", "karti", "hai", "hain", "hoon", "aap", "tum", "mera", "meri",
            "kaise", "batao", "chal", "chalao", "lagao", "baat", "shukriya", "dhanyawad", "theek"
        )
        return hinglishTokens.any { lower.contains(it) }
    }

    /**
     * Plays spoken voice: if Gemini Live audio bytes are available, plays raw audio;
     * otherwise synthesizes speech via on-device multi-lingual voice engine.
     */
    fun speak(text: String, audioBytes: ByteArray? = null, languageHint: String? = null) {
        stop()

        if (audioBytes != null && audioBytes.isNotEmpty()) {
            // Play true Gemini Live generated voice bytes
            audioPlayer.playPcm(audioBytes, sampleRate = 24000)
        } else {
            // Synthesize voice
            if (isTtsReady && tts != null) {
                detectAndSetLanguage(text, languageHint)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poxi_voice_${System.currentTimeMillis()}")
            }
        }
    }

    /**
     * Immediately interrupts and silences voice.
     */
    fun stop() {
        audioPlayer.stop()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
        onSpeakingFinished?.invoke()
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}
