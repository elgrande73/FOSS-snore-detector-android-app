package com.aistudio.snoredetector.afkwd.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Result data class for bulk export operations.
 */
data class ExportSummary(
    val success: Boolean,
    val exportedAudioCount: Int,
    val missingAudioCount: Int,
    val totalEventsCount: Int,
    val errorMessage: String? = null
)

/**
 * Clean FOSS Audio and CSV Export Manager.
 * Operates 100% offline using standard Android platform APIs and Java standard library.
 */
object AudioExportManager {

    private const val TAG = "AudioExportManager"

    /**
     * Generates a deterministic, filesystem-safe filename for an audio event.
     * Example: SnoreDetector_2026-08-18_23-41-12.wav
     */
    fun formatAudioFileName(timestamp: Long, eventId: Int? = null, suffixIndex: Int = 0): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val formattedDate = sdf.format(Date(timestamp))
        return if (suffixIndex > 0) {
            "SnoreDetector_${formattedDate}_${suffixIndex}.wav"
        } else if (eventId != null) {
            "SnoreDetector_${formattedDate}.wav"
        } else {
            "SnoreDetector_${formattedDate}.wav"
        }
    }

    /**
     * Generates standard CSV text for a list of snore events.
     * References the deterministic audio filename in the AudioClip column.
     */
    fun generateCsvContent(events: List<SnoreEvent>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val csvBuilder = StringBuilder()
        csvBuilder.append("Timestamp,Datetime,Duration_Seconds,dB_Level,Max_RMS,Mean_ZCR,Mean_BandEnergy,Mean_LowFreqRatio,AudioClip\n")

        val usedFileNames = mutableMapOf<String, Int>()

        for (e in events) {
            val formattedDate = sdf.format(Date(e.timestamp))
            val clipName = if (e.audioFilePath != null && File(e.audioFilePath).exists()) {
                val base = formatAudioFileName(e.timestamp, e.id)
                val count = usedFileNames.getOrDefault(base, 0)
                usedFileNames[base] = count + 1
                if (count == 0) base else formatAudioFileName(e.timestamp, e.id, count)
            } else {
                "None"
            }
            csvBuilder.append("${e.timestamp},\"$formattedDate\",${e.durationSeconds},${e.maxDb},${e.maxRms},${e.meanZcr},${e.meanBandEnergy},${e.meanLowFreqRatio},\"$clipName\"\n")
        }
        return csvBuilder.toString()
    }

    /**
     * Creates a standard Android ACTION_SEND intent to share an individual audio clip via FileProvider.
     */
    fun createShareAudioIntent(context: Context, event: SnoreEvent): Intent? {
        val path = event.audioFilePath ?: return null
        val sourceFile = File(path)
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return null
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, sourceFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Snore Recording - ${formatAudioFileName(event.timestamp, event.id)}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Recorded with Snore Detector on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))} (${String.format(Locale.US, "%.1f", event.durationSeconds)}s, peak ${event.maxDb.toInt()} dB)."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, "Share Snore Audio Recording")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share audio intent", e)
            null
        }
    }

    /**
     * Copies a single audio recording directly to a user-chosen Document Uri (Storage Access Framework).
     */
    fun exportSingleAudioToUri(context: Context, sourcePath: String, destinationUri: Uri): Boolean {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || sourceFile.length() == 0L) return false

        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
                outStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting single audio to URI", e)
            false
        }
    }

    /**
     * Exports CSV data directly to a user-chosen Document Uri.
     */
    fun exportCsvToUri(context: Context, events: List<SnoreEvent>, destinationUri: Uri): Boolean {
        return try {
            val csvText = generateCsvContent(events)
            context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                outStream.write(csvText.toByteArray(Charsets.UTF_8))
                outStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting CSV to URI", e)
            false
        }
    }

    /**
     * Exports audio recordings into a ZIP package written to a Document Uri.
     * Optionally includes the CSV index file if [includeCsv] is true.
     */
    fun exportZipArchiveToUri(
        context: Context,
        events: List<SnoreEvent>,
        destinationUri: Uri,
        includeCsv: Boolean = true,
        includeAudio: Boolean = true,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): ExportSummary {
        var exportedAudio = 0
        var missingAudio = 0
        val total = events.size

        try {
            val outputStream: OutputStream = context.contentResolver.openOutputStream(destinationUri)
                ?: return ExportSummary(false, 0, 0, total, "Cannot open output destination")

            ZipOutputStream(outputStream).use { zipOut ->
                // 1. Optionally write CSV index file at the root of the ZIP
                if (includeCsv) {
                    val csvContent = generateCsvContent(events)
                    val csvEntry = ZipEntry("snoring_events.csv")
                    zipOut.putNextEntry(csvEntry)
                    zipOut.write(csvContent.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }

                // 2. Optionally write audio files in audio/ subfolder
                if (includeAudio) {
                    val usedFileNames = mutableMapOf<String, Int>()

                    events.forEachIndexed { index, event ->
                        onProgress?.invoke(index + 1, total)

                        val path = event.audioFilePath
                        if (path != null) {
                            val audioFile = File(path)
                            if (audioFile.exists() && audioFile.length() > 0L) {
                                val base = formatAudioFileName(event.timestamp, event.id)
                                val count = usedFileNames.getOrDefault(base, 0)
                                usedFileNames[base] = count + 1
                                val fileName = if (count == 0) base else formatAudioFileName(event.timestamp, event.id, count)

                                val entryPath = if (includeCsv) "audio/$fileName" else fileName
                                val zipEntry = ZipEntry(entryPath)
                                zipOut.putNextEntry(zipEntry)

                                FileInputStream(audioFile).use { inStream ->
                                    inStream.copyTo(zipOut)
                                }
                                zipOut.closeEntry()
                                exportedAudio++
                            } else {
                                missingAudio++
                            }
                        } else {
                            missingAudio++
                        }
                    }
                }
                zipOut.finish()
            }

            return ExportSummary(
                success = true,
                exportedAudioCount = exportedAudio,
                missingAudioCount = missingAudio,
                totalEventsCount = total
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error writing ZIP export archive", e)
            return ExportSummary(
                success = false,
                exportedAudioCount = exportedAudio,
                missingAudioCount = missingAudio,
                totalEventsCount = total,
                errorMessage = e.localizedMessage ?: "Unknown export error"
            )
        }
    }
}
