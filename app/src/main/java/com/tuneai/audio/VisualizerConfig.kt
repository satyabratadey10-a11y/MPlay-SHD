package com.tuneai.audio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class VisualizerConfig(
    val barCount: Int = 64,
    val minBarHeight: Float = 0.06f,
    val cornerRadius: Float = 8f,
    val barGapRatio: Float = 0.35f,
    val strokeWidth: Dp = 1.dp,
    val gradientColors: List<Color> = listOf(
        Color(0xFFFF3B30),
        Color(0xFF34C759),
        Color(0xFF0A84FF)
    )
)
