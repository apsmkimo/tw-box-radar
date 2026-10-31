package com.twboxradar

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.twboxradar.work.RadarWorker
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class RadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RadarWorker.ensureChannel(this)
        // 行情每天收盤後發佈一次（16:30／18:30 台北），所以一天檢查一次，從 19:30 開始。
        // 資料還沒出來時 Worker 會回傳 retry，每 30 分鐘左右再試，最晚到 23:00。
        val request = PeriodicWorkRequestBuilder<RadarWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(CHECK_TIME), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork(LEGACY_WORK_NAME)
        // KEEP：每次開 App 不重設排程時間。
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun delayUntil(time: LocalTime): Long {
        val now = ZonedDateTime.now(RadarWorker.TAIPEI)
        var next = now.with(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    companion object {
        private const val WORK_NAME = "darvas-radar-daily"
        private const val LEGACY_WORK_NAME = "darvas-radar"
        private val CHECK_TIME: LocalTime = LocalTime.of(19, 30)
    }
}
