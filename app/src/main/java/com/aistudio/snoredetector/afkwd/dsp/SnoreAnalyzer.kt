package com.aistudio.snoredetector.afkwd.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Result of the DSP analysis on a single block of audio samples.
 */
data class AnalysisResult(
    val rms: Float,
    val db: Float,
    val zcr: Float,
    val bandEnergy: Float,
    val lowFreqEnergyRatio: Float,
    val rmsThresholdMet: Boolean,
    val zcrThresholdMet: Boolean,
    val bandThresholdMet: Boolean,
    val lowFreqThresholdMet: Boolean,
    val isSnoring: Boolean
)

/**
 * Configuration of parameters and activation states for the four snore-detection methods.
 */
data class DetectionConfig(
    // Method Activations
    val useRms: Boolean = true,
    val useZcr: Boolean = true,
    val useBandEnergy: Boolean = true,
    val useLowFreqRatio: Boolean = true,

    // Thresholds
    val rmsDbThreshold: Float = 55.0f,          // In relative dB (positive scale ~20 - 120 dB)
    val zcrThreshold: Float = 0.15f,            // Max ZCR for low-pitched snoring rumble (0 to 1)
    val bandEnergyThreshold: Float = 0.015f,     // Min average frequency magnitude in 100-1000Hz
    val lowFreqRatioThreshold: Float = 0.65f    // Min ratio of energy below 500Hz to total (0 to 1)
)

/**
 * Class to perform real-time digital signal processing on 16-bit PCM mic buffers.
 */
class SnoreAnalyzer {

    // Center frequency bands for 16kHz sampling rate:
    // With N = 1024, each bin corresponds to: 16000 / 1024 = 15.625 Hz
    // Bins for 100Hz to 1000Hz: indices 6 to 64
    // Bins for Low Frequency <= 500Hz: indices 0 to 32
    private val sampleRate = 16000f
    private val fftSize = 1024

    /**
     * Analyze a single audio buffer of 1024 samples.
     * @param shortSamples Raw short audio samples (PCM 16-bit).
     * @param config The current detection threshold and activation config.
     */
    fun analyze(shortSamples: ShortArray, config: DetectionConfig): AnalysisResult {
        val n = shortSamples.size
        
        // 1. Convert to normalized floats (-1.0 to 1.0) for standard DSP scaling
        val floatSamples = FloatArray(n)
        for (i in 0 until n) {
            floatSamples[i] = shortSamples[i].toFloat() / 32768.0f
        }

        // 2. RMS & dB SPL calculation
        var sumSquares = 0.0f
        for (i in 0 until n) {
            sumSquares += floatSamples[i] * floatSamples[i]
        }
        val rms = sqrt(sumSquares / n.toFloat())
        // Map RMS [0.0, 1.0] to a relative positive decibel scale [0.0, 120.0]
        val db = (20.0 * log10(rms.coerceAtLeast(1e-5f).toDouble()) + 120.0).toFloat()

        // 3. Zero Crossing Rate (ZCR)
        var crossings = 0
        for (i in 1 until n) {
            // Check sign change
            if ((floatSamples[i] >= 0 && floatSamples[i - 1] < 0) ||
                (floatSamples[i] < 0 && floatSamples[i - 1] >= 0)) {
                crossings++
            }
        }
        val zcr = crossings.toFloat() / n.toFloat()

        // 4. Compute FFT for Band Energy and Low Frequency Ratio
        val real = floatSamples.clone()
        val imag = FloatArray(n) { 0.0f }
        
        FFT.fft(real, imag)

        // Compute magnitude of each frequency bin
        val numBins = n / 2
        val magnitude = FloatArray(numBins)
        var totalMagSum = 0.0f
        for (i in 0 until numBins) {
            magnitude[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
            totalMagSum += magnitude[i]
        }

        // Calculate Average Band Energy (between 100Hz and 1000Hz)
        // Bin 100Hz = 100 / 15.625 ≈ 6
        // Bin 1000Hz = 1000 / 15.625 ≈ 64
        val bandStartBin = 6
        val bandEndBin = 64
        var bandMagSum = 0.0f
        var bandBinsCount = 0
        for (i in bandStartBin..bandEndBin) {
            if (i < numBins) {
                bandMagSum += magnitude[i]
                bandBinsCount++
            }
        }
        val bandEnergy = if (bandBinsCount > 0) bandMagSum / bandBinsCount else 0.0f

        // Calculate Low Frequency Energy Ratio (energy <= 500Hz / total energy)
        // Bin 500Hz = 500 / 15.625 = 32
        val lowFreqEndBin = 32
        var lowFreqMagSum = 0.0f
        for (i in 0..lowFreqEndBin) {
            if (i < numBins) {
                lowFreqMagSum += magnitude[i]
            }
        }
        // Avoid division by zero
        val lowFreqEnergyRatio = if (totalMagSum > 0.01f) lowFreqMagSum / totalMagSum else 0.0f

        // Evaluate conditions
        val rmsMet = db >= config.rmsDbThreshold
        // Snore is low-pitched rumble, so ZCR must be lower than threshold
        val zcrMet = zcr <= config.zcrThreshold
        val bandMet = bandEnergy >= config.bandEnergyThreshold
        val lowFreqMet = lowFreqEnergyRatio >= config.lowFreqRatioThreshold

        // Determine if snoring is detected based on active methods.
        // A block is snoring if ALL activated methods meet their threshold.
        // If no methods are activated, then snoring is false.
        var isSnoring = false
        var activeMethodsCount = 0
        var matchingActiveMethodsCount = 0

        if (config.useRms) {
            activeMethodsCount++
            if (rmsMet) matchingActiveMethodsCount++
        }
        if (config.useZcr) {
            activeMethodsCount++
            if (zcrMet) matchingActiveMethodsCount++
        }
        if (config.useBandEnergy) {
            activeMethodsCount++
            if (bandMet) matchingActiveMethodsCount++
        }
        if (config.useLowFreqRatio) {
            activeMethodsCount++
            if (lowFreqMet) matchingActiveMethodsCount++
        }

        if (activeMethodsCount > 0) {
            isSnoring = (matchingActiveMethodsCount == activeMethodsCount)
        }

        return AnalysisResult(
            rms = rms,
            db = db,
            zcr = zcr,
            bandEnergy = bandEnergy,
            lowFreqEnergyRatio = lowFreqEnergyRatio,
            rmsThresholdMet = rmsMet,
            zcrThresholdMet = zcrMet,
            bandThresholdMet = bandMet,
            lowFreqThresholdMet = lowFreqMet,
            isSnoring = isSnoring
        )
    }
}
