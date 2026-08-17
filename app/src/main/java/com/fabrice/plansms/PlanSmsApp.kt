package com.fabrice.plansms

import android.app.Application
import com.fabrice.plansms.util.AppLogger

class PlanSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this, BuildConfig.VERSION_NAME)
    }
}
