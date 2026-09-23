package com.example.poxi.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PoxiVisualizer(
    isListening: Boolean,
    isSpeaking: Boolean,
    isProcessing: Boolean,
    amplitude: Float,
    size: Dp = 180.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 600 else if (isListening) 900 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isProcessing) 3000 else 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_angle"
    )

    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_offset"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * 0.45f
            val dynamicBoost = (amplitude * 24.dp.toPx()).coerceAtMost(30.dp.toPx())

            // Color palette depending on state
            val primaryColor = when {
                isListening -> Color(0xFF06B6D4) // Electric Cyan
                isSpeaking -> Color(0xFF8B5CF6)  // Radiant Violet
                isProcessing -> Color(0xFFEC4899) // Hot Pink
                else -> Color(0xFF6366F1)        // Indigo
            }

            val secondaryColor = when {
                isListening -> Color(0xFF10B981) // Neon Green
                isSpeaking -> Color(0xFFF43F5E)  // Coral Rose
                isProcessing -> Color(0xFF8B5CF6) // Violet
                else -> Color(0xFF38BDF8)        // Sky Blue
            }

            // Outer Aura Rings
            if (isListening || isSpeaking || isProcessing) {
                for (i in 1..3) {
                    val ringRadius = baseRadius + (i * 18.dp.toPx() * (pulseScale - 0.2f)) + dynamicBoost * (i * 0.4f)
                    val alpha = (0.28f / i) * (1f - (waveOffset * 0.2f))
                    drawCircle(
                        color = primaryColor.copy(alpha = alpha.coerceIn(0f, 1f)),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = (2.5f - i * 0.5f).dp.toPx())
                    )
                }
            }

            // Holographic Rotating Orbit Particles
            val particleCount = if (isSpeaking || isListening) 8 else 5
            for (p in 0 until particleCount) {
                val angleRad = Math.toRadians((rotationAngle + (p * (360f / particleCount))).toDouble())
                val orbitRadius = baseRadius + 14.dp.toPx() + (dynamicBoost * 0.6f)
                val px = center.x + (orbitRadius * cos(angleRad)).toFloat()
                val py = center.y + (orbitRadius * sin(angleRad)).toFloat()
                val particleColor = if (p % 2 == 0) primaryColor else secondaryColor
                drawCircle(
                    color = particleColor.copy(alpha = 0.75f),
                    radius = 3.5.dp.toPx(),
                    center = Offset(px, py)
                )
            }

            // Glowing Radial Core
            val orbRadius = (baseRadius * pulseScale) + dynamicBoost
            val orbBrush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    secondaryColor.copy(alpha = 0.85f),
                    primaryColor.copy(alpha = 0.9f),
                    primaryColor.copy(alpha = 0.15f)
                ),
                center = center,
                radius = orbRadius
            )

            drawCircle(
                brush = orbBrush,
                radius = orbRadius,
                center = center
            )

            // Dynamic Voice Equalizer Waveforms in center when listening or speaking
            val waveBars = 7
            val barWidth = 4.dp.toPx()
            val totalWaveWidth = (waveBars * barWidth) + ((waveBars - 1) * 3.dp.toPx())
            val startX = center.x - (totalWaveWidth / 2f)

            for (b in 0 until waveBars) {
                val factor = when (b) {
                    0, 6 -> 0.35f
                    1, 5 -> 0.65f
                    2, 4 -> 0.9f
                    else -> 1.0f
                }
                val waveHeight = if (isSpeaking || isListening) {
                    val sineVal = sin((waveOffset * 6.28f + b.toFloat()).toDouble()).toFloat()
                    (10.dp.toPx() + (dynamicBoost * 1.5f * factor) + (sineVal * 8.dp.toPx())).coerceAtLeast(6.dp.toPx())
                } else {
                    8.dp.toPx()
                }

                val halfHeight = waveHeight / 2f
                val bx = startX + (b.toFloat() * (barWidth + 3.dp.toPx()))
                drawLine(
                    color = Color.White.copy(alpha = 0.9f),
                    start = Offset(bx, center.y - halfHeight),
                    end = Offset(bx, center.y + halfHeight),
                    strokeWidth = barWidth
                )
            }
        }
    }
}
