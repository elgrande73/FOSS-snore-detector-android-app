package com.aistudio.snoredetector.afkwd.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.TimeZone

/**
 * App Database instance for storing snore detection assets locally and securely offline.
 *
 * Schema history:
 * - v1 (app 1.0.0): snore_events
 * - v2 (app 1.1.0): + error_logs
 * - v3: + monitoring_sessions, snore_events.sessionId
 *
 * Every schema change must bump [version] and add a [Migration] to [ALL_MIGRATIONS].
 * There is deliberately no destructive fallback: a missing migration fails loudly
 * instead of silently wiping the user's snoring history.
 */
@Database(
    entities = [SnoreEvent::class, ErrorLog::class, MonitoringSession::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun snoreDao(): SnoreDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val DATABASE_NAME = "snore_detector_database"

        /** v1 -> v2: adds the error_logs table; snore_events is unchanged. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `error_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`errorType` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL, " +
                        "`diagnosticDetails` TEXT NOT NULL, " +
                        "`component` TEXT NOT NULL)"
                )
            }
        }

        /** v2 -> v3: adds monitoring_sessions and links existing events to rebuilt nightly sessions. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monitoring_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER NOT NULL, " +
                        "`monitoredMinutes` REAL NOT NULL, " +
                        "`snoreEventCount` INTEGER NOT NULL, " +
                        "`totalSnoreMinutes` REAL NOT NULL, " +
                        "`longestEpisodeSeconds` REAL NOT NULL, " +
                        "`avgPeakDb` REAL, " +
                        "`maxPeakDb` REAL, " +
                        "`backgroundDb` REAL, " +
                        "`appVersion` TEXT, " +
                        "`detectionSettings` TEXT, " +
                        "`isBackfilled` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE `snore_events` ADD COLUMN `sessionId` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_snore_events_sessionId` ON `snore_events` (`sessionId`)")
                SessionBackfill.run(db, TimeZone.getDefault())
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
