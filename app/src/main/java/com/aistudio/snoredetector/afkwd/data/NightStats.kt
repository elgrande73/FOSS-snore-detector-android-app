package com.aistudio.snoredetector.afkwd.data

import androidx.compose.runtime.Immutable
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

enum class NightStatus { OK, TOO_SHORT, NO_MONITORED_TIME }

/** Everything recorded during one night, which may be several sessions. */
@Immutable
data class NightSummary(
    val epochDay: Long,                     // Calendar date the night began on, days since 1970-01-01
    val startTime: Long,
    val endTime: Long,
    val sessionCount: Int,
    val monitoredMinutes: Double,
    val snoreEventCount: Int,
    val totalSnoreMinutes: Double,
    val longestEpisodeSeconds: Double,
    val avgPeakDb: Float?,
    val maxPeakDb: Float?,
    val backgroundDb: Float?,
    val appVersion: String?,
    val detectionSettings: String?,
    val status: NightStatus,
    val breakdown: ScoreBreakdown?
) {
    val score: Int? get() = breakdown?.score
}

data class TrendPoint(val epochDay: Long, val value: Double)

enum class ChangeKind { APP_UPDATE, SETTINGS }

data class ChangeMarker(val epochDay: Long, val kind: ChangeKind)

data class PeriodComparison(
    val recentAverage: Double?,
    val previousAverage: Double?,
    val recentNights: Int,
    val previousNights: Int
) {
    val change: Double?
        get() = if (recentAverage != null && previousAverage != null) recentAverage - previousAverage else null

    /** Null when there is nothing to compare against, including a previous average of 0. */
    val percentChange: Double?
        get() {
            val delta = change ?: return null
            val base = previousAverage ?: return null
            return if (base == 0.0) null else delta / base * 100.0
        }
}

/**
 * Turns stored sessions into nights and trends. All pure, with java.util calendars because
 * java.time needs API 26 and the app supports API 24.
 */
object NightStats {
    /** A night runs noon to noon, so 23:50 and 00:20 are the same night. */
    const val NIGHT_START_HOUR = 12

    /** Below this much monitored time a score is flagged and left out of trends by default. */
    const val MIN_RELIABLE_MINUTES = 120.0

    const val MOVING_AVERAGE_DAYS = 7
    const val COMPARISON_DAYS = 30

    private const val MILLIS_PER_DAY = 86_400_000L

    /** The night [timestamp] belongs to, as the local calendar date on which that night began. */
    fun nightEpochDay(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val local = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        if (local.get(Calendar.HOUR_OF_DAY) < NIGHT_START_HOUR) local.add(Calendar.DAY_OF_MONTH, -1)
        val utcDate = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        return utcDate.timeInMillis / MILLIS_PER_DAY
    }

    /** Midnight UTC of [epochDay], for formatting with a UTC date formatter. */
    fun epochDayToUtcMillis(epochDay: Long): Long = epochDay * MILLIS_PER_DAY

