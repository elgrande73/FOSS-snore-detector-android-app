package com.aistudio.snoredetector.afkwd

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.snoredetector.afkwd.data.AppDatabase
import com.aistudio.snoredetector.afkwd.data.ErrorLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Verifies that upgrading from older databases keeps the user's snoring history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
        TimeZone.setDefault(originalTimeZone)
    }

    /** Creates the database exactly as app 1.0.0 (Room schema v1) left it, with one event. */
    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createEventsTable(db)
            db.execSQL(
                "INSERT INTO snore_events (timestamp, durationSeconds, maxDb, maxRms, meanZcr, " +
                    "meanBandEnergy, meanLowFreqRatio, audioFilePath) " +
                    "VALUES (1771372800000, 2.5, 61.0, 0.04, 0.08, 0.03, 0.78, 'clip.wav')"
            )
            db.version = 1
        }
    }

    private fun createEventsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snore_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`durationSeconds` REAL NOT NULL, " +
                "`maxDb` REAL NOT NULL, " +
                "`maxRms` REAL NOT NULL, " +
                "`meanZcr` REAL NOT NULL, " +
                "`meanBandEnergy` REAL NOT NULL, " +
                "`meanLowFreqRatio` REAL NOT NULL, " +
                "`audioFilePath` TEXT)"
        )
    }

    /** Creates the database exactly as app 1.1.x (Room schema v2) left it. */
    private fun createVersion2Database(eventTimes: List<Pair<Long, Double>>) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createEventsTable(db)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `error_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`errorType` TEXT NOT NULL, " +
                    "`message` TEXT NOT NULL, " +
                    "`diagnosticDetails` TEXT NOT NULL, " +
                    "`component` TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO error_logs (timestamp, errorType, message, diagnosticDetails, component) " +
                    "VALUES (1, 'TYPE', 'kept', '', 'test')"
            )
            eventTimes.forEachIndexed { index, (timestamp, seconds) ->
                db.execSQL(
                    "INSERT INTO snore_events (timestamp, durationSeconds, maxDb, maxRms, meanZcr, " +
                        "meanBandEnergy, meanLowFreqRatio, audioFilePath) VALUES (?, ?, ?, 0.04, 0.08, 0.03, 0.78, ?)",
                    arrayOf<Any>(timestamp, seconds, 60f + index, "clip$index.wav")
                )
            }
            db.version = 2
        }
    }

    private fun lisbonTime(day: Int, hour: Int, minute: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("Europe/Lisbon")).apply {
            clear()
            set(2026, 2, day, hour, minute)
        }.timeInMillis

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migrate2To3_keepsEventsAndGroupsThemIntoNights(): Unit = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
        val beforeMidnight = lisbonTime(10, 23, 50)
        val afterMidnight = lisbonTime(11, 0, 20)
        val nextNight = lisbonTime(11, 22, 0)
        createVersion2Database(listOf(beforeMidnight to 30.0, afterMidnight to 90.0, nextNight to 10.0))

        val db = openDatabase() // Opening runs Room's schema validation against the v3 entities
        try {
            val events = db.snoreDao().getAllEvents().first().sortedBy { it.timestamp }
            assertEquals(listOf(beforeMidnight, afterMidnight, nextNight), events.map { it.timestamp })
            assertEquals("clip1.wav", events[1].audioFilePath)
            assertEquals(90.0, events[1].durationSeconds, 0.0001)
            assertEquals(1, db.errorLogDao().getAllLogs().first().size)

            val sessions = db.sessionDao().getAllSessionsOnce().sortedBy { it.startTime }
            assertEquals(2, sessions.size)
            assertEquals(sessions[0].id, events[0].sessionId)
            assertEquals(sessions[0].id, events[1].sessionId)
            assertEquals(sessions[1].id, events[2].sessionId)

            with(sessions[0]) {
                assertTrue(isBackfilled)
                assertEquals(beforeMidnight, startTime)
                assertEquals(afterMidnight + 90_000L, endTime)
                assertEquals(2, snoreEventCount)
                assertEquals(2.0, totalSnoreMinutes, 0.0001)
                assertEquals(90.0, longestEpisodeSeconds, 0.0001)
                assertEquals(60.5f, avgPeakDb!!, 0.0001f)
                assertEquals(61f, maxPeakDb!!, 0.0001f)
                // Monitored time was never recorded, so none is invented
                assertEquals(0.0, monitoredMinutes, 0.0)
                assertNull(appVersion)
                assertNull(detectionSettings)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_handlesAnEmptyHistory(): Unit = runBlocking {
        createVersion2Database(emptyList())

        val db = openDatabase()
        try {
            assertTrue(db.sessionDao().getAllSessionsOnce().isEmpty())
            assertTrue(db.snoreDao().getAllEvents().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1To3_runsTheWholeChain(): Unit = runBlocking {
        createVersion1Database()

        val db = openDatabase()
        try {
            val event = db.snoreDao().getAllEvents().first().single()
            assertEquals(db.sessionDao().getAllSessionsOnce().single().id, event.sessionId)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1To2_preservesSnoreEventsAndAddsErrorLogs(): Unit = runBlocking {
        createVersion1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val events = db.snoreDao().getAllEvents().first()
            assertEquals(1, events.size)
            with(events.single()) {
                assertEquals(1771372800000L, timestamp)
                assertEquals(2.5, durationSeconds, 0.0001)
                assertEquals(61.0f, maxDb, 0.0001f)
                assertEquals("clip.wav", audioFilePath)
            }

            // The new error_logs table must be usable after the migration.
            db.errorLogDao().insertLog(
                ErrorLog(errorType = "TEST", message = "after migration", diagnosticDetails = "")
            )
        } finally {
            db.close()
        }
    }
}
