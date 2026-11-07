package com.aistudio.snoredetector.afkwd.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.aistudio.snoredetector.afkwd.BuildConfig
import com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Outcome of a full backup or restore. [errorMessage] is ready to show to the user. */
data class BackupResult(
    val success: Boolean,
    val eventCount: Int = 0,
    val recordingCount: Int = 0,
    val errorMessage: String? = null
)

/** Rejection with a user-facing reason. */
private class BackupException(message: String) : Exception(message)

/**
 * Full backup and restore of the snoring history, error logs, settings and, optionally, the
 * recordings, as a single ZIP file. Unlike [AudioExportManager], whose output is meant for reading
 * elsewhere, a file written here can be restored by this app.
 *
 * ZIP layout: `database.db`, `audio/<clip>.wav`, then `backup.json` (settings and metadata).
 * The manifest comes last so that a file cut short while saving has none and is refused.
 *
 * Restore validates everything and migrates a copy of the database first. Existing data is only
 * replaced by one Room transaction once all of that has passed, so a bad file changes nothing.
 * Restored clips stay in a new folder inside `snore_clips`, and that transaction switches the
 * database over to them, so an interrupted restore leaves unused files, not broken history.
 */
object BackupManager {

    private const val TAG = "BackupManager"

    const val PREFERENCES_NAME = "snore_detector_preferences"

    private const val FORMAT_VERSION = 1
    private const val APP_ID = "snore-detector"
    private const val MANIFEST_ENTRY = "backup.json"
    private const val DATABASE_ENTRY = "database.db"
    private const val AUDIO_PREFIX = "audio/"
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val CLIPS_DIR = "snore_clips"
    private const val SNAPSHOT_DB_NAME = "backup_snapshot.db"
    private const val RESTORE_DB_NAME = "backup_restore.db"
    private val SAFE_CLIP_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.wav")

    // Every setting that belongs in a backup must be listed here, because restoring only accepts
    // these keys with these types: a wrong type in SharedPreferences would crash the app at start.
    // The selected microphone is left out on purpose: its ID only means something on one device.
    private val BOOLEAN_SETTINGS = listOf(
        "useRms", "useZcr", "useBandEnergy", "useLowFreqRatio",
        "saveAudioClips", "notifyOnSnore", "ignoreDuringMediaPlayback", "dynamicColor"
    )
    private val FLOAT_SETTINGS = listOf(
        "rmsDbThreshold", "zcrThreshold", "bandEnergyThreshold",
        "lowFreqRatioThreshold", "minDurationSeconds"
    )
    private const val THEME_SETTING = "themeMode"

