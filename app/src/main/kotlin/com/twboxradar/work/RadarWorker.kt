package com.twboxradar.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.twboxradar.MainActivity
import com.twboxradar.R
import com.twboxradar.core.BoxStatus
import com.twboxradar.core.DISCLAIMER
import com.twboxradar.core.analyze
import com.twboxradar.data.MarketRepository
import com.twboxradar.data.Prefs
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RadarWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.alertsEnabled || prefs.watchlist.isEmpty()) return Result.success()
        val repository = MarketRepository(applicationContext)
        val branch = prefs.dataBranch

        // 先只看 manifest（約 1KB）。資料日沒變就不下載任何行情。
        val asOf = try {
            repository.fetchAsOf(branch)
        } catch (error: Exception) {
            return Result.retry()
        }
        if (asOf.isBlank()) return Result.retry()
        if (asOf == prefs.lastAlertAsOf) {
            // 收盤資料可能還沒發佈（排程 16:30／18:30）。交易日晚上稍後再試。
            return if (shouldWaitForToday(asOf)) Result.retry() else Result.success()
        }

        val watched = prefs.watchlist
        val symbols = try {
            repository.loadSymbols(watched, branch)
        } catch (error: Exception) {
            return Result.retry()
        }
        val lines = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        symbols.map { analyze(it, prefs.boxParams()) }.forEach { row ->
            val event = when {
                row.status == BoxStatus.BREAKOUT && row.volumeConfirmed -> "breakout"
                row.status == BoxStatus.BREAKDOWN -> "breakdown"
                else -> return@forEach
            }
            val key = "$asOf|${row.code}|$event"
            keys += key
            if (key in prefs.notified) return@forEach
            lines += when (event) {
                "breakout" -> "${row.code} ${row.name} 突破箱頂，可能進入新箱型"
                else -> "${row.code} ${row.name} 跌破箱底，箱型被破壞"
            }
        }
        if (lines.isNotEmpty() && canNotify()) {
            show(lines)
        }
        // 只保留這個資料日的紀錄，避免 notified 無限增長。
        prefs.notified = keys
        prefs.lastAlertAsOf = asOf
        return Result.success()
    }

    /** 台北時間平日、23:00 以前，且資料日還不是今天：今天的收盤可能還沒發佈。 */
    private fun shouldWaitForToday(asOf: String): Boolean {
        val now = ZonedDateTime.now(TAIPEI)
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend || now.hour >= 23) return false
        if (runAttemptCount >= MAX_WAIT_ATTEMPTS) return false
        return asOf != now.format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun show(lines: List<String>) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle().setBigContentTitle("自選股箱型變動")
        lines.take(8).forEach { style.addLine(it) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            // SMCPKG_SUPPORT>>>CursorXXX
            // .setSmallIcon(R.drawable.ic_launcher)
            .setSmallIcon(R.drawable.ic_stat_notify)
            // SMCPKG_SUPPORT<<<CursorYYY
            .setContentTitle("自選股箱型變動")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSubText(DISCLAIMER)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "box-radar"
        private const val NOTIFICATION_ID = 42
        private const val MAX_WAIT_ATTEMPTS = 6
        val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "箱型變動",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "自選股突破箱頂或跌破箱底時通知。資料僅供參考，不構成投資建議。"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
