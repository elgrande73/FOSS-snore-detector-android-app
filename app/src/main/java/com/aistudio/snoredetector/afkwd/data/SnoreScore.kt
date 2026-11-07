package com.aistudio.snoredetector.afkwd.data

import kotlin.math.roundToInt

/** A score and the measured values behind it, so the UI can show what drives the number. */
data class ScoreBreakdown(
    val score: Int,
    val snoreIndex: Double,                 // Snoring minutes per monitored hour
    val episodesPerHour: Double,
    val longestEpisodeSeconds: Double,
    val loudnessAboveBackgroundDb: Double?,
    val snoreIndexPoints: Double,
    val episodePoints: Double,
    val longestEpisodePoints: Double,
    val loudnessPoints: Double
)

/**
 * The nightly snore score: 0 (no snoring recorded) to 100 (heavy snoring all night), lower is better.
 *
 *     score = 60 × min(snoring minutes per monitored hour / 30, 1)
 *           + 20 × min(episodes per monitored hour / 60, 1)
 *           + 10 × min(longest episode in seconds / 600, 1)
 *           + 10 × min(average level above background in dB / 30, 1)
 *
 * The reference values are fixed on purpose. They do not follow the user's detection thresholds,
 * so a score stays comparable after those change. The Statistics tab shows this formula to the
 * user; keep the two in step. It describes recorded sound only and is not a medical measure.
 */
object SnoreScore {
    const val SNORE_INDEX_WEIGHT = 60.0
    const val EPISODE_WEIGHT = 20.0
    const val LONGEST_EPISODE_WEIGHT = 10.0
    const val LOUDNESS_WEIGHT = 10.0

    const val FULL_SNORE_INDEX = 30.0       // Snoring minutes per hour
    const val FULL_EPISODES_PER_HOUR = 60.0
    const val FULL_LONGEST_EPISODE_SECONDS = 600.0
    const val FULL_LOUDNESS_DB = 30.0

    /** Returns null when nothing was monitored: no rate can be derived from zero time. */
    fun calculate(
        monitoredMinutes: Double,
        snoreMinutes: Double,
        episodes: Int,
        longestEpisodeSeconds: Double,
        loudnessAboveBackgroundDb: Double?
    ): ScoreBreakdown? {
        if (monitoredMinutes <= 0.0) return null
        val monitoredHours = monitoredMinutes / 60.0
        val snoreIndex = snoreMinutes / monitoredHours
        val episodesPerHour = episodes / monitoredHours

        val snoreIndexPoints = points(snoreIndex, FULL_SNORE_INDEX, SNORE_INDEX_WEIGHT)
        val episodePoints = points(episodesPerHour, FULL_EPISODES_PER_HOUR, EPISODE_WEIGHT)
        val longestPoints = points(longestEpisodeSeconds, FULL_LONGEST_EPISODE_SECONDS, LONGEST_EPISODE_WEIGHT)
        val loudnessPoints = points(loudnessAboveBackgroundDb ?: 0.0, FULL_LOUDNESS_DB, LOUDNESS_WEIGHT)

        return ScoreBreakdown(
            score = (snoreIndexPoints + episodePoints + longestPoints + loudnessPoints).roundToInt(),
            snoreIndex = snoreIndex,
            episodesPerHour = episodesPerHour,
            longestEpisodeSeconds = longestEpisodeSeconds,
            loudnessAboveBackgroundDb = loudnessAboveBackgroundDb,
            snoreIndexPoints = snoreIndexPoints,
            episodePoints = episodePoints,
            longestEpisodePoints = longestPoints,
            loudnessPoints = loudnessPoints
        )
    }

    private fun points(value: Double, fullAt: Double, weight: Double): Double =
        (value / fullAt).coerceIn(0.0, 1.0) * weight
}