    /** Writes a backup to a user-chosen Document Uri (Storage Access Framework). */
    suspend fun exportBackupToUri(
        context: Context,
        destinationUri: Uri,
        includeRecordings: Boolean,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): BackupResult {
        return try {
            val output = context.contentResolver.openOutputStream(destinationUri, "wt")
                ?: return BackupResult(false, errorMessage = "Cannot open output destination")
            output.use {
                writeBackup(context, AppDatabase.getDatabase(context), preferences(context), it, includeRecordings, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            BackupResult(false, errorMessage = e.localizedMessage ?: "Unknown backup error")
        }
    }

    /** Restores a backup from a user-chosen Document Uri, replacing the current data if it is valid. */
    suspend fun restoreBackupFromUri(
        context: Context,
        sourceUri: Uri,
        onProgress: ((recordings: Int) -> Unit)? = null
    ): BackupResult {
        return try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return BackupResult(false, errorMessage = "Cannot open the selected file")
            input.use {
                restoreBackup(context, AppDatabase.getDatabase(context), preferences(context), it, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            BackupResult(false, errorMessage = "Could not read the selected file. Your data was not changed.")
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Writes the ZIP to [output]. Throws on I/O errors; the caller owns [output]. */
    suspend fun writeBackup(
        context: Context,
        database: AppDatabase,
        prefs: SharedPreferences,
        output: OutputStream,
        includeRecordings: Boolean,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): BackupResult {
        val events = database.snoreDao().getAllEvents().first()
        val logs = database.errorLogDao().getAllLogs().first()
        var recordingCount = 0

        try {
            writeSnapshotDatabase(context, events, logs)

            val zipOut = ZipOutputStream(output)
            zipOut.putNextEntry(ZipEntry(DATABASE_ENTRY))
            FileInputStream(context.getDatabasePath(SNAPSHOT_DB_NAME)).use { it.copyTo(zipOut) }
            zipOut.closeEntry()

            if (includeRecordings) {
                val usedNames = mutableSetOf<String>()
                events.forEachIndexed { index, event ->
                    onProgress?.invoke(index + 1, events.size)
                    val clip = event.audioFilePath?.let { File(it) }
                    if (clip != null && clip.isFile && clip.length() > 0L && usedNames.add(clip.name)) {
                        zipOut.putNextEntry(ZipEntry(AUDIO_PREFIX + clip.name))
                        FileInputStream(clip).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        recordingCount++
                    }
                }
            }

            val manifest = JSONObject()
                .put("app", APP_ID)
                .put("formatVersion", FORMAT_VERSION)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("createdAt", System.currentTimeMillis())
                .put("eventCount", events.size)
                .put("recordingCount", recordingCount)
                .put("settings", settingsToJson(prefs))
            zipOut.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zipOut.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
            zipOut.finish()
            zipOut.flush()
        } finally {
            context.deleteDatabase(SNAPSHOT_DB_NAME)
        }
        return BackupResult(true, events.size, recordingCount)
    }

    /**
     * Copies the rows into a separate database rather than copying the live file, which in WAL mode
     * may be missing recent writes and may change while being read during a running detection.
     */
    private suspend fun writeSnapshotDatabase(context: Context, events: List<SnoreEvent>, logs: List<ErrorLog>) {
        context.deleteDatabase(SNAPSHOT_DB_NAME)
        val snapshot = stagingDatabase(context, SNAPSHOT_DB_NAME)
        try {
            snapshot.openHelper.writableDatabase // creates the file even when there are no rows
            snapshot.withTransaction {
                events.forEach { snapshot.snoreDao().insertEvent(it) }
                logs.forEach { snapshot.errorLogDao().insertLog(it) }
            }
        } finally {
            snapshot.close()
        }
    }

    private fun stagingDatabase(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    /**
     * Validates the backup in [input] and, only if all of it is good, replaces the current history,
     * error logs, recordings and settings with it. Returns a failed result for an invalid file.
     */
    suspend fun restoreBackup(
        context: Context,
        database: AppDatabase,
        prefs: SharedPreferences,
        input: InputStream,
        onProgress: ((recordings: Int) -> Unit)? = null
    ): BackupResult {
        val clipsDir = File(context.filesDir, CLIPS_DIR)
        // Listed now so that only these are removed later, never a clip that appears meanwhile.
        val previousClips = clipsDir.listFiles()?.toList().orEmpty()
        val stagingDir = File(clipsDir, "restore_${System.currentTimeMillis()}")
        val stagedDb = context.getDatabasePath(RESTORE_DB_NAME)
        var committed = false

        try {
            context.deleteDatabase(RESTORE_DB_NAME)
            stagedDb.parentFile?.mkdirs()

            val manifest = parseManifest(extractBackup(input, stagedDb, stagingDir, onProgress))
            val settings = parseSettings(manifest)
            val recordingCount = stagingDir.list()?.size ?: 0
            if (manifest.getInt("recordingCount") != recordingCount) {
                throw BackupException("This backup is incomplete: recordings are missing.")
            }

            checkDatabaseFile(stagedDb, database.openHelper.readableDatabase.version)
            val staged = stagingDatabase(context, RESTORE_DB_NAME)
            val (events, logs) = try {
                staged.snoreDao().getAllEvents().first() to staged.errorLogDao().getAllLogs().first()
            } finally {
                staged.close()
            }
            if (manifest.getInt("eventCount") != events.size) {
                throw BackupException("This backup is incomplete: history entries are missing.")
            }

            // Paths in the file come from another install, or from anywhere if it was tampered with,
            // so the only ones kept are those of clips that were just extracted.
            val restoredEvents = events.map { event ->
                val clip = event.audioFilePath?.let { File(stagingDir, File(it).name) }
                event.copy(audioFilePath = clip?.takeIf { it.isFile }?.absolutePath)
            }

            database.withTransaction {
                database.snoreDao().deleteAllEvents()
                database.errorLogDao().deleteAllLogs()
                restoredEvents.forEach { database.snoreDao().insertEvent(it) }
                logs.forEach { database.errorLogDao().insertLog(it) }
            }
            committed = true

            applySettings(prefs, settings)
            previousClips.forEach { it.deleteRecursively() }
            return BackupResult(true, events.size, recordingCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            Log.w(TAG, "Backup rejected: ${e.message}")
            return BackupResult(false, errorMessage = "${e.message} Your data was not changed.")
        } catch (e: Exception) {
            // Damaged ZIPs, non-SQLite files and foreign schemas all end up here.
            Log.w(TAG, "Backup could not be restored", e)
            return BackupResult(
                false,
                errorMessage = "This file is damaged or is not a Snore Detector backup. Your data was not changed."
            )
        } finally {
            context.deleteDatabase(RESTORE_DB_NAME)
            if (!committed) stagingDir.deleteRecursively()
        }
    }

    /** Unpacks the database to [databaseFile] and clips to [clipDir]; returns the manifest text. */
    private fun extractBackup(
        input: InputStream,
        databaseFile: File,
        clipDir: File,
        onProgress: ((recordings: Int) -> Unit)?
    ): String {
        var manifest: String? = null
        var hasDatabase = false
        val seen = mutableSetOf<String>()
        var clips = 0

        // A ZipInputStream reports any non-ZIP input as an empty archive, which fails the check below.
        val zipIn = ZipInputStream(input)
        while (true) {
            val name = zipIn.nextEntry?.name ?: break
            if (!seen.add(name)) throw BackupException("This backup is damaged.")
            when {
                name == MANIFEST_ENTRY -> manifest = readLimited(zipIn)
                name == DATABASE_ENTRY -> {
                    FileOutputStream(databaseFile).use { zipIn.copyTo(it) }
                    hasDatabase = true
                }
                name.startsWith(AUDIO_PREFIX) -> {
                    val clipName = name.removePrefix(AUDIO_PREFIX)
                    // Also what stops "audio/../../x" from escaping the staging folder.
                    if (!SAFE_CLIP_NAME.matches(clipName)) throw BackupException("This backup is damaged.")
                    clipDir.mkdirs()
                    FileOutputStream(File(clipDir, clipName)).use { zipIn.copyTo(it) }
                    onProgress?.invoke(++clips)
                }
            }
            zipIn.closeEntry()
        }
        if (!hasDatabase) throw BackupException("This file is damaged or is not a Snore Detector backup.")
        return manifest ?: throw BackupException("This file is damaged or is not a Snore Detector backup.")
    }

    private fun readLimited(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes.write(buffer, 0, read)
            if (bytes.size() > MAX_MANIFEST_BYTES) throw BackupException("This backup is damaged.")
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun parseManifest(text: String): JSONObject {
        val manifest = JSONObject(text)
        if (manifest.optString("app") != APP_ID) {
            throw BackupException("This file is not a Snore Detector backup.")
        }
        if (manifest.getInt("formatVersion") > FORMAT_VERSION) {
            throw BackupException("This backup was made by a newer version of the app. Update the app first.")
        }
        return manifest
    }

    private fun checkDatabaseFile(file: File, currentVersion: Int) {
        val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        SQLiteDatabase.openDatabase(file.path, null, flags).use { db ->
            if (db.version > currentVersion) {
                throw BackupException("This backup was made by a newer version of the app. Update the app first.")
            }
            if (db.version < 1) throw BackupException("This file is not a Snore Detector backup.")
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
                    throw BackupException("This backup is damaged.")
                }
            }
        }
    }

    private fun settingsToJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()
        val saved = prefs.all
        BOOLEAN_SETTINGS.forEach { key -> (saved[key] as? Boolean)?.let { json.put(key, it) } }
        // Through the decimal text, as toDouble() would turn 0.15f into 0.15000000596...
        FLOAT_SETTINGS.forEach { key -> (saved[key] as? Float)?.let { json.put(key, it.toString().toDouble()) } }
        (saved[THEME_SETTING] as? String)?.let { json.put(THEME_SETTING, it) }
        return json
    }

    /** Type-checks every known setting, so that a bad value is found before anything is replaced. */
    private fun parseSettings(manifest: JSONObject): Map<String, Any> {
        val json = manifest.getJSONObject("settings")
        val settings = mutableMapOf<String, Any>()
        val invalid = BackupException("This backup contains invalid settings.")

        BOOLEAN_SETTINGS.filter(json::has).forEach { key ->
            settings[key] = json.get(key) as? Boolean ?: throw invalid
        }
        FLOAT_SETTINGS.filter(json::has).forEach { key ->
            val value = (json.get(key) as? Number)?.toFloat()
            settings[key] = value?.takeIf { it.isFinite() } ?: throw invalid
        }
        if (json.has(THEME_SETTING)) {
            val value = json.get(THEME_SETTING) as? String
            settings[THEME_SETTING] = ThemeMode.entries.firstOrNull { it.name == value }?.name ?: throw invalid
        }
        return settings
    }

    /** Makes the settings match the backup: one that isn't in it returns to its default. */
    @SuppressLint("ApplySharedPref") // already off the main thread; must be on disk before reporting success
    private fun applySettings(prefs: SharedPreferences, settings: Map<String, Any>) {
        val editor = prefs.edit()
        (BOOLEAN_SETTINGS + FLOAT_SETTINGS + THEME_SETTING).forEach { editor.remove(it) }
        settings.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
    }
}
