package com.aistudio.snoredetector.afkwd.service

import android.content.Context
import android.os.PowerManager
import androidx.core.content.edit
import com.aistudio.snoredetector.afkwd.data.ErrorLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A monitoring session as last persisted by the service.
 *
 * It lives outside the process on purpose: when Android or a phone maker kills the process no code
 * runs, so a session that is still stored after the service is gone is the only evidence that
 * monitoring ended early. It is removed only by a clean stop.
 */
internal data class MonitoringSession(val startedAt: Long, val lastActiveAt: Long)

/** What a first onStartCommand should do, given what was left behind by the previous process. */
internal sealed interface SessionStart {
    /** The user started monitoring. [abandoned] is an older session that never ended cleanly. */
    data class Fresh(val abandoned: MonitoringSession?) : SessionStart

    /** Android restarted the service in the middle of [session]. */
    data class Resume(val session: MonitoringSession) : SessionStart

    /** Android restarted the service but there is nothing left to resume, so it must not record. */
    data class Refuse(val expired: MonitoringSession?) : SessionStart
}

internal fun decideSessionStart(
    stored: MonitoringSession?,
    isSystemRestart: Boolean,
    now: Long,
    limitMs: Long
): SessionStart = when {
    !isSystemRestart -> SessionStart.Fresh(abandoned = stored)
    stored == null -> SessionStart.Refuse(expired = null)
    now - stored.startedAt >= limitMs -> SessionStart.Refuse(expired = stored)
    else -> SessionStart.Resume(stored)
}

/** The end of a gap: monitoring would have stopped by itself at the session limit anyway. */
internal fun interruptionEnd(session: MonitoringSession, now: Long, limitMs: Long): Long =
    minOf(now, session.startedAt + limitMs).coerceAtLeast(session.lastActiveAt)

/** SharedPreferences rather than Room: the record has to be a cheap write every heartbeat. */
internal class MonitoringSessionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("snore_monitoring_session", Context.MODE_PRIVATE)

    fun activeSession(): MonitoringSession? {
        if (!prefs.contains(KEY_STARTED_AT)) return null
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        return MonitoringSession(startedAt, prefs.getLong(KEY_LAST_ACTIVE_AT, startedAt))
    }

    fun begin(now: Long) {
        prefs.edit { putLong(KEY_STARTED_AT, now).putLong(KEY_LAST_ACTIVE_AT, now) }
    }

    fun heartbeat(now: Long) {
        prefs.edit { putLong(KEY_LAST_ACTIVE_AT, now) }
    }

    fun clear() {
        prefs.edit { remove(KEY_STARTED_AT).remove(KEY_LAST_ACTIVE_AT) }
    }

    /** Latest interruption the user has not acknowledged on the dashboard yet. */
    var pendingNotice: String?
        get() = prefs.getString(KEY_PENDING_NOTICE, null)
        set(value) = prefs.edit { putString(KEY_PENDING_NOTICE, value) }

    var isBatteryHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_HINT_DISMISSED, value) }

    private companion object {
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_LAST_ACTIVE_AT = "lastActiveAt"
        const val KEY_PENDING_NOTICE = "pendingNotice"
        const val KEY_BATTERY_HINT_DISMISSED = "batteryHintDismissed"
    }
}

internal object MonitoringInterruptions {
    const val ERROR_TYPE = "MONITORING_INTERRUPTED"

    /**
     * How long after the last heartbeat a session may still be picked up by a system restart.
     * Opening the app during that window must not close a session the service is about to resume.
     */
    private const val RESTART_GRACE_MS = 60_000L

    private fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    fun gapMessage(from: Long, to: Long): String = "Monitoring interrupted ${clock(from)}–${clock(to)}."

    fun resumedMessage(from: Long, to: Long): String =
        "Monitoring interrupted ${clock(from)}–${clock(to)}, then resumed."

    fun restartRefusedMessage(at: Long): String =
        "Monitoring stopped at ${clock(at)}. Android did not allow it to restart."

    fun silentRestartMessage(at: Long): String =
        "Monitoring stopped at ${clock(at)}. Android kept the microphone silent after a restart."

    /** Shows [message] on the dashboard and keeps it in History under System Errors. */
    fun record(context: Context, store: MonitoringSessionStore, message: String, session: MonitoringSession?) {
        store.pendingNotice = message
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        ErrorLogger.log(
            context = context,
            errorType = ERROR_TYPE,
            message = message,
            component = "Service",
            additionalDiagnostics = mapOf(
                "SessionStart" to (session?.startedAt?.toString() ?: "N/A"),
                "LastActive" to (session?.lastActiveAt?.toString() ?: "N/A"),
                "BatteryOptimizationDisabled" to
                    powerManager.isIgnoringBatteryOptimizations(context.packageName).toString()
            )
        )
    }

    /**
     * Reports a session whose process died without Android ever restarting it. Nothing can run at
     * the moment of such a kill, so this happens the next time the app is opened.
     */
    fun reportAbandonedSession(context: Context, now: Long = System.currentTimeMillis()) {
        if (SnoreDetectionService.isServiceRunning.value) return
        val store = MonitoringSessionStore(context)
        val session = store.activeSession() ?: return
        if (now - session.lastActiveAt < RESTART_GRACE_MS) return
        val end = interruptionEnd(session, now, SnoreDetectionService.LIMIT_DURATION_MS)
        record(context, store, gapMessage(session.lastActiveAt, end), session)
        store.clear()
    }
}
