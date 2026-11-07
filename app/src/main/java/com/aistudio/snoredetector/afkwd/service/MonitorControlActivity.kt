package com.aistudio.snoredetector.afkwd.service

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aistudio.snoredetector.afkwd.data.ErrorLogger

/**
 * Invisible entry point that lets automation apps start and stop the monitor (issue #7).
 *
 * It is an activity, not a broadcast receiver, because Android only lets a microphone foreground
 * service start while the app is in the foreground. A receiver would be refused on API 31+ and,
 * even when exempt, would get no microphone access from API 30 on. Being resumed for a moment
 * meets that condition, which is why a start waits for [onResume] and wakes the screen if needed.
 *
 * This is the only exported way into the monitor. It stays inert until the user opts in, accepts
 * only the two actions in [MonitorControl], never reads the caller's extras and returns nothing.
 */
open class MonitorControlActivity : Activity() {

    private var startPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val decision = MonitorControl.decide(
            action = intent?.action,
            automationEnabled = MonitorControl.isAutomationEnabled(this),
            hasMicrophonePermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            isRunning = SnoreDetectionService.isServiceRunning.value
        )

        when (decision) {
            MonitorControl.Decision.START -> {
                startPending = true
                showOverLockScreen()
                return
            }
            MonitorControl.Decision.STOP -> MonitorControl.stopMonitoring(this)
            MonitorControl.Decision.REJECT_DISABLED ->
                showToast("Automation Control is off. Turn it on in Snore Detector settings.")
            MonitorControl.Decision.REJECT_NO_MICROPHONE_PERMISSION ->
                showToast("Open Snore Detector and grant microphone access first.")
            MonitorControl.Decision.IGNORE -> Unit
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!startPending) return

        startPending = false
        try {
            MonitorControl.startMonitoring(this)
        } catch (e: IllegalStateException) {
            reportStartFailure(e)
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Never reached the foreground (e.g. the screen stayed off): drop the request now rather
        // than start monitoring unexpectedly whenever the phone is next used.
        if (startPending) {
            startPending = false
            reportStartFailure(null)
        }
        if (!isFinishing) finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun reportStartFailure(cause: Throwable?) {
        val message = "Could not start monitoring from automation."
        ErrorLogger.log(
            context = applicationContext,
            errorType = "AUTOMATION_START_FAILED",
            message = message,
            throwable = cause,
            component = "MonitorControl"
        )
        showToast(message)
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
