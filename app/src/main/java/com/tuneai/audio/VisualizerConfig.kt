package com.tuneai.audio

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.tuneai.audio.nativebridge.NativeAudioEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun HardwareVisualizer(
    modifier: Modifier = Modifier,
    engine: NativeAudioEngine
) {
    var fftData by remember { mutableStateOf(FloatArray(0)) }

    LaunchedEffect(engine) {
        while (isActive) {
            fftData = engine.getFftData()
            delay(16L)
        }
    }

    Canvas(modifier = modifier) {
        val barCount = if (fftData.isNotEmpty()) min(fftData.size, 64) else 48
        val barWidth = size.width / barCount
        val spacing = barWidth * 0.35f
        val usableWidth = barWidth - spacing
        val gradient = Brush.verticalGradient(
            listOf(
                Color(0xFF6F9BFF),
                Color(0xFF6FD2FF),
                Color(0xFF5AF2C5)
            )
        )

        for (index in 0 until barCount) {
            val raw = if (fftData.isNotEmpty()) fftData[index % fftData.size] else 0f
            val magnitude = sqrt(abs(raw)).coerceIn(0f, 1f)
            val barHeight = magnitude * size.height
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(
                    x = index * barWidth + spacing / 2f,
                    y = size.height - barHeight
                ),
                size = Size(usableWidth, barHeight),
                cornerRadius = CornerRadius(8f, 8f)
            )
        }
    }
}
