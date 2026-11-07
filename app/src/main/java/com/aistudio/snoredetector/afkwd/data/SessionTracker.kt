package com.aistudio.snoredetector.afkwd.data

/**
 * Follows a running session frame by frame: how much audio was really monitored, and how loud the
 * room was when nobody snored. A session can span several capture runs (the microphone can be
 * re-routed overnight), so this lives as long as the service rather than the recording loop.
 *
 * Levels go into a histogram because a median ignores door slams and costs a few bytes all night.
 */
class SessionTracker {
    private var monitoredMs = 0.0
    private val quietFrames = IntArray(MAX_DB + 1)

    /**
     * @param isPaused detection was not really listening: media standby, or the input delivered
     *                 only zero samples.
     */
    fun onFrame(durationMs: Double, db: Float, isSnoring: Boolean, isPaused: Boolean) {
        if (isPaused) return
        monitoredMs += durationMs
        if (!isSnoring) quietFrames[db.toInt().coerceIn(0, MAX_DB)]++
    }

    val monitoredMinutes: Double
        get() = monitoredMs / 60_000.0

    /** Median level of the non-snoring frames, or null if there were none. */
    val backgroundDb: Float?
        get() {
            val total = quietFrames.sumOf { it.toLong() }
            if (total == 0L) return null
            var seen = 0L
            quietFrames.forEachIndexed { db, count ->
                seen += count
                if (seen * 2 >= total) return db.toFloat()
            }
            return null
        }

    private companion object {
        const val MAX_DB = 120 // Top of the SnoreAnalyzer dB scale
    }
}
