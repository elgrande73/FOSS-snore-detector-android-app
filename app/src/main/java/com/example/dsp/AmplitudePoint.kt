package com.example.dsp

/**
 * Data model for plotting the measurement timeline in Jetpack Compose Canvas.
 */
data class AmplitudePoint(
    val dbValue: Float,
    val isSnore: Boolean,
    val timestamp: Long
)