    /** One [NightSummary] per night, oldest first. */
    fun summarize(
        sessions: List<MonitoringSession>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<NightSummary> = sessions
        .groupBy { nightEpochDay(it.startTime, timeZone) }
        .toSortedMap()
        .map { (day, group) -> mergeNight(day, group.sortedBy { it.startTime }) }

    private fun mergeNight(epochDay: Long, sessions: List<MonitoringSession>): NightSummary {
        // A backfilled session has events but no monitored time. Counting those events against
        // another session's hours would inflate the rates, so it only stands in when alone.
        val measured = sessions.filterNot { it.isBackfilled }
        val used = measured.ifEmpty { sessions }

        val monitoredMinutes = used.sumOf { it.monitoredMinutes }
        val eventCount = used.sumOf { it.snoreEventCount }
        val snoreMinutes = used.sumOf { it.totalSnoreMinutes }
        val longest = used.maxOf { it.longestEpisodeSeconds }
        val avgPeak = weightedAverage(used) { it.avgPeakDb?.toDouble()?.to(it.snoreEventCount.toDouble()) }
        val background = weightedAverage(used) { it.backgroundDb?.toDouble()?.to(it.monitoredMinutes) }
        val loudness = if (avgPeak != null && background != null) {
            (avgPeak - background).coerceAtLeast(0.0)
        } else {
            null
        }

        return NightSummary(
            epochDay = epochDay,
            startTime = sessions.first().startTime,
            endTime = sessions.maxOf { it.endTime },
            sessionCount = sessions.size,
            monitoredMinutes = monitoredMinutes,
            snoreEventCount = eventCount,
            totalSnoreMinutes = snoreMinutes,
            longestEpisodeSeconds = longest,
            avgPeakDb = avgPeak?.toFloat(),
            maxPeakDb = used.mapNotNull { it.maxPeakDb }.maxOrNull(),
            backgroundDb = background?.toFloat(),
            appVersion = sessions.last().appVersion,
            detectionSettings = sessions.last().detectionSettings,
            status = when {
                measured.isEmpty() -> NightStatus.NO_MONITORED_TIME
                monitoredMinutes < MIN_RELIABLE_MINUTES -> NightStatus.TOO_SHORT
                else -> NightStatus.OK
            },
            breakdown = if (measured.isEmpty()) {
                null
            } else {
                SnoreScore.calculate(monitoredMinutes, snoreMinutes, eventCount, longest, loudness)
            }
        )
    }

    private fun weightedAverage(
        sessions: List<MonitoringSession>,
        valueAndWeight: (MonitoringSession) -> Pair<Double, Double>?
    ): Double? {
        val pairs = sessions.mapNotNull(valueAndWeight).filter { it.second > 0.0 }
        val totalWeight = pairs.sumOf { it.second }
        return if (pairs.isEmpty()) null else pairs.sumOf { it.first * it.second } / totalWeight
    }

    /** Nights that count towards trends: scored, and long enough unless [includeShort] is set. */
    fun trendNights(nights: List<NightSummary>, includeShort: Boolean): List<NightSummary> =
        nights.filter { it.score != null && (includeShort || it.status == NightStatus.OK) }

    /**
     * For each night, the mean score of the nights within the [windowDays] calendar days ending on
     * it. The window is measured in days, not rows, so a skipped week does not pull in old nights.
     */
    fun movingAverage(
        nights: List<NightSummary>,
        windowDays: Int = MOVING_AVERAGE_DAYS
    ): List<TrendPoint> {
        val scored = nights.filter { it.score != null }
        return scored.map { night ->
            val window = (night.epochDay - windowDays + 1)..night.epochDay
            val values = scored.filter { it.epochDay in window }.mapNotNull { it.score }
            TrendPoint(night.epochDay, values.average())
        }
    }

    /** Marks the first night on a new app version or with new detection settings. */
    fun changeMarkers(nights: List<NightSummary>): List<ChangeMarker> {
        val markers = mutableListOf<ChangeMarker>()
        var version: String? = null
        var settings: String? = null
        for (night in nights) {
            night.appVersion?.let {
                if (version != null && it != version) markers += ChangeMarker(night.epochDay, ChangeKind.APP_UPDATE)
                version = it
            }
            night.detectionSettings?.let {
                if (settings != null && it != settings) markers += ChangeMarker(night.epochDay, ChangeKind.SETTINGS)
                settings = it
            }
        }
        return markers
    }

    /** Compares the [days] up to and including [today] with the [days] before that. */
    fun comparePeriods(
        nights: List<NightSummary>,
        today: Long,
        days: Int = COMPARISON_DAYS
    ): PeriodComparison {
        val recentStart = today - days + 1
        val recent = nights.filter { it.epochDay in recentStart..today }.mapNotNull { it.score }
        val previous = nights.filter { it.epochDay in (recentStart - days) until recentStart }
            .mapNotNull { it.score }
        return PeriodComparison(
            recentAverage = recent.takeIf { it.isNotEmpty() }?.average(),
            previousAverage = previous.takeIf { it.isNotEmpty() }?.average(),
            recentNights = recent.size,
            previousNights = previous.size
        )
    }
}
