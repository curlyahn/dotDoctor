package com.dot.doctor.report

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dot.doctor.DoctorApp
import com.dot.doctor.MainActivity
import com.dot.doctor.R
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * 월요일 아침 8시 무렵 '지난주 리포트를 만들 수 있어요' 알림만 띄운다.
 * 아무것도 보내지 않는다 — 보내기는 앱에서 미리 보기를 확인한 뒤 사용자가 직접 누른다.
 * 알림 문구에는 건강 값을 넣지 않는다 (잠금 화면에 보일 수 있음).
 */
class WeeklyReminder(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DoctorApp
        if (!app.prefs.now().weeklyReminder) return Result.success()
        val week = ReportBuilder.lastWeekStart().toString()
        if (app.db.reports().countFor(week) > 0) return Result.success()
        notifyReady(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK = "weekly_reminder"
        const val CHANNEL = "weekly"

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "주간 리포트 알림", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE }
            )
        }

        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).with(LocalTime.of(8, 0))
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            val delay = Duration.between(now, next).toMinutes()
            val req = PeriodicWorkRequestBuilder<WeeklyReminder>(7, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun notifyReady(ctx: Context) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("주간 리포트")
                .setContentText("지난주 리포트를 만들 수 있어요")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(1, n)
        }
    }
}
