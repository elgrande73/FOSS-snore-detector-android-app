package com.aistudio.snoredetector.afkwd.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Longest silence that still counts as the same session. Long enough that a quiet stretch
 * does not split a night in two, and shorter than the time awake between two nights.
 */
const val SESSION_GAP_MILLIS = 4L * 60L * 60L * 1000L

/**
 * A run of consecutive snore events recorded close together, typically one night.
 * Sessions are inferred from the event timestamps because the database does not record
 * when monitoring started or stopped.
 */
data class SnoreSession(val events: List<SnoreEvent>) {
    val startMillis: Long = events.minOf { it.timestamp }
    val endMillis: Long = events.maxOf { it.timestamp }
}

/**
 * Splits [events], sorted by timestamp in either direction, wherever two neighbouring events
 * are more than [gapMillis] apart. The order of [events] is preserved.
 */
fun groupEventsIntoSessions(
    events: List<SnoreEvent>,
    gapMillis: Long = SESSION_GAP_MILLIS
): List<SnoreSession> {
    val sessions = mutableListOf<SnoreSession>()
    var current = mutableListOf<SnoreEvent>()
    for (event in events) {
        val previous = current.lastOrNull()
        if (previous != null && abs(event.timestamp - previous.timestamp) > gapMillis) {
            sessions.add(SnoreSession(current))
            current = mutableListOf()
        }
        current.add(event)
    }
    if (current.isNotEmpty()) sessions.add(SnoreSession(current))
    return sessions
}

/**
 * Human-readable time span of a session, for example "Thu, Nov 05, 11:12 PM – Fri, Nov 06, 06:41 AM".
 * The date is not repeated when the session starts and ends on the same day.
 */
fun formatSessionRange(
    session: SnoreSession,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val dateTime = SimpleDateFormat("EEE, MMM dd, hh:mm a", locale).apply { this.timeZone = timeZone }
    val time = SimpleDateFormat("hh:mm a", locale).apply { this.timeZone = timeZone }
    val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { this.timeZone = timeZone }

    val start = Date(session.startMillis)
    val end = Date(session.endMillis)
    val startText = dateTime.format(start)
    val sameDay = day.format(start) == day.format(end)
    return when {
        !sameDay -> "$startText – ${dateTime.format(end)}"
        time.format(start) == time.format(end) -> startText
        else -> "$startText – ${time.format(end)}"
    }
}
