package com.example.poxi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000 // Native Gemini Live 24kHz PCM
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: Any? = null

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception in AudioPlayer: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.Default + exceptionHandler)

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes.USAGE_ASSISTANT
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
                val attributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* transient focus change */ }
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
            true
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequest as? AudioFocusRequest)?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    /**
     * Plays raw PCM audio from Gemini Live: 24kHz, 16-bit Mono, Little-Endian.
     * Prevents choppy audio or premature cutoffs by monitoring playbackHeadPosition.
     */
    fun playPcm(pcmBytes: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        stop()

        if (pcmBytes.isEmpty()) {
            return
        }

        playbackJob = scope.launch {
            var track: AudioTrack? = null
            try {
                requestAudioFocus()
                isPlaying = true
                VoiceLogger.logAudioOutputStart()

                withContext(Dispatchers.Main) { onPlaybackStarted?.invoke() }

                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                // Safe streaming buffer size that complies with Android Audio HAL limits
                val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 2, 4096) else 8192

                try {
                    val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        AudioAttributes.USAGE_ASSISTANT
                    } else {
                        AudioAttributes.USAGE_MEDIA
                    }
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to instantiate AudioTrack: ${t.javaClass.simpleName}: ${t.message}", t)
                    return@launch
                }

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack uninitialized (state=${track.state})")
                    track.release()
                    track = null
                    return@launch
                }

                audioTrack = track
                try {
                    track.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start AudioTrack playback: ${e.javaClass.simpleName}: ${e.message}", e)
                    return@launch
                }

                val totalFrames = pcmBytes.size / 2 // 16-bit mono = 2 bytes per frame
                val chunkSize = 2048
                var offset = 0
                val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var shortsRead = 0

                while (offset < pcmBytes.size && isActive && isPlaying) {
                    val bytesToWrite = minOf(chunkSize, pcmBytes.size - offset)
                    val written = track.write(pcmBytes, offset, bytesToWrite)
                    if (written <= 0) break

                    // Update amplitude for smooth visualizer feedback
                    var sum = 0L
                    val shortsToCalc = minOf(bytesToWrite / 2, totalFrames - shortsRead)
                    for (i in 0 until shortsToCalc) {
                        if (shortBuffer.hasRemaining()) {
                            sum += abs(shortBuffer.get().toInt())
                        }
                    }
                    shortsRead += shortsToCalc
                    val avgAmplitude = if (shortsToCalc > 0) (sum / shortsToCalc) / 32768f else 0f
                    onAmplitudeUpdated?.invoke(avgAmplitude.coerceIn(0f, 1f))

                    offset += written
                }

                // Wait gracefully for all frames to be rendered by hardware AudioTrack
                // Avoids clipping or robotic cutoff at sentence endings
                val timeoutMs = (totalFrames * 1000L / sampleRate) + 500L
                val startTime = System.currentTimeMillis()
                while (isActive && isPlaying && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val headPosition = track.playbackHeadPosition
                    if (headPosition >= totalFrames || (System.currentTimeMillis() - startTime) > timeoutMs) {
                        break
                    }
                    delay(30)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error playing PCM audio: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.Main) {
                        cleanupTrack(track)
                        val shouldNotifyFinished = isPlaying
                        isPlaying = false
                        abandonAudioFocus()
                        VoiceLogger.logAudioOutputStop()
                        onAmplitudeUpdated?.invoke(0f)
                        if (shouldNotifyFinished) {
                            onPlaybackFinished?.invoke()
                        }
                    }
                }
            }
        }
    }

    /**
     * Plays compressed audio like MP3/WAV if returned by fallback sources.
     */
    fun playEncodedAudio(audioBytes: ByteArray, extension: String = "mp3") {
        stop()

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                requestAudioFocus()
                val tempFile = File.createTempFile("poxi_audio_", ".$extension", context.cacheDir)
                FileOutputStream(tempFile).use { it.write(audioBytes) }

                withContext(Dispatchers.Main) {
                    isPlaying = true
                    VoiceLogger.logAudioOutputStart()
                    onPlaybackStarted?.invoke()

                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            AudioAttributes.USAGE_ASSISTANT
                        } else {
                            AudioAttributes.USAGE_MEDIA
                        }
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setOnCompletionListener {
                            stop()
                            tempFile.delete()
                        }
                        setOnErrorListener { _, _, _ ->
                            stop()
                            tempFile.delete()
                            true
                        }
                        prepare()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing encoded audio", e)
                withContext(Dispatchers.Main) {
                    stop()
                }
            }
        }
    }

    private fun cleanupTrack(track: AudioTrack?) {
        try {
            track?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) {}
        if (audioTrack == track) {
            audioTrack = null
        }
    }

    /**
     * Instantly stops any ongoing audio playback (Interruption support).
     */
    fun stop() {
        val wasPlaying = isPlaying
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        cleanupTrack(audioTrack)

        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null

        abandonAudioFocus()
        onAmplitudeUpdated?.invoke(0f)

        if (wasPlaying) {
            VoiceLogger.logAudioOutputStop()
            onPlaybackFinished?.invoke()
        }
    }
}
