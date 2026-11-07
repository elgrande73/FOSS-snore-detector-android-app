package com.aistudio.snoredetector.afkwd

import com.aistudio.snoredetector.afkwd.dsp.AnalysisResult
import com.aistudio.snoredetector.afkwd.dsp.CriterionResult
import com.aistudio.snoredetector.afkwd.dsp.CriterionStatus
import com.aistudio.snoredetector.afkwd.dsp.DetectionConfig
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckAccumulator
import com.aistudio.snoredetector.afkwd.dsp.SetupCheckReport
import com.aistudio.snoredetector.afkwd.dsp.SetupCriterion
import com.aistudio.snoredetector.afkwd.dsp.SetupVerdict
import com.aistudio.snoredetector.afkwd.dsp.SnoreAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class SetupCheckAccumulatorTest {

    private val analyzer = SnoreAnalyzer()
    private val random = Random(19)

    private companion object {
        const val FRAME = 1024
        const val FRAMES_PER_SECOND = 16000f / FRAME
        const val QUIET_DB = 45f
        const val SNORE_DB = 70f
    }

    private fun seconds(value: Float) = (value * FRAMES_PER_SECOND).toInt()

    /** Quiet room at low level: below the default volume threshold. */
    private fun backgroundFrame(amplitude: Double = 10.0) =
        ShortArray(FRAME) { (random.nextGaussian() * amplitude).toInt().toShort() }

    private fun toneFrame(frequency: Double, amplitude: Double, index: Int) = ShortArray(FRAME) { i ->
        val sample = sin(2.0 * PI * frequency * (index * FRAME + i) / 16000.0) * amplitude
        (sample + random.nextGaussian() * 10.0).toInt().toShort()
    }

    private fun SetupCheckAccumulator.feed(config: DetectionConfig, frames: Int, frame: (Int) -> ShortArray) {
        repeat(frames) { add(analyzer.analyze(frame(it), config), FRAME) }
    }

    /** 10 s of background, then [soundSeconds] of [sound], then background up to 30 s. */
    private fun runCheck(
        config: DetectionConfig = DetectionConfig(),
        soundSeconds: Float = 3f,
        sound: (Int) -> ShortArray
    ): SetupCheckReport = SetupCheckAccumulator(config).apply {
        feed(config, seconds(10f)) { backgroundFrame() }
        feed(config, seconds(soundSeconds), sound)
        feed(config, seconds(20f - soundSeconds)) { backgroundFrame() }
    }.report()

    private fun analysis(
        db: Float = QUIET_DB,
        zcr: Float = 0.05f,
        band: Float = 0.1f,
        lowFreq: Float = 0.9f,
        isSnoring: Boolean = false,
        rms: Float = 0.01f
    ) = AnalysisResult(
        rms = rms,
        db = db,
        zcr = zcr,
        bandEnergy = band,
        lowFreqEnergyRatio = lowFreq,
        rmsThresholdMet = false,
        zcrThresholdMet = false,
        bandThresholdMet = false,
        lowFreqThresholdMet = false,
        isSnoring = isSnoring
    )

    private fun SetupCheckAccumulator.addFrames(count: Int, frame: AnalysisResult) {
        repeat(count) { add(frame, FRAME) }
    }

    private fun SetupCheckReport.row(criterion: SetupCriterion): CriterionResult =
        criteria.single { it.criterion == criterion }

    private fun SetupCheckReport.status(criterion: SetupCriterion) = row(criterion).status

    @Test
    fun snoreLikeSoundPassesEveryCriterionAndIsDetected() {
        val report = runCheck { toneFrame(200.0, 3000.0, it) }

        assertEquals(SetupVerdict.DETECTED, report.verdict)
        assertTrue(report.criteria.toString(), report.criteria.all { it.status == CriterionStatus.PASS })
        assertEquals(3f, report.row(SetupCriterion.DURATION).measured!!, 0.2f)
    }

    @Test
    fun highPitchedSoundFailsTheSpectralCriteria() {
        val report = runCheck { toneFrame(2500.0, 3000.0, it) }

        assertEquals(CriterionStatus.PASS, report.status(SetupCriterion.VOLUME))
        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.ZCR))
        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.LOW_FREQ_RATIO))
        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.DURATION))
        assertEquals(SetupVerdict.NOT_DETECTED, report.verdict)
    }

    @Test
    fun soundThatIsTooQuietFailsVolumeButStillCountsAsHeard() {
        val config = DetectionConfig(rmsDbThreshold = 70f)

        val report = runCheck(config) { toneFrame(200.0, 40.0, it) }

        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.VOLUME))
        assertEquals(SetupVerdict.NOT_DETECTED, report.verdict)
    }

    @Test
    fun soundShorterThanMinimumDurationFailsOnlyDuration() {
        val report = runCheck(soundSeconds = 0.5f) { toneFrame(200.0, 3000.0, it) }

        assertEquals(CriterionStatus.PASS, report.status(SetupCriterion.VOLUME))
        assertEquals(CriterionStatus.PASS, report.status(SetupCriterion.ZCR))
        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.DURATION))
        assertEquals(SetupVerdict.NOT_DETECTED, report.verdict)
    }

    @Test
    fun disabledMethodsAreReportedAsOffAndDoNotBlockDetection() {
        val config = DetectionConfig(useZcr = false, useLowFreqRatio = false, useBandEnergy = false)

        val report = runCheck(config) { toneFrame(2500.0, 3000.0, it) }

        assertEquals(CriterionStatus.DISABLED, report.status(SetupCriterion.ZCR))
        assertEquals(CriterionStatus.DISABLED, report.status(SetupCriterion.BAND_ENERGY))
        assertEquals(CriterionStatus.DISABLED, report.status(SetupCriterion.LOW_FREQ_RATIO))
        assertNull(report.row(SetupCriterion.ZCR).measured)
        assertEquals(SetupVerdict.DETECTED, report.verdict)
    }

    @Test
    fun userThresholdsAreTheOnesJudged() {
        val config = DetectionConfig(rmsDbThreshold = 85f, minDurationSeconds = 5f)

        val report = runCheck(config) { toneFrame(200.0, 500.0, it) }

        assertEquals(CriterionStatus.FAIL, report.status(SetupCriterion.VOLUME))
        assertEquals(85f, report.row(SetupCriterion.VOLUME).threshold, 0.001f)
        assertEquals(5f, report.row(SetupCriterion.DURATION).threshold, 0.001f)
    }

    @Test
    fun noiseFloorIgnoresTheTestSoundEvenWhenItFillsMostOfTheCheck() {
        val accumulator = SetupCheckAccumulator(DetectionConfig())
        accumulator.addFrames(150, analysis(db = QUIET_DB))
        accumulator.addFrames(320, analysis(db = SNORE_DB))

        assertEquals(QUIET_DB, accumulator.report().noiseFloorDb, 0.001f)
    }

    @Test
    fun criteriaAreJudgedOnTheTestSoundNotTheBackground() {
        val config = DetectionConfig(zcrThreshold = 0.15f)
        val accumulator = SetupCheckAccumulator(config)
        accumulator.addFrames(300, analysis(db = QUIET_DB, zcr = 0.4f))
        accumulator.addFrames(100, analysis(db = SNORE_DB, zcr = 0.1f))

        val row = accumulator.report().row(SetupCriterion.ZCR)

        assertEquals(CriterionStatus.PASS, row.status)
        assertEquals(0.1f, row.measured!!, 0.001f)
    }

    @Test
    fun valuesExactlyOnTheThresholdPassLikeTheAnalyzer() {
        val config = DetectionConfig()
        val accumulator = SetupCheckAccumulator(config)
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        accumulator.addFrames(
            100,
            analysis(
                db = config.rmsDbThreshold,
                zcr = config.zcrThreshold,
                band = config.bandEnergyThreshold,
                lowFreq = config.lowFreqRatioThreshold
            )
        )

        val report = accumulator.report()

        listOf(SetupCriterion.VOLUME, SetupCriterion.ZCR, SetupCriterion.BAND_ENERGY, SetupCriterion.LOW_FREQ_RATIO)
            .forEach { assertEquals(it.name, CriterionStatus.PASS, report.status(it)) }
    }

    @Test
    fun quietRoomReportsNoTestSoundInsteadOfFailing() {
        val config = DetectionConfig()
        val accumulator = SetupCheckAccumulator(config).apply { feed(config, 469) { backgroundFrame() } }

        val report = accumulator.report()

        assertEquals(SetupVerdict.NO_TEST_SOUND, report.verdict)
        assertTrue(report.criteria.all { it.status == CriterionStatus.NOT_TESTED })
        assertEquals(QUIET_DB, report.noiseFloorDb, 5f)
        assertFalse(report.noiseFloorReachesVolumeThreshold)
    }

    @Test
    fun aSingleClickIsNotTreatedAsATestSound() {
        val accumulator = SetupCheckAccumulator(DetectionConfig())
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        accumulator.addFrames(SetupCheckAccumulator.MIN_TEST_SOUND_FRAMES - 1, analysis(db = SNORE_DB))

        assertEquals(SetupVerdict.NO_TEST_SOUND, accumulator.report().verdict)
    }

    @Test
    fun allZeroInputIsReportedAsNoSignal() {
        val config = DetectionConfig()
        val accumulator = SetupCheckAccumulator(config).apply { feed(config, 469) { ShortArray(FRAME) } }

        assertEquals(SetupVerdict.NO_SIGNAL, accumulator.report().verdict)
    }

    @Test
    fun steadyLoudRumbleIsReportedAsBackgroundThatWouldBeLogged() {
        val config = DetectionConfig()
        val accumulator = SetupCheckAccumulator(config).apply { feed(config, 469) { toneFrame(200.0, 3000.0, it) } }

        val report = accumulator.report()

        assertEquals(SetupVerdict.BACKGROUND_DETECTED, report.verdict)
        assertTrue(report.noiseFloorReachesVolumeThreshold)
    }

    @Test
    fun gapShorterThanTheServiceLimitStaysOneEvent() {
        val config = DetectionConfig(minDurationSeconds = 3f)
        val accumulator = SetupCheckAccumulator(config)
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        accumulator.addFrames(seconds(1f), analysis(db = SNORE_DB, isSnoring = true))
        accumulator.addFrames(seconds(2f), analysis(db = QUIET_DB))
        accumulator.addFrames(seconds(1f), analysis(db = SNORE_DB, isSnoring = true))

        val report = accumulator.report()

        assertEquals(CriterionStatus.PASS, report.status(SetupCriterion.DURATION))
        assertEquals(SetupVerdict.DETECTED, report.verdict)
    }

    @Test
    fun gapLongerThanTheServiceLimitSplitsTheEvent() {
        val config = DetectionConfig(minDurationSeconds = 3f)
        val accumulator = SetupCheckAccumulator(config)
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        accumulator.addFrames(seconds(2f), analysis(db = SNORE_DB, isSnoring = true))
        accumulator.addFrames(seconds(3f), analysis(db = QUIET_DB))
        accumulator.addFrames(seconds(2f), analysis(db = SNORE_DB, isSnoring = true))

        assertEquals(CriterionStatus.FAIL, accumulator.report().status(SetupCriterion.DURATION))
    }

    @Test
    fun mediaPlaybackPausesDetectionLikeDuringMonitoring() {
        val accumulator = SetupCheckAccumulator(DetectionConfig(ignoreDuringMediaPlayback = true))
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        repeat(seconds(3f)) { accumulator.add(analysis(db = SNORE_DB, isSnoring = true), FRAME, mediaPlaying = true) }

        val report = accumulator.report()

        assertTrue(report.pausedForMedia)
        assertEquals(CriterionStatus.PASS, report.status(SetupCriterion.VOLUME))
        assertEquals(SetupVerdict.NOT_DETECTED, report.verdict)
    }

    @Test
    fun mediaPlaybackIsIgnoredWhenThatSettingIsOff() {
        val accumulator = SetupCheckAccumulator(DetectionConfig(ignoreDuringMediaPlayback = false))
        accumulator.addFrames(300, analysis(db = QUIET_DB))
        repeat(seconds(3f)) { accumulator.add(analysis(db = SNORE_DB, isSnoring = true), FRAME, mediaPlaying = true) }

        val report = accumulator.report()

        assertFalse(report.pausedForMedia)
        assertEquals(SetupVerdict.DETECTED, report.verdict)
    }

    @Test
    fun elapsedTimeComesFromTheSamplesRead() {
        val accumulator = SetupCheckAccumulator(DetectionConfig())

        accumulator.addFrames(469, analysis())

        assertEquals(30f, accumulator.elapsedSeconds, 0.05f)
    }

    @Test
    fun anEmptyCheckStillProducesAReport() {
        assertEquals(SetupVerdict.NO_SIGNAL, SetupCheckAccumulator(DetectionConfig()).report().verdict)
    }
}
