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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that upgrading from the 1.0.0 database (schema v1) keeps the user's snoring history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates the database exactly as app 1.0.0 (Room schema v1) left it, with one event. */
    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
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
            db.execSQL(
                "INSERT INTO snore_events (timestamp, durationSeconds, maxDb, maxRms, meanZcr, " +
                    "meanBandEnergy, meanLowFreqRatio, audioFilePath) " +
                    "VALUES (1771372800000, 2.5, 61.0, 0.04, 0.08, 0.03, 0.78, 'clip.wav')"
            )
            db.version = 1
        }
    }

    @Test
    fun migrate1To2_preservesSnoreEventsAndAddsErrorLogs() = runBlocking {
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
