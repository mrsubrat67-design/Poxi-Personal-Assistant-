package com.example.poxi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A reactive Jetpack Compose visual waveform animation component.
 *
 * Dynamically reacts to:
 * - Real-time audio input/output [amplitude] (0.0f - 1.0f)
 * - State transitions:
 *   - [isListening]: Active cyan-green frequency equalizer tracking user microphone input.
 *   - [isProcessing]: Iridescent pink-violet travelling phase wave indicating AI thinking.
 *   - [isSpeaking]: Harmonious purple-rose rhythmic speech bars during voice output.
 *   - Idle / Dormant: Gentle breathing ambient pulse with subtle luminescence.
 */
@Composable
fun AudioWaveformVisualizer(
    amplitude: Float,
    isListening: Boolean,
    isSpeaking: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    showContinuousCurve: Boolean = true
) {
    // Smooth the raw audio amplitude input to prevent jittering
    val smoothedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "smoothed_amplitude"
    )

    // Infinite transitions for ambient motion & processing travelling wave
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_motion")

    // Travelling wave phase for processing state
    val travellingPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isProcessing) 1000 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "travelling_phase"
    )

    // Breathing pulse for idle & speaking rhythm
    val ambientBreath by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 450 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_breath"
    )

    // Dynamic gradient colors reflecting assistant state
    val startColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0xFF06B6D4) // Cyan
            isSpeaking -> Color(0xFFA855F7)  // Bright Purple
            isProcessing -> Color(0xFFEC4899) // Hot Pink
            else -> Color(0xFF475569)        // Slate Neutral
        },
        animationSpec = tween(400),
        label = "waveform_start_color"
    )

    val endColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0xFF10B981) // Emerald Green
            isSpeaking -> Color(0xFFF43F5E)  // Rose
            isProcessing -> Color(0xFF8B5CF6) // Deep Violet
            else -> Color(0xFF64748B)        // Subtle Slate
        },
        animationSpec = tween(400),
        label = "waveform_end_color"
    )

    val glowColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0x3306B6D4)
            isSpeaking -> Color(0x33A855F7)
            isProcessing -> Color(0x33EC4899)
            else -> Color(0x1164748B)
        },
        animationSpec = tween(400),
        label = "waveform_glow_color"
    )

    Box(
        modifier = modifier.testTag("audio_waveform_visualizer"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            if (width <= 0f || height <= 0f) return@Canvas

            // 1. Draw background ambient glow aura
            if (isListening || isSpeaking || isProcessing) {
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = Offset(width / 2f, centerY),
                        radius = (width / 2f).coerceAtLeast(10f)
                    ),
                    size = size,
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }

            // 2. Continuous smooth waveform spline path (flowing wave behind bars)
            if (showContinuousCurve) {
                val wavePath = Path()
                val wavePoints = 48
                val stepX = width / (wavePoints - 1)

                for (i in 0 until wavePoints) {
                    val x = i * stepX
                    val normX = (i.toFloat() / (wavePoints - 1)) * 2f - 1f // -1 to 1
                    val envelope = (1f - (normX * normX)).coerceIn(0.1f, 1f) // parabolic bell curve

                    val waveAmp = when {
                        isProcessing -> height * 0.35f * envelope * (0.5f + 0.5f * sin(normX * 4f + travellingPhase))
                        isListening -> height * 0.45f * envelope * (smoothedAmplitude * 1.2f + 0.1f * sin(normX * 6f + travellingPhase))
                        isSpeaking -> height * 0.40f * envelope * (smoothedAmplitude * 1.0f + 0.15f * sin(normX * 5f + travellingPhase))
                        else -> height * 0.12f * envelope * ambientBreath
                    }

                    val y = centerY + (sin(normX * PI * 2f + travellingPhase).toFloat() * waveAmp)

                    if (i == 0) {
                        wavePath.moveTo(x, y)
                    } else {
                        val prevX = (i - 1) * stepX
                        val midX = (prevX + x) / 2f
                        wavePath.quadraticTo(prevX, centerY, midX, (centerY + y) / 2f)
                        wavePath.lineTo(x, y)
                    }
                }

                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            startColor.copy(alpha = 0.3f),
                            endColor.copy(alpha = 0.6f),
                            startColor.copy(alpha = 0.3f)
                        )
                    ),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            // 3. Symmetric Frequency Equalizer Bars
            val barSpacing = width / (barCount * 1.5f + 1f)
            val barWidth = (barSpacing * 0.85f).coerceIn(2.5.dp.toPx(), 8.dp.toPx())
            val totalBarsWidth = (barCount * barWidth) + ((barCount - 1) * (barSpacing - barWidth))
            val startX = (width - totalBarsWidth) / 2f

            for (i in 0 until barCount) {
                val normalizedIndex = (i.toFloat() / (barCount - 1)) * 2f - 1f // -1.0 to 1.0
                // Center-weighted parabolic bell curve
                val bellCurve = (1f - (normalizedIndex * normalizedIndex * 0.75f)).coerceIn(0.25f, 1f)

                // Distinct state-driven bar dynamics
                val barFraction: Float = when {
                    isProcessing -> {
                        // Phase-shifted travelling sinusoidal wave
                        val wave = sin((normalizedIndex * 3.5f + travellingPhase).toDouble()).toFloat()
                        val pulse = (wave + 1f) / 2f // 0f to 1f
                        (0.20f + (pulse * 0.65f * bellCurve)).coerceIn(0.12f, 0.95f)
                    }
                    isListening -> {
                        // High-response audio input bars with frequency jitter
                        val phaseOffset = i * 0.45f
                        val modulation = abs(sin((travellingPhase * 2f + phaseOffset).toDouble())).toFloat()
                        val dynamicPeak = (smoothedAmplitude * 1.4f * bellCurve) + (modulation * smoothedAmplitude * 0.5f)
                        (0.12f + dynamicPeak + (ambientBreath * 0.1f)).coerceIn(0.10f, 0.98f)
                    }
                    isSpeaking -> {
                        // Harmonious speech cadence with rhythmic fluctuation
                        val harmonic = abs(cos((travellingPhase * 1.5f + i * 0.3f).toDouble())).toFloat()
                        val voiceEnergy = (smoothedAmplitude * 1.1f * bellCurve) + (harmonic * 0.25f)
                        (0.15f + voiceEnergy).coerceIn(0.12f, 0.92f)
                    }
                    else -> {
                        // Idle state: subtle organic ripple
                        val idleWave = sin((ambientBreath * 3f + i * 0.25f).toDouble()).toFloat()
                        (0.10f + (abs(idleWave) * 0.12f * bellCurve)).coerceIn(0.08f, 0.25f)
                    }
                }

                val currentBarHeight = (height * barFraction).coerceAtLeast(4.dp.toPx())
                val bx = startX + i * barSpacing
                val by = centerY - (currentBarHeight / 2f)

                // Individual bar color gradient
                val barProgress = i.toFloat() / (barCount - 1)
                val barBrush = Brush.verticalGradient(
                    colors = listOf(
                        startColor.copy(alpha = if (isListening || isSpeaking || isProcessing) 0.95f else 0.5f),
                        endColor.copy(alpha = if (isListening || isSpeaking || isProcessing) 1.0f else 0.7f),
                        startColor.copy(alpha = if (isListening || isSpeaking || isProcessing) 0.85f else 0.4f)
                    ),
                    startY = by,
                    endY = by + currentBarHeight
                )

                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(bx, by),
                    size = Size(barWidth, currentBarHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
