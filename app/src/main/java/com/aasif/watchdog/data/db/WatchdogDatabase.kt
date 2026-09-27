package com.aasif.watchdog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, ManualSubscriptionEntity::class, UserMarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WatchdogDatabase : RoomDatabase() {
    abstract fun dao(): WatchdogDao

    companion object {
        fun create(context: Context): WatchdogDatabase =
            Room.databaseBuilder(context, WatchdogDatabase::class.java, "watchdog.db").build()
    }
}
