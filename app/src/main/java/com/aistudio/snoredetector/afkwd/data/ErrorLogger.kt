package com.aistudio.snoredetector.afkwd.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, crash-proof, privacy-first diagnostic error logger.
 * Stores error events strictly in the local offline Room database without any external telemetry.
 */
object ErrorLogger {
    private const val TAG = "ErrorLogger"
    private val loggerScope = CoroutineScope(Dispatchers.IO)

    /**
     * Safely logs an error event to the local database.
     * Guaranteed to never throw or disrupt normal application operations.
     */
    fun log(
        context: Context,
        errorType: String,
        message: String,
        throwable: Throwable? = null,
        component: String = "App",
        additionalDiagnostics: Map<String, String>? = null
    ) {
        try {
            val diagnosticInfo = buildDiagnosticDetails(throwable, additionalDiagnostics)
            val logEntry = ErrorLog(
                timestamp = System.currentTimeMillis(),
                errorType = errorType,
                message = message,
                diagnosticDetails = diagnosticInfo,
                component = component
            )
            Log.e(TAG, "[$component | $errorType] $message")

            loggerScope.launch {
                try {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.errorLogDao().insertLog(logEntry)
                } catch (dbError: Throwable) {
                    Log.e(TAG, "Failed to write error log to Room database", dbError)
                }
            }
        } catch (loggerError: Throwable) {
            // Absolute catch-all so error logging itself can never crash the host application
            Log.e(TAG, "ErrorLogger encountered internal exception", loggerError)
        }
    }

    /**
     * Compiles privacy-safe diagnostic telemetry: OS version, device architecture, and stacktrace.
     * Contains NO user identifiers, personal names, geolocation, or raw audio data.
     */
    fun buildDiagnosticDetails(
        throwable: Throwable?,
        additionalDiagnostics: Map<String, String>?
    ): String {
        return buildString {
            appendLine("=== System Diagnostics ===")
            appendLine("Platform: Android API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Architecture: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"}")

            if (!additionalDiagnostics.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Operational Context ===")
                additionalDiagnostics.forEach { (k, v) ->
                    appendLine("$k: $v")
                }
            }

            if (throwable != null) {
                appendLine()
                appendLine("=== Stack Trace ===")
                appendLine("${throwable.javaClass.name}: ${throwable.message ?: "No message provided"}")
                try {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    appendLine(sw.toString().trimEnd())
                } catch (_: Throwable) {
                    appendLine("Failed to format full stack trace.")
                }
            }
        }.trim()
    }

    /**
     * Formats an ErrorLog into a clean, human-readable plain text (.txt) document suitable for export or sharing.
     */
    fun formatAsPlainText(log: ErrorLog): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val formattedDate = sdf.format(Date(log.timestamp))

        return buildString {
            appendLine("=================================================================")
            appendLine("              SNORE DETECTOR - SYSTEM ERROR LOG                  ")
            appendLine("=================================================================")
            appendLine("Timestamp:     $formattedDate (${log.timestamp})")
            appendLine("Component:     ${log.component}")
            appendLine("Error Type:    ${log.errorType}")
            appendLine("Message:       ${log.message}")
            appendLine("=================================================================")
            appendLine()
            appendLine("DIAGNOSTIC & TECHNICAL DETAILS:")
            appendLine(log.diagnosticDetails)
            appendLine()
            appendLine("=================================================================")
            appendLine("Snore Detector — 100% Offline, Privacy-First, FOSS Software")
            appendLine("=================================================================")
        }
    }

    /**
     * Exports a formatted error log plain text string directly to a SAF Document Uri.
     */
    suspend fun exportErrorLogToUri(context: Context, log: ErrorLog, destinationUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val textContent = formatAsPlainText(log)
                context.contentResolver.openOutputStream(destinationUri, "wt")?.use { outputStream ->
                    outputStream.write(textContent.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write error log to target URI", e)
                false
            }
        }
    }
}

