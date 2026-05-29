package com.example.dsp

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Clean FOSS wave exporter in pure Kotlin for saving snoring audio snippets.
 */
object WavWriter {

    /**
     * Save raw short PCM buffers into a standard WAV file.
     */
    fun saveWavFile(file: File, sampleRate: Int, sampleBuffers: List<ShortArray>) {
        var totalShorts = 0
        for (buf in sampleBuffers) {
            totalShorts += buf.size
        }
        val totalAudioLen = totalShorts * 2 // 16-bit = 2 bytes per sample

        FileOutputStream(file).use { fos ->
            writeHeader(fos, 1, sampleRate, 16, totalAudioLen)
            
            // Allocate a reusable little-endian byte array of size 2048 to write faster
            val outBuffer = ByteArray(2048)
            var bufferIdx = 0

            for (buffer in sampleBuffers) {
                for (value in buffer) {
                    if (bufferIdx >= outBuffer.size - 1) {
                        fos.write(outBuffer, 0, bufferIdx)
                        bufferIdx = 0
                    }
                    // Extract little-endian bytes
                    outBuffer[bufferIdx++] = (value.toInt() and 0xFF).toByte()
                    outBuffer[bufferIdx++] = ((value.toInt() shr 8) and 0xFF).toByte()
                }
            }
            if (bufferIdx > 0) {
                fos.write(outBuffer, 0, bufferIdx)
            }
        }
    }

    /**
     * Write standard RIFF/WAVE header
     */
    private fun writeHeader(
        out: OutputStream,
        channels: Short,
        sampleRate: Int,
        bitsPerSample: Short,
        totalAudioLen: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()

        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte() // fmt
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16 // Header chunk size
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1 // Format type: 1 = PCM
        header[21] = 0

        header[22] = channels.toByte()
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()

        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0

        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0

        header[36] = 'd'.code.toByte() // data
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header, 0, 44)
    }
}
