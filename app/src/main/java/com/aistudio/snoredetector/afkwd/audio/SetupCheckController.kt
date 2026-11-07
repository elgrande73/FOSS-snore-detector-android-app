package com.aistudio.snoredetector.afkwd.audio

import android.util.Log
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckAccumulator
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckReport
import com.aistudio.snoredetector.afkwd.dsp.SnoreAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SetupCheckProblem { PERMISSION_MISSING, MONITORING_ACTIVE, MIC_UNAVAILABLE }

sealed interface SetupCheckState {
    data object Idle : SetupCheckState
    data class Running(val elapsedSeconds: Int) : SetupCheckState
    data class Finished(val report: SetupCheckReport) : SetupCheckState
    data class Failed(val problem: SetupCheckProblem) : SetupCheckState
}

/**
 * Runs the setup check: a short capture fed through [SnoreAnalyzer] into a [SetupCheckAccumulator].
 *
 * Only one capture runs at a time and it never overlaps monitoring: [start] refuses while
 * [isMonitoring] is true, each frame checks it again, and [stopThen] lets the app start monitoring
 * only after the microphone is released. The source is closed on every way out of the capture.
 *
 * [start], [stop] and [stopThen] must be called from the main thread.
 */
class SetupCheckController(
    private val scope: CoroutineScope,
    private val isMonitoring: StateFlow<Boolean>,
    private val hasMicPermission: () -> Boolean,
    private val openSource: () -> SetupCheckAudioSource?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<SetupCheckState>(SetupCheckState.Idle)
    val state: StateFlow<SetupCheckState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(config: DetectionConfig) {
        if (job?.isCompleted == false) return
        val problem = when {
            isMonitoring.value -> SetupCheckProblem.MONITORING_ACTIVE
            !hasMicPermission() -> SetupCheckProblem.PERMISSION_MISSING
            else -> null
        }
        if (problem != null) {
            _state.value = SetupCheckState.Failed(problem)
            return
        }
        // Only this coroutine writes the state from here on, so a late frame can't undo a stop.
        job = scope.launch(ioDispatcher) {
            _state.value = SetupCheckState.Running(0)
            try {
                _state.value = capture(config)
            } catch (e: CancellationException) {
                _state.value = SetupCheckState.Idle
                throw e
            }
        }
    }

    /** Stops a running check and discards it. The state returns to Idle once the mic is released. */
    fun stop() {
        job?.cancel()
    }

    /** Runs [action] on the main thread, after stopping a running check and releasing the mic. */
    fun stopThen(action: () -> Unit) {
        val running = job
        if (running == null || running.isCompleted) {
            action()
            return
        }
        running.cancel()
        scope.launch {
            running.join()
            action()
        }
    }

    private suspend fun capture(config: DetectionConfig): SetupCheckState {
        val source = try {
            openSource()
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone access refused", e)
            return SetupCheckState.Failed(SetupCheckProblem.PERMISSION_MISSING)
        } ?: return SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE)

        try {
            val analyzer = SnoreAnalyzer()
            val accumulator = SetupCheckAccumulator(config)
            val buffer = ShortArray(FRAME_SIZE)
            var shortReads = 0

            while (accumulator.elapsedSeconds < DURATION_SECONDS) {
                val read = source.read(buffer)
                currentCoroutineContext().ensureActive()
                if (isMonitoring.value) return SetupCheckState.Failed(SetupCheckProblem.MONITORING_ACTIVE)
                if (read < 0) return SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE)

                // The FFT only takes whole frames, and a blocking read returns less only on trouble.
                if (read < FRAME_SIZE) {
                    if (++shortReads > MAX_SHORT_READS) return SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE)
                    delay(10)
                    continue
                }
                shortReads = 0

                accumulator.add(analyzer.analyze(buffer, config), read, source.isMediaPlaying())
                _state.value = SetupCheckState.Running(accumulator.elapsedSeconds.toInt())
            }
            return SetupCheckState.Finished(accumulator.report())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Setup check capture failed", e)
            return SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE)
        } finally {
            source.close()
        }
    }

    companion object {
        private const val TAG = "SetupCheck"
        private const val FRAME_SIZE = 1024
        private const val MAX_SHORT_READS = 100

        const val DURATION_SECONDS = 30

        /** How long the UI asks for quiet before the test sound, so the noise floor is measured. */
        const val QUIET_SECONDS = 10
    }
}
