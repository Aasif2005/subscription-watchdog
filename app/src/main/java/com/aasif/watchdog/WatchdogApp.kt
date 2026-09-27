package com.aasif.watchdog

import android.app.Application
import com.aasif.watchdog.data.WatchdogRepository
import com.aasif.watchdog.data.db.WatchdogDatabase
import com.aasif.watchdog.work.Workers

class WatchdogApp : Application() {
    val repository: WatchdogRepository by lazy {
        WatchdogRepository.create(this, WatchdogDatabase.create(this).dao())
    }

    override fun onCreate() {
        super.onCreate()
        Workers.schedulePeriodic(this)
    }
}
