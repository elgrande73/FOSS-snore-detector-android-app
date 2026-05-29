package com.example.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Fast Fourier Transform (Cooley-Tukey Radix-2) in pure Kotlin.
 * Fully FOSS-compliant, offline-friendly, high-performance.
 */
object FFT {

    /**
     * Compute the FFT of the input complex arrays in-place.
     * The input sizes must be a power of 2.
     */
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n <= 1) return

        // Check if power of 2
        if ((n and (n - 1)) != 0) {
            throw IllegalArgumentException("Size must be a power of 2 ($n given)")
        }

        // Bit reversal permutation
        var i = 0
        for (j in 1 until n - 1) {
            var bit = n shr 1
            while (i and bit != 0) {
                i = i xor bit
                bit = bit shr 1
            }
            i = i xor bit
            if (j < i) {
                // Swap real
                val tempR = real[j]
                real[j] = real[i]
                real[i] = tempR
                // Swap imag
                val tempI = imag[j]
                imag[j] = imag[i]
                imag[i] = tempI
            }
        }

        // Cooley-Tukey decimation-in-time
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wlenR = cos(angle).toFloat()
            val wlenI = sin(angle).toFloat()
            
            val halfLen = len shr 1
            for (step in 0 until n step len) {
                var wR = 1.0f
                var wI = 0.0f
                for (j in 0 until halfLen) {
                    val uIdx = step + j
                    val vIdx = step + j + halfLen
                    
                    // Complex multiplication: t = w * A[vIdx]
                    val tR = wR * real[vIdx] - wI * imag[vIdx]
                    val tI = wR * imag[vIdx] + wI * real[vIdx]
                    
                    real[vIdx] = real[uIdx] - tR
                    imag[vIdx] = imag[uIdx] - tI
                    
                    real[uIdx] += tR
                    imag[uIdx] += tI
                    
                    // Update w
                    val nextWR = wR * wlenR - wI * wlenI
                    val nextWI = wR * wlenI + wI * wlenR
                    wR = nextWR
                    wI = nextWI
                }
            }
            len = len shl 1
        }
    }
}
