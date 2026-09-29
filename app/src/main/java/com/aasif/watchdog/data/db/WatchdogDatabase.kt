package com.aasif.watchdog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EventEntity::class, ManualSubscriptionEntity::class, UserMarkEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WatchdogDatabase : RoomDatabase() {
    abstract fun dao(): WatchdogDao

    companion object {
        /**
         * Adds provenance. The sending app or header is recoverable from the old rows because
         * `smsKey` starts with it; the arrival time is not, and stays 0 for them.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN source TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN receivedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE events SET source = substr(smsKey, 1, instr(smsKey, '|') - 1) WHERE instr(smsKey, '|') > 1")
            }
        }

        fun create(context: Context): WatchdogDatabase =
            Room.databaseBuilder(context, WatchdogDatabase::class.java, "watchdog.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
