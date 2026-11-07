package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.audio.SetupCheckAudioSource
import com.aistudio.snoredetector.afkwd.audio.SetupCheckController
import com.aistudio.snoredetector.afkwd.audio.SetupCheckProblem
import com.aistudio.snoredetector.afkwd.audio.SetupCheckState
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SetupVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // android.util.Log
@Config(sdk = [34])
class SetupCheckControllerTest {

    private class FakeSource(
        private val onRead: (frame: Int, buffer: ShortArray) -> Int = { _, buffer -> buffer.size }
    ) : SetupCheckAudioSource {
        var reads = 0
        var closes = 0
        var mediaPlaying = false

        override fun read(buffer: ShortArray): Int = onRead(reads++, buffer)
        override fun isMediaPlaying() = mediaPlaying
        override fun close() {
            closes++
        }
    }

    private inner class Harness(
        scheduler: TestCoroutineScheduler,
        var source: FakeSource? = FakeSource(),
        val permission: Boolean = true,
        monitoring: Boolean = false
    ) {
        private val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val isMonitoring = MutableStateFlow(monitoring)
        var opens = 0
        var openFailure: Exception? = null
        val controller = SetupCheckController(
            scope = scope,
            isMonitoring = isMonitoring,
            hasMicPermission = { permission },
            openSource = {
                opens++
                openFailure?.let { throw it }
                source
            },
            ioDispatcher = dispatcher
        )
    }

    private fun SetupCheckController.start() = start(DetectionConfig())

    @Test
    fun completedCheckReadsThirtySecondsReportsAndReleasesTheMic() = runTest {
        val source = FakeSource()
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        val state = harness.controller.state.value as SetupCheckState.Finished
        assertEquals(469, source.reads) // 30 s at 16 kHz in frames of 1024
        assertEquals(1, source.closes)
        assertEquals(SetupVerdict.NO_SIGNAL, state.report.verdict)
    }

    @Test
    fun capturedAudioReachesTheReport() = runTest {
        val source = FakeSource { frame, buffer ->
            for (i in buffer.indices) {
                val amplitude = if (frame in 200..250) 3000.0 else 10.0
                buffer[i] = (sin(2.0 * PI * 200.0 * (frame * 1024 + i) / 16000.0) * amplitude).toInt().toShort()
            }
            buffer.size
        }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        val state = harness.controller.state.value as SetupCheckState.Finished
        assertEquals(SetupVerdict.DETECTED, state.report.verdict)
    }

