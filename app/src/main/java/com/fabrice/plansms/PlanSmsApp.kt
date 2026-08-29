package com.fabrice.plansms

import android.app.Application
import com.fabrice.plansms.relay.SmsRelay
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlanSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this, BuildConfig.VERSION_NAME)

        // Relais SMS : à chaque ouverture, on reprend la file d'attente et on
        // réarme le réveil — un SMS reçu pendant que l'app dormait ne se perd pas.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsRelay.flush(this@PlanSmsApp)
                SmsRelay.scheduleDailyDigest(this@PlanSmsApp)
            } catch (e: Exception) {
                AppLogger.e("PlanSmsApp", "Reprise du relais SMS impossible", e)
            }
        }
    }
}
