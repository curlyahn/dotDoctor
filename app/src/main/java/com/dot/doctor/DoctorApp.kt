package com.dot.doctor

import android.app.Application
import com.dot.doctor.data.AppDatabase
import com.dot.doctor.data.Prefs
import com.dot.doctor.report.WeeklyReminder

class DoctorApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        // 혹시 남은 약봉투 임시 사진이 있으면 지운다
        java.io.File(cacheDir, "envelope").listFiles()?.forEach { it.delete() }
        WeeklyReminder.createChannel(this)
        WeeklyReminder.schedule(this)
    }
}