    @Test
    fun progressIsReportedWhileRunning() = runTest {
        val seen = mutableListOf<Int>()
        lateinit var harness: Harness
        val source = FakeSource { frame, buffer ->
            (harness.controller.state.value as? SetupCheckState.Running)?.let { seen.add(it.elapsedSeconds) }
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals((0..29).toList(), seen.distinct())
    }

    @Test
    fun mediaStateFromTheSourceReachesTheReport() = runTest {
        val source = FakeSource().apply { mediaPlaying = true }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        val state = harness.controller.state.value as SetupCheckState.Finished
        assertTrue(state.report.pausedForMedia)
    }

    @Test
    fun refusesToStartWhileMonitoringAndNeverOpensTheMic() = runTest {
        val harness = Harness(testScheduler, monitoring = true)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(0, harness.opens)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MONITORING_ACTIVE), harness.controller.state.value)
    }

    @Test
    fun refusesToStartWithoutPermissionAndNeverOpensTheMic() = runTest {
        val harness = Harness(testScheduler, permission = false)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(0, harness.opens)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.PERMISSION_MISSING), harness.controller.state.value)
    }

    @Test
    fun permissionRefusedByTheSystemIsReportedAsMissingPermission() = runTest {
        val harness = Harness(testScheduler).apply { openFailure = SecurityException("denied") }

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(SetupCheckState.Failed(SetupCheckProblem.PERMISSION_MISSING), harness.controller.state.value)
    }

    @Test
    fun micThatCannotBeOpenedIsReported() = runTest {
        val harness = Harness(testScheduler, source = null)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE), harness.controller.state.value)
    }

    @Test
    fun monitoringStartingMidCheckStopsItAndReleasesTheMic() = runTest {
        lateinit var harness: Harness
        val source = FakeSource { frame, buffer ->
            if (frame == 20) harness.isMonitoring.value = true
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(21, source.reads)
        assertEquals(1, source.closes)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MONITORING_ACTIVE), harness.controller.state.value)
    }

    @Test
    fun stoppingMidCheckReleasesTheMicAndDiscardsTheResult() = runTest {
        lateinit var harness: Harness
        val source = FakeSource { frame, buffer ->
            if (frame == 20) harness.controller.stop()
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(21, source.reads)
        assertEquals(1, source.closes)
        assertEquals(SetupCheckState.Idle, harness.controller.state.value)
    }

    @Test
    fun cancellingTheOwningScopeReleasesTheMic() = runTest {
        lateinit var harness: Harness
        val source = FakeSource { frame, buffer ->
            if (frame == 20) harness.scope.cancel()
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(1, source.closes)
    }

    @Test
    fun readErrorStopsTheCheckAndReleasesTheMic() = runTest {
        val source = FakeSource { frame, buffer -> if (frame == 20) -6 else buffer.size }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(1, source.closes)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE), harness.controller.state.value)
    }

    @Test
    fun readExceptionStopsTheCheckAndReleasesTheMic() = runTest {
        val source = FakeSource { frame, buffer ->
            check(frame != 20) { "AudioRecord died" }
            buffer.size
        }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(1, source.closes)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE), harness.controller.state.value)
    }

    @Test
    fun aMicThatStopsDeliveringAudioCannotHangTheCheck() = runTest {
        val source = FakeSource { _, _ -> 0 }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(1, source.closes)
        assertEquals(SetupCheckState.Failed(SetupCheckProblem.MIC_UNAVAILABLE), harness.controller.state.value)
    }

    @Test
    fun partialFramesAreSkippedInsteadOfCrashingTheFft() = runTest {
        val source = FakeSource { frame, buffer -> if (frame % 2 == 0) 512 else buffer.size }
        val harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertTrue(harness.controller.state.value is SetupCheckState.Finished)
        assertEquals(1, source.closes)
    }

    @Test
    fun secondStartWhileRunningDoesNotOpenTheMicAgain() = runTest {
        lateinit var harness: Harness
        val source = FakeSource { frame, buffer ->
            if (frame == 20) harness.controller.start()
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertEquals(1, harness.opens)
    }

    @Test
    fun checkCanRunAgainAfterFinishing() = runTest {
        val harness = Harness(testScheduler)

        harness.controller.start()
        advanceUntilIdle()
        harness.source = FakeSource()
        harness.controller.start()
        advanceUntilIdle()

        assertEquals(2, harness.opens)
        assertTrue(harness.controller.state.value is SetupCheckState.Finished)
    }

    @Test
    fun monitoringStartsOnlyAfterARunningCheckHasReleasedTheMic() = runTest {
        var closesWhenMonitoringStarted = -1
        var actionRan = false
        lateinit var harness: Harness
        lateinit var source: FakeSource
        source = FakeSource { frame, buffer ->
            if (frame == 20) {
                harness.controller.stopThen {
                    actionRan = true
                    closesWhenMonitoringStarted = source.closes
                }
                assertFalse("must wait for the mic", actionRan)
            }
            buffer.size
        }
        harness = Harness(testScheduler, source)

        harness.controller.start()
        advanceUntilIdle()

        assertTrue(actionRan)
        assertEquals(1, closesWhenMonitoringStarted)
    }

    @Test
    fun monitoringStartsImmediatelyWhenNoCheckIsRunning() = runTest {
        val harness = Harness(testScheduler)
        var actionRan = false

        harness.controller.stopThen { actionRan = true }
        assertTrue(actionRan)

        harness.controller.start()
        advanceUntilIdle()
        actionRan = false
        harness.controller.stopThen { actionRan = true }
        assertTrue(actionRan)
    }
}
