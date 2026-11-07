package com.aistudio.snoredetector.afkwd.data

import android.content.Context
import com.aistudio.snoredetector.afkwd.dsp.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A finished (or interrupted) full-session recording on disk.
 */
data class SessionRecording(
    val file: File,
    val startTimeMillis: Long,
    val sizeBytes: Long
) {
    val durationSeconds: Long
        get() = (sizeBytes - WavWriter.HEADER_SIZE).coerceAtLeast(0L) / SessionRecordingStore.BYTES_PER_SECOND

    /** Name offered when saving; the start time lets the audio be lined up with exported event timestamps. */
    val exportFileName: String
        get() {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startTimeMillis))
            return "SnoreDetector_Session_$date.wav"
        }
}

/**
 * Where full-session recordings live and how much space they take.
 *
 * They are kept in noBackupFilesDir rather than next to the snore clips: a single night is far
 * above Android Auto Backup's 25 MB per-app quota and would stop the rest of the app being backed up.
 */
object SessionRecordingStore {

    const val BYTES_PER_SECOND = 32_000L // 16 kHz, 16-bit, mono
    const val BYTES_PER_HOUR = BYTES_PER_SECOND * 3600

    /** Recording stops when free space falls below this, so clips and the database can still be written. */
    const val MIN_FREE_BYTES = 500_000_000L

    /** Recordings are about 1 GB each; a forgotten switch must not fill the device. */
    const val MAX_KEPT_RECORDINGS = 3

    private const val DIRECTORY_NAME = "session_recordings"
    private const val FILE_PREFIX = "session_"
    private const val FILE_SUFFIX = ".wav"

    fun directory(context: Context): File = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun newFile(directory: File, startTimeMillis: Long): File =
        File(directory, "$FILE_PREFIX$startTimeMillis$FILE_SUFFIX")

    /** Recordings in [directory], newest first. Files that are not recordings are ignored. */
    fun list(directory: File): List<SessionRecording> =
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .mapNotNull { file ->
                file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull()
                    ?.let { start -> SessionRecording(file, start, file.length()) }
            }
            .sortedByDescending { it.startTimeMillis }

    /** Deletes the oldest recordings until at most [keep] remain. */
    fun prune(directory: File, keep: Int) {
        list(directory).drop(keep).forEach { it.file.delete() }
    }

    fun deleteAll(directory: File) {
        list(directory).forEach { it.file.delete() }
    }

    fun formatSize(bytes: Long): String =
        if (bytes >= 1_000_000_000L) {
            String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        } else {
            "${bytes / 1_000_000L} MB"
        }

    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "$hours h $minutes min" else "$minutes min"
    }
}
