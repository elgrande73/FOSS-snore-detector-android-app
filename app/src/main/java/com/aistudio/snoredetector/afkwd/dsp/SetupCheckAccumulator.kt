package com.aistudio.snoredetector.afkwd.dsp

/** The conditions a sound has to meet to be logged, in the order Settings lists them. */
enum class SetupCriterion { VOLUME, ZCR, BAND_ENERGY, LOW_FREQ_RATIO, DURATION }

enum class CriterionStatus {
    PASS,
    FAIL,

    /** No test sound was heard, so there was nothing to measure. */
    NOT_TESTED,

    /** The method is switched off in Settings and doesn't affect detection. */
    DISABLED
}

data class CriterionResult(
    val criterion: SetupCriterion,
    val status: CriterionStatus,
    val measured: Float?,
    val threshold: Float
)

enum class SetupVerdict {
    /** Every sample was zero: the input is muted, blocked or not delivering audio. */
    NO_SIGNAL,

    /** Nothing rose above the background, and the background itself wouldn't be logged. */
    NO_TEST_SOUND,

    /** Nothing rose above the background, yet the background alone would be logged. */
    BACKGROUND_DETECTED,

    DETECTED,
    NOT_DETECTED
}

data class SetupCheckReport(
    val noiseFloorDb: Float,
    val noiseFloorReachesVolumeThreshold: Boolean,
    val rmsDbThreshold: Float,
    val criteria: List<CriterionResult>,
    val verdict: SetupVerdict,
    /** Media was playing with "Pause Triggers During Media Playback" on, so detection was paused. */
    val pausedForMedia: Boolean
)

/**
 * Turns the [SnoreAnalyzer] results of one setup check into a [SetupCheckReport].
 *
 * Holds no Android state, so a check can be replayed in a plain unit test. It keeps one entry per
 * audio frame, which the 30 s limit of the setup check bounds to about 470.
 *
 * The noise floor is a low percentile of all frames, so it holds up however long the test sound
 * lasts and whether or not the user stayed quiet first. The test sound is every frame clearly
 * above that floor, and each criterion is judged on the median of those frames, so one stray
 * frame can't decide it.
 *
 * The duration row and the verdict come from [AnalysisResult.isSnoring] run through the same rules
 * as the event state machine in SnoreDetectionService: gaps under [EVENT_GAP_SECONDS] don't end an
 * event, and media playback resets it.
 */
class SetupCheckAccumulator(
    private val config: DetectionConfig,
    private val sampleRate: Int = 16000
) {
    private val frames = ArrayList<AnalysisResult>()
    private var samplesSeen = 0L
    private var eventStart: Float? = null
    private var lastSnoreAt = 0f
    private var longestEventSeconds = 0f
    private var pausedForMedia = false

    val elapsedSeconds: Float
        get() = samplesSeen.toFloat() / sampleRate

    fun add(result: AnalysisResult, sampleCount: Int, mediaPlaying: Boolean = false) {
        val now = elapsedSeconds
        samplesSeen += sampleCount
        frames.add(result)

        val pausedNow = mediaPlaying && config.ignoreDuringMediaPlayback
        pausedForMedia = pausedForMedia || pausedNow

        if (pausedNow) {
            eventStart = null
        } else if (result.isSnoring) {
            val start = eventStart ?: now.also { eventStart = it }
            lastSnoreAt = now
            longestEventSeconds = maxOf(longestEventSeconds, now - start)
        } else if (now - lastSnoreAt >= EVENT_GAP_SECONDS) {
            eventStart = null
        }
    }

    fun report(): SetupCheckReport {
        val noiseFloor = frames.map { it.db }.sorted().let { dbs ->
            if (dbs.isEmpty()) 0f else dbs[(dbs.size * NOISE_FLOOR_PERCENTILE).toInt()]
        }
        val hasSignal = frames.any { it.rms > 0f }
        val soundFrames = frames.filter { it.db >= noiseFloor + TEST_SOUND_MARGIN_DB }
        val heardTestSound = hasSignal && soundFrames.size >= MIN_TEST_SOUND_FRAMES
        val wouldLog = longestEventSeconds >= config.minDurationSeconds

        fun judge(
            criterion: SetupCriterion,
            enabled: Boolean,
            threshold: Float,
            value: (AnalysisResult) -> Float,
            passes: (Float) -> Boolean
        ): CriterionResult {
            if (!enabled) return CriterionResult(criterion, CriterionStatus.DISABLED, null, threshold)
            if (!heardTestSound) return CriterionResult(criterion, CriterionStatus.NOT_TESTED, null, threshold)
            val median = soundFrames.map(value).sorted().let { it[it.size / 2] }
            val status = if (passes(median)) CriterionStatus.PASS else CriterionStatus.FAIL
            return CriterionResult(criterion, status, median, threshold)
        }

        val criteria = listOf(
            judge(SetupCriterion.VOLUME, true, config.rmsDbThreshold, { it.db }) { it >= config.rmsDbThreshold },
            judge(SetupCriterion.ZCR, config.useZcr, config.zcrThreshold, { it.zcr }) { it <= config.zcrThreshold },
            judge(SetupCriterion.BAND_ENERGY, config.useBandEnergy, config.bandEnergyThreshold, { it.bandEnergy }) {
                it >= config.bandEnergyThreshold
            },
            judge(SetupCriterion.LOW_FREQ_RATIO, config.useLowFreqRatio, config.lowFreqRatioThreshold, { it.lowFreqEnergyRatio }) {
                it >= config.lowFreqRatioThreshold
            },
            CriterionResult(
                criterion = SetupCriterion.DURATION,
                status = when {
                    !heardTestSound -> CriterionStatus.NOT_TESTED
                    wouldLog -> CriterionStatus.PASS
                    else -> CriterionStatus.FAIL
                },
                measured = if (heardTestSound) longestEventSeconds else null,
                threshold = config.minDurationSeconds
            )
        )

        return SetupCheckReport(
            noiseFloorDb = noiseFloor,
            noiseFloorReachesVolumeThreshold = hasSignal && noiseFloor >= config.rmsDbThreshold,
            rmsDbThreshold = config.rmsDbThreshold,
            criteria = criteria,
            verdict = when {
                !hasSignal -> SetupVerdict.NO_SIGNAL
                !heardTestSound && wouldLog -> SetupVerdict.BACKGROUND_DETECTED
                !heardTestSound -> SetupVerdict.NO_TEST_SOUND
                wouldLog -> SetupVerdict.DETECTED
                else -> SetupVerdict.NOT_DETECTED
            },
            pausedForMedia = pausedForMedia
        )
    }

    companion object {
        /** Low enough to sit in the quiet part of a check that is a third quiet, as the UI asks. */
        const val NOISE_FLOOR_PERCENTILE = 0.25f

        /** 6 dB is twice the background amplitude: a clear rise, but a distant snore can reach it. */
        const val TEST_SOUND_MARGIN_DB = 6f

        /** About a quarter of a second at 1024 samples per frame. */
        const val MIN_TEST_SOUND_FRAMES = 4

        /** Same as the 2500 ms of silence that closes an event in SnoreDetectionService. */
        const val EVENT_GAP_SECONDS = 2.5f
    }
}
