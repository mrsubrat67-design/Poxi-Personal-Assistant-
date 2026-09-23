package com.example.poxi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        const val DEFAULT_SAMPLE_RATE = 24000 // Standard Gemini 24kHz PCM
    }

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * Plays raw PCM audio data (e.g. from Gemini Live audio output: 24kHz, 16-bit, mono).
     */
    fun playPcm(pcmBytes: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        stop()

        playbackJob = scope.launch {
            try {
                isPlaying = true
                withContext(Dispatchers.Main) { onPlaybackStarted?.invoke() }

                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val bufferSize = maxOf(minBufferSize, pcmBytes.size)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
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

                audioTrack = track
                track.play()

                val chunkSize = 2048
                var offset = 0
                val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val totalShorts = pcmBytes.size / 2
                var shortsRead = 0

                while (offset < pcmBytes.size && isActive && isPlaying) {
                    val bytesToWrite = minOf(chunkSize, pcmBytes.size - offset)
                    val written = track.write(pcmBytes, offset, bytesToWrite)
                    if (written <= 0) break

                    // Calculate amplitude for audio visualizer
                    var sum = 0L
                    val shortsToCalc = minOf(bytesToWrite / 2, totalShorts - shortsRead)
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

                // Wait for buffer to finish draining
                kotlinx.coroutines.delay(250)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing PCM audio", e)
            } finally {
                withContext(Dispatchers.Main) {
                    stop()
                    onPlaybackFinished?.invoke()
                }
            }
        }
    }

    /**
     * Plays compressed audio format like MP3 or WAV from bytes.
     */
    fun playEncodedAudio(audioBytes: ByteArray, extension: String = "mp3") {
        stop()

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                val tempFile = File.createTempFile("poxi_audio_", ".$extension", context.cacheDir)
                FileOutputStream(tempFile).use { it.write(audioBytes) }

                withContext(Dispatchers.Main) {
                    isPlaying = true
                    onPlaybackStarted?.invoke()

                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setOnCompletionListener {
                            stop()
                            onPlaybackFinished?.invoke()
                            tempFile.delete()
                        }
                        setOnErrorListener { _, _, _ ->
                            stop()
                            onPlaybackFinished?.invoke()
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
                    onPlaybackFinished?.invoke()
                }
            }
        }
    }

    /**
     * Instantly stops any ongoing audio playback (Interruption support).
     */
    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }

        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }

        onAmplitudeUpdated?.invoke(0f)
    }
}
