package com.aistudio.snoredetector.afkwd.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App Database instance for storing snore detection assets locally and securely offline.
 *
 * Schema history:
 * - v1 (app 1.0.0): snore_events
 * - v2 (app 1.1.0): + error_logs
 *
 * Every schema change must bump [version] and add a [Migration] to [ALL_MIGRATIONS].
 * There is deliberately no destructive fallback: a missing migration fails loudly
 * instead of silently wiping the user's snoring history.
 */
@Database(entities = [SnoreEvent::class, ErrorLog::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun snoreDao(): SnoreDao
    abstract fun errorLogDao(): ErrorLogDao

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

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

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
