package com.aasif.watchdog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EventEntity::class, ManualSubscriptionEntity::class, UserMarkEntity::class, UserFactEntity::class],
    version = 4,
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

        /**
         * Adds currency (rows so far are all INR), the peer-to-peer and auto-renew flags, and the
         * table the "Needs your input" screen writes to.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'")
                db.execSQL("ALTER TABLE events ADD COLUMN peerToPeer INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN autoRenews INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS user_facts (
                        subscriptionKey TEXT NOT NULL PRIMARY KEY,
                        frequency TEXT, nextRenewal TEXT, amount TEXT, currency TEXT,
                        dismissed INTEGER NOT NULL DEFAULT 0
                    )""",
                )
            }
        }

        /** Lets the user name a subscription the bank text could only attribute to a billing channel. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_facts ADD COLUMN name TEXT")
            }
        }

        fun create(context: Context): WatchdogDatabase =
            Room.databaseBuilder(context, WatchdogDatabase::class.java, "watchdog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
