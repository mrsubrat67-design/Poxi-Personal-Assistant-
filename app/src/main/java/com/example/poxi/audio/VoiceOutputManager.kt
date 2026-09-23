package com.example.poxi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlin.math.sin

/**
 * Manages voice output for Poxi via Android TextToSpeech (TTS) engine.
 * Provides spoken responses back to the user with multi-language support (Hindi, Hinglish, English),
 * utterance progress tracking, audio focus ducking, dynamic waveform amplitude feedback, and instant interruption.
 */
class VoiceOutputManager(
    private val context: Context,
    val audioPlayer: AudioPlayer = AudioPlayer(context)
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceOutputManager"
        private const val DEFAULT_SPEECH_RATE = 1.0f
        private const val DEFAULT_SPEECH_PITCH = 1.02f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null

    @Volatile
    private var _isTtsReady = false
    val isTtsReady: Boolean
        get() = _isTtsReady

    @Volatile
    private var _isTtsSpeaking = false

    val isSpeaking: Boolean
        get() = _isTtsSpeaking || audioPlayer.isPlaying

    private var pendingSpeech: PendingUtterance? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    var speechRate: Float = DEFAULT_SPEECH_RATE
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            try {
                tts?.setSpeechRate(field)
            } catch (_: Exception) {}
        }

    var speechPitch: Float = DEFAULT_SPEECH_PITCH
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            try {
                tts?.setPitch(field)
            } catch (_: Exception) {}
        }

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null
    var onTtsReadyChanged: ((Boolean) -> Unit)? = null

    private data class PendingUtterance(
        val text: String,
        val languageHint: String?
    )

    // Waveform simulation runnable for TTS speech
    private var amplitudeStep = 0
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (_isTtsSpeaking) {
                amplitudeStep++
                // Generate a natural oscillating amplitude between 0.3 and 0.85
                val wave = (sin(amplitudeStep * 0.4) * 0.25 + sin(amplitudeStep * 0.7) * 0.15 + 0.5).toFloat()
                val clamped = wave.coerceIn(0.15f, 0.95f)
                onAmplitudeUpdated?.invoke(clamped)
                mainHandler.postDelayed(this, 50)
            } else {
                onAmplitudeUpdated?.invoke(0f)
            }
        }
    }

    init {
        audioPlayer.onPlaybackStarted = {
            mainHandler.post { onSpeakingStarted?.invoke() }
        }
        audioPlayer.onPlaybackFinished = {
            mainHandler.post { onSpeakingFinished?.invoke() }
        }
        audioPlayer.onAmplitudeUpdated = { amp ->
            mainHandler.post { onAmplitudeUpdated?.invoke(amp) }
        }
        initializeTts()
    }

    private fun initializeTts() {
        try {
            Log.d(TAG, "Initializing Android TextToSpeech engine...")
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate Android TextToSpeech", e)
            _isTtsReady = false
            onTtsReadyChanged?.invoke(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            _isTtsReady = true
            Log.i(TAG, "Android TextToSpeech engine initialized successfully")

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS onStart: utteranceId=$utteranceId")
                    _isTtsSpeaking = true
                    VoiceLogger.logAudioOutputStart()
                    mainHandler.post {
                        startAmplitudeSimulation()
                        onSpeakingStarted?.invoke()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS onDone: utteranceId=$utteranceId")
                    _isTtsSpeaking = false
                    VoiceLogger.logAudioOutputStop()
                    abandonAudioFocus()
                    mainHandler.post {
                        stopAmplitudeSimulation()
                        onSpeakingFinished?.invoke()
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS onError: utteranceId=$utteranceId")
                    _isTtsSpeaking = false
                    VoiceLogger.logAudioOutputStop()
                    abandonAudioFocus()
                    mainHandler.post {
                        stopAmplitudeSimulation()
                        onSpeakingFinished?.invoke()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS onError with code: utteranceId=$utteranceId, errorCode=$errorCode")
                    _isTtsSpeaking = false
                    VoiceLogger.logAudioOutputStop()
                    abandonAudioFocus()
                    mainHandler.post {
                        stopAmplitudeSimulation()
                        onSpeakingFinished?.invoke()
                    }
                }
            })

            // Apply default voice parameters
            try {
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(speechPitch)
            } catch (e: Exception) {
                Log.w(TAG, "Error configuring initial voice rate/pitch", e)
            }

            mainHandler.post {
                onTtsReadyChanged?.invoke(true)
            }

            // Speak any utterance that arrived before TTS finished initializing
            pendingSpeech?.let { pending ->
                pendingSpeech = null
                mainHandler.post {
                    speak(pending.text, audioBytes = null, languageHint = pending.languageHint)
                }
            }
        } else {
            _isTtsReady = false
            Log.e(TAG, "Android TextToSpeech engine initialization failed with status: $status")
            mainHandler.post {
                onTtsReadyChanged?.invoke(false)
                pendingSpeech?.let {
                    pendingSpeech = null
                    onSpeakingFinished?.invoke()
                }
            }
        }
    }

    /**
     * Speaks out Poxi's response.
     * Uses the Android TextToSpeech engine to vocalize text responses naturally.
     * Supports Hindi, Hinglish, and English with auto-language selection.
     */
    fun speak(
        text: String,
        audioBytes: ByteArray? = null,
        languageHint: String? = null,
        preferTts: Boolean = true
    ) {
        stop(notifyFinished = false)

        val cleanText = sanitizeTextForSpeech(text)
        if (cleanText.isBlank() && (audioBytes == null || audioBytes.isEmpty())) {
            Log.d(TAG, "Nothing to speak (text and audio are blank)")
            mainHandler.post { onSpeakingFinished?.invoke() }
            return
        }

        // If raw PCM audioBytes is explicitly provided, play via AudioPlayer
        if (audioBytes != null && audioBytes.isNotEmpty()) {
            audioPlayer.playPcm(audioBytes, sampleRate = 24000)
        } else if (cleanText.isNotBlank()) {
            if (!_isTtsReady || tts == null) {
                Log.w(TAG, "TTS not ready yet; queueing pending speech")
                pendingSpeech = PendingUtterance(cleanText, languageHint)
                return
            }

            speakViaAndroidTts(cleanText, languageHint)
        } else {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onSpeakingFinished?.invoke()
            } else {
                mainHandler.post { onSpeakingFinished?.invoke() }
            }
        }
    }

    private fun speakViaAndroidTts(text: String, languageHint: String?) {
        // Request audio focus so background music or videos duck smoothly
        requestAudioFocus()

        // Configure target locale based on text content and language hint
        configureLocaleForText(text, languageHint)

        val utteranceId = "poxi_tts_${UUID.randomUUID()}"
        Log.d(TAG, "Speaking via Android TextToSpeech: \"${text.take(40)}...\" [utteranceId=$utteranceId]")

        try {
            val result = tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TextToSpeech.speak failed with code: $result")
                abandonAudioFocus()
                _isTtsSpeaking = false
                stopAmplitudeSimulation()
                mainHandler.post { onSpeakingFinished?.invoke() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling tts.speak", e)
            abandonAudioFocus()
            _isTtsSpeaking = false
            stopAmplitudeSimulation()
            mainHandler.post { onSpeakingFinished?.invoke() }
        }
    }

    private fun configureLocaleForText(text: String, languageHint: String?) {
        try {
            val isDevanagari = text.any { it in '\u0900'..'\u097F' }
            val isHindiHint = languageHint?.equals("Hindi", ignoreCase = true) == true

            val targetLocale = when {
                isDevanagari || isHindiHint -> Locale.forLanguageTag("hi-IN")
                languageHint?.equals("Hinglish", ignoreCase = true) == true -> Locale.forLanguageTag("en-IN")
                else -> Locale.forLanguageTag("en-IN")
            }

            val availability = tts?.isLanguageAvailable(targetLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = targetLocale
            } else {
                val englishAvail = tts?.isLanguageAvailable(Locale.ENGLISH) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (englishAvail >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = Locale.ENGLISH
                } else {
                    tts?.language = Locale.getDefault()
                }
            }

            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        } catch (e: Exception) {
            Log.w(TAG, "Error selecting TTS locale", e)
        }
    }

    private fun sanitizeTextForSpeech(input: String): String {
        return input
            // Remove markdown formatting: bold, italics, code
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("\\*(.*?)\\*"), "$1")
            .replace(Regex("`{1,3}(.*?)`{1,3}"), "$1")
            .replace(Regex("#+\\s*"), "")
            // Remove emojis or special symbols that might sound strange
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            // Remove multiple whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun startAmplitudeSimulation() {
        amplitudeStep = 0
        mainHandler.removeCallbacks(amplitudeRunnable)
        mainHandler.post(amplitudeRunnable)
    }

    private fun stopAmplitudeSimulation() {
        mainHandler.removeCallbacks(amplitudeRunnable)
        onAmplitudeUpdated?.invoke(0f)
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            stop(notifyFinished = false)
                        }
                    }
                    .build()

                audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        }
    }

    /**
     * Immediately silences and halts speech (interruption support).
     */
    fun stop(notifyFinished: Boolean = false) {
        pendingSpeech = null
        val wasTtsSpeaking = _isTtsSpeaking || (tts?.isSpeaking == true)
        val wasAudioPlaying = audioPlayer.isPlaying

        _isTtsSpeaking = false
        stopAmplitudeSimulation()
        abandonAudioFocus()

        audioPlayer.stop(notifyFinished = false)
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }

        if (wasTtsSpeaking || wasAudioPlaying || notifyFinished) {
            VoiceLogger.logAudioOutputStop()
            if (notifyFinished) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    onSpeakingFinished?.invoke()
                } else {
                    mainHandler.post { onSpeakingFinished?.invoke() }
                }
            }
        }
    }

    /**
     * Releases TTS resources.
     */
    fun shutdown() {
        stop(notifyFinished = false)
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        _isTtsReady = false
    }
}
