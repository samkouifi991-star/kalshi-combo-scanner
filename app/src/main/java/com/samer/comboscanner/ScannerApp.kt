package com.samer.comboscanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.samer.comboscanner.data.ScannerRepository
import com.samer.comboscanner.work.ScanWorker

class ScannerApp : Application() {
    lateinit var repo: ScannerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = ScannerRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Verified opportunities", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Combos whose verified quote clears your return threshold"
            },
        )
        ScanWorker.schedule(this, repo.settings.value)
    }

    companion object {
        const val ALERT_CHANNEL = "verified_opportunities"
    }
}
