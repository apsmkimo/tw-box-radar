package com.twboxradar

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.twboxradar.work.RadarWorker
import java.util.concurrent.TimeUnit

class RadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RadarWorker.ensureChannel(this)
        val request = PeriodicWorkRequestBuilder<RadarWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "darvas-radar",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
