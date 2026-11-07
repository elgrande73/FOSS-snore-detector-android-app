package com.aistudio.snoredetector.afkwd

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.BackupManager
import com.aistudio.snoredetector.afkwd.data.BackupResult
import com.aistudio.snoredetector.afkwd.data.ErrorLog
import com.aistudio.snoredetector.afkwd.data.SnoreEvent
import com.aistudio.snoredetector.afkwd.ui.theme.ThemeMode
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exercises backup and restore end to end on real ZIP files and real SQLite databases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var db: AppDatabase
    private lateinit var clipsDir: File

    private val clipBytes = byteArrayOf(1, 2, 3, 4, 5)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(BackupManager.PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clipsDir = File(context.filesDir, "snore_clips").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        clipsDir.deleteRecursively()
    }

    private fun event(id: Int, path: String?) = SnoreEvent(
        id = id,
        timestamp = 1771372800000L + id,
        durationSeconds = 2.5,
        maxDb = 61.0f,
        maxRms = 0.04f,
        meanZcr = 0.08f,
        meanBandEnergy = 0.03f,
        meanLowFreqRatio = 0.78f,
        audioFilePath = path
    )

    /** One event with a clip, one without, one error log and some non-default settings. */
    private fun seedOriginalData(): File = runBlocking {
        val clip = File(clipsDir, "snore_1.wav").apply { writeBytes(clipBytes) }
        db.snoreDao().insertEvent(event(1, clip.absolutePath))
        db.snoreDao().insertEvent(event(2, null))
        db.errorLogDao().insertLog(ErrorLog(id = 7, errorType = "TEST", message = "kept", diagnosticDetails = "d"))
        prefs.edit()
            .putFloat("rmsDbThreshold", 62.5f)
            .putFloat("zcrThreshold", 0.15f)
            .putBoolean("useZcr", false)
            .putString("themeMode", "DARK")
            .putInt("selectedAudioInputId", 42)
            .putString("selectedAudioInputName", "Headset")
            .commit()
        clip
    }

    private fun backupBytes(includeRecordings: Boolean = true): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        val result = BackupManager.writeBackup(context, db, prefs, out, includeRecordings)
        assertTrue(result.success)
        out.toByteArray()
    }

    private fun restore(bytes: ByteArray): BackupResult = runBlocking {
        BackupManager.restoreBackup(context, db, prefs, ByteArrayInputStream(bytes))
    }

    /** Replaces what [seedOriginalData] wrote, as if the user carried on using the app. */
    private fun replaceWithCurrentData(): File = runBlocking {
        db.snoreDao().deleteAllEvents()
        db.errorLogDao().deleteAllLogs()
        clipsDir.listFiles()?.forEach { it.deleteRecursively() }
        val clip = File(clipsDir, "snore_99.wav").apply { writeBytes(byteArrayOf(9, 9)) }
        db.snoreDao().insertEvent(event(99, clip.absolutePath))
        db.errorLogDao().insertLog(ErrorLog(id = 8, errorType = "CURRENT", message = "now", diagnosticDetails = "d"))
        prefs.edit().clear().putFloat("rmsDbThreshold", 70f).putString("themeMode", "LIGHT").commit()
        clip
    }

    private fun assertCurrentDataUntouched(result: BackupResult, currentClip: File) = runBlocking {
        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("not changed"))
        val events = db.snoreDao().getAllEvents().first()
        assertEquals(listOf(99), events.map { it.id })
        assertEquals(currentClip.absolutePath, events.single().audioFilePath)
        assertArrayEquals(byteArrayOf(9, 9), currentClip.readBytes())
        assertEquals("CURRENT", db.errorLogDao().getAllLogs().first().single().errorType)
        assertEquals(70f, prefs.getFloat("rmsDbThreshold", 0f), 0f)
        assertEquals("LIGHT", prefs.getString("themeMode", null))
        assertEquals("Staged clips must be removed", listOf(currentClip.name), clipsDir.list()!!.toList())
        assertFalse(context.getDatabasePath("backup_restore.db").exists())
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entriesOf(zip: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }
        return entries
    }

    /** Rewrites one entry of a valid backup, keeping everything else as exported. */
    private fun withEntry(backup: ByteArray, name: String, content: ByteArray?): ByteArray {
        val entries = entriesOf(backup).toMutableMap()
        if (content == null) entries.remove(name) else entries[name] = content
        return zipOf(*entries.toList().toTypedArray())
    }

    private fun withManifest(backup: ByteArray, change: (JSONObject) -> Unit): ByteArray {
        val manifest = JSONObject(String(entriesOf(backup).getValue("backup.json")))
        change(manifest)
        return withEntry(backup, "backup.json", manifest.toString().toByteArray())
    }

    @Test
    fun backupAndRestore_bringsBackHistoryLogsRecordingsAndSettings() = runBlocking {
        seedOriginalData()
        val backup = backupBytes()
        replaceWithCurrentData()

        val result = restore(backup)

        assertTrue(result.errorMessage, result.success)
        assertEquals(2, result.eventCount)
        assertEquals(1, result.recordingCount)

        val events = db.snoreDao().getAllEvents().first().sortedBy { it.id }
        assertEquals(listOf(1, 2), events.map { it.id })
        assertEquals(1771372800001L, events[0].timestamp)
        assertEquals(2.5, events[0].durationSeconds, 0.0001)
        assertNull(events[1].audioFilePath)
        val clip = File(events[0].audioFilePath!!)
        assertArrayEquals(clipBytes, clip.readBytes())
        assertTrue(
            "Clips must stay under snore_clips for sharing and Clear History to work",
            clip.canonicalPath.startsWith(clipsDir.canonicalPath + File.separator)
        )
        assertEquals("kept", db.errorLogDao().getAllLogs().first().single().message)

        assertEquals(62.5f, prefs.getFloat("rmsDbThreshold", 0f), 0f)
        assertEquals(0.15f, prefs.getFloat("zcrThreshold", 0f), 0f)
        assertFalse(prefs.getBoolean("useZcr", true))
        assertEquals("DARK", prefs.getString("themeMode", null))

        assertFalse("Replaced clips must be deleted", File(clipsDir, "snore_99.wav").exists())
        assertFalse(context.getDatabasePath("backup_restore.db").exists())
        assertFalse(context.getDatabasePath("backup_snapshot.db").exists())
    }

    @Test
    fun restoredBackup_canBeBackedUpAndRestoredAgain() = runBlocking {
        seedOriginalData()
        assertTrue(restore(backupBytes()).success)

        val second = backupBytes()
        assertEquals(setOf("database.db", "audio/snore_1.wav", "backup.json"), entriesOf(second).keys)
        assertTrue(restore(second).success)

        val clip = File(db.snoreDao().getAllEvents().first().first { it.id == 1 }.audioFilePath!!)
        assertArrayEquals(clipBytes, clip.readBytes())
        assertEquals("Only the live restore folder remains", 1, clipsDir.list()!!.size)
    }

    @Test
    fun manifestComesLast_soATruncatedBackupHasNone() {
        seedOriginalData()
        assertEquals("backup.json", entriesOf(backupBytes()).keys.last())
    }

    @Test
    fun backupWithoutRecordings_restoresEventsWithoutBrokenAudioPaths() = runBlocking {
        seedOriginalData()
        val backup = backupBytes(includeRecordings = false)
        assertFalse(entriesOf(backup).keys.any { it.startsWith("audio/") })
        replaceWithCurrentData()

        val result = restore(backup)

        assertTrue(result.errorMessage, result.success)
        assertEquals(0, result.recordingCount)
        val events = db.snoreDao().getAllEvents().first()
        assertEquals(2, events.size)
        assertTrue(events.all { it.audioFilePath == null })
    }

    @Test
    fun emptyDatabase_producesARestorableBackup() = runBlocking {
        val backup = backupBytes()
        seedOriginalData()

        assertTrue(restore(backup).success)
        assertTrue(db.snoreDao().getAllEvents().first().isEmpty())
    }

    @Test
    fun microphoneSelection_isNotBackedUpAndSurvivesRestore() {
        seedOriginalData()
        val backup = backupBytes()
        val settings = JSONObject(String(entriesOf(backup).getValue("backup.json"))).getJSONObject("settings")
        assertFalse(settings.has("selectedAudioInputId"))
        assertFalse(settings.has("selectedAudioInputName"))

        prefs.edit().putInt("selectedAudioInputId", 7).commit()
        assertTrue(restore(backup).success)
        assertEquals(7, prefs.getInt("selectedAudioInputId", 0))
    }

    @Test
    fun settingAbsentFromBackup_returnsToDefault() {
        val backup = backupBytes() // no settings saved yet
        prefs.edit().putFloat("rmsDbThreshold", 70f).commit()

        assertTrue(restore(backup).success)
        assertFalse(prefs.contains("rmsDbThreshold"))
    }

    @Test
    fun backupFromDatabaseVersion1_runsThroughMigrations() = runBlocking {
        val v1File = File(context.cacheDir, "v1.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(v1File, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE `snore_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                    "`durationSeconds` REAL NOT NULL, `maxDb` REAL NOT NULL, `maxRms` REAL NOT NULL, " +
                    "`meanZcr` REAL NOT NULL, `meanBandEnergy` REAL NOT NULL, " +
                    "`meanLowFreqRatio` REAL NOT NULL, `audioFilePath` TEXT)"
            )
            v1.execSQL(
                "INSERT INTO snore_events (timestamp, durationSeconds, maxDb, maxRms, meanZcr, " +
                    "meanBandEnergy, meanLowFreqRatio, audioFilePath) " +
                    "VALUES (1771372800000, 2.5, 61.0, 0.04, 0.08, 0.03, 0.78, " +
                    "'/data/user/0/old.package/files/snore_clips/snore_5.wav')"
            )
            v1.version = 1
        }
        val manifest = JSONObject()
            .put("app", "snore-detector")
            .put("formatVersion", 1)
            .put("eventCount", 1)
            .put("recordingCount", 1)
            .put("settings", JSONObject())
        val backup = zipOf(
            "database.db" to v1File.readBytes(),
            "audio/snore_5.wav" to clipBytes,
            "backup.json" to manifest.toString().toByteArray()
        )
        replaceWithCurrentData()

        val result = restore(backup)

        assertTrue(result.errorMessage, result.success)
        val restored = db.snoreDao().getAllEvents().first().single()
        assertEquals(1771372800000L, restored.timestamp)
        assertArrayEquals(clipBytes, File(restored.audioFilePath!!).readBytes())
        assertTrue("Path must be rebuilt for this install", restored.audioFilePath!!.startsWith(context.filesDir.path))
        assertTrue(db.errorLogDao().getAllLogs().first().isEmpty())
    }

    @Test
    fun fileThatIsNotAZip_isRefused() {
        val clip = replaceWithCurrentData()
        assertCurrentDataUntouched(restore("definitely not a backup".toByteArray()), clip)
    }

    @Test
    fun emptyFile_isRefused() {
        val clip = replaceWithCurrentData()
        assertCurrentDataUntouched(restore(ByteArray(0)), clip)
    }

    @Test
    fun unrelatedZip_isRefused() {
        val clip = replaceWithCurrentData()
        assertCurrentDataUntouched(restore(zipOf("photo.jpg" to clipBytes)), clip)
    }

    @Test
    fun truncatedBackup_isRefused() {
        seedOriginalData()
        val backup = backupBytes()
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup.copyOf(backup.size / 2)), clip)
    }

    @Test
    fun backupMissingItsDatabase_isRefused() {
        seedOriginalData()
        val backup = withEntry(backupBytes(), "database.db", null)
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun manifestThatIsNotJson_isRefused() {
        seedOriginalData()
        val backup = withEntry(backupBytes(), "backup.json", "{oops".toByteArray())
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun databaseThatIsNotSqlite_isRefused() {
        seedOriginalData()
        val backup = withEntry(backupBytes(), "database.db", ByteArray(4096) { 7 })
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun databaseWithAForeignSchema_isRefused() {
        val foreign = File(context.cacheDir, "foreign.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { other ->
            other.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)")
            other.version = 2
        }
        seedOriginalData()
        val backup = withEntry(backupBytes(), "database.db", foreign.readBytes())
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun databaseFromANewerAppVersion_isRefusedWithAClearReason() {
        val newer = File(context.cacheDir, "newer.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(newer, null).use { it.version = 999 }
        seedOriginalData()
        val backup = withEntry(backupBytes(), "database.db", newer.readBytes())
        val clip = replaceWithCurrentData()

        val result = restore(backup)

        assertCurrentDataUntouched(result, clip)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("newer version"))
    }

    @Test
    fun newerBackupFormat_isRefused() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) { it.put("formatVersion", 2) }
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun manifestFromAnotherApp_isRefused() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) { it.put("app", "something-else") }
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun settingWithTheWrongType_isRefused() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) {
            it.getJSONObject("settings").put("rmsDbThreshold", "loud")
        }
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun unknownThemeMode_isRefused() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) {
            it.getJSONObject("settings").put("themeMode", "NEON")
        }
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun unknownSetting_isNotWrittenToPreferences() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) {
            it.getJSONObject("settings").put("somethingElse", "value")
        }

        assertTrue(restore(backup).success)
        assertFalse(prefs.contains("somethingElse"))
    }

    @Test
    fun missingRecording_isRefused() {
        seedOriginalData()
        val backup = withEntry(backupBytes(), "audio/snore_1.wav", null)
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun eventCountMismatch_isRefused() {
        seedOriginalData()
        val backup = withManifest(backupBytes()) { it.put("eventCount", 3) }
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
    }

    @Test
    fun entryEscapingTheClipFolder_isRefusedAndWritesNothing() {
        seedOriginalData()
        val backup = withEntry(backupBytes(), "audio/../../evil.wav", clipBytes)
        val clip = replaceWithCurrentData()

        assertCurrentDataUntouched(restore(backup), clip)
        assertFalse(File(context.filesDir, "evil.wav").exists())
    }

    @Test
    fun audioPathInDatabase_cannotPointOutsideTheRestoredClips() = runBlocking {
        db.snoreDao().insertEvent(event(1, "/data/data/other/files/secret.txt"))
        val backup = withManifest(backupBytes()) { it.put("recordingCount", 0) }

        assertTrue(restore(backup).success)
        assertNull(db.snoreDao().getAllEvents().first().single().audioFilePath)
    }

    @Test
    fun cloudBackupRules_onlyAllowTheSettings() {
        for (rules in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val includes = mutableListOf<String>()
            val parser = context.resources.getXml(rules)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "include") {
                    includes += parser.getAttributeValue(null, "domain") + ":" + parser.getAttributeValue(null, "path")
                }
            }
            assertEquals(listOf("sharedpref:snore_detector_preferences.xml"), includes)
        }
    }

    @Test
    fun viewModelRestore_updatesVisibleSettingsWithoutRestart() {
        val application = context as Application
        val viewModel = SnoreViewModel(application)
        val file = File(context.cacheDir, "viewmodel-backup.zip").apply { delete() }
        file.createNewFile()

        viewModel.updateRmsDbThreshold(61.5f)
        viewModel.updateThemeMode(ThemeMode.DARK)
        var exported: BackupResult? = null
        viewModel.exportBackup(Uri.fromFile(file), includeRecordings = true) { exported = it }
        awaitOnMainLooper { exported != null }
        assertTrue(exported!!.errorMessage, exported!!.success)
        assertNull(viewModel.backupProgressText.value)

        viewModel.updateRmsDbThreshold(70f)
        viewModel.updateThemeMode(ThemeMode.LIGHT)
        var restored: BackupResult? = null
        viewModel.restoreBackup(Uri.fromFile(file)) { restored = it }
        awaitOnMainLooper { restored != null }

        assertTrue(restored!!.errorMessage, restored!!.success)
        assertEquals(61.5f, viewModel.rmsDbThreshold.value, 0f)
        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
    }

    @Test
    fun viewModelRestore_reportsABadFile() {
        val viewModel = SnoreViewModel(context as Application)
        val file = File(context.cacheDir, "bad-backup.zip").apply { writeText("nope") }
        var result: BackupResult? = null

        viewModel.restoreBackup(Uri.fromFile(file)) { result = it }
        awaitOnMainLooper { result != null }

        assertNotNull(result)
        assertFalse(result!!.success)
    }

    private fun awaitOnMainLooper(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out", condition())
    }
}
