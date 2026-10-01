package com.fabrice.plansms.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import com.fabrice.plansms.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Service de filtrage d'appels : Android le consulte AVANT de faire sonner un
 * appel entrant, à condition que PlanSMS tienne le rôle « application de
 * filtrage et anti-spam » (demandé depuis Réglages → Blocage d'appels).
 *
 * Un numéro dont le préfixe est bloqué est rejeté silencieusement : pas de
 * sonnerie, pas de notification d'appel manqué — seulement une trace dans le
 * journal d'appels Android et dans Journal → Envois de PlanSMS.
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val block = incoming &&
            CallBlocker.enabled(this) &&
            CallBlocker.shouldBlock(number, CallBlocker.prefixes(this))

        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(block)
                .setRejectCall(block)
                .setSkipNotification(block)
                .build()
        )

        if (block) {
            AppLogger.i("ScreeningService", "Appel bloqué avant sonnerie : $number")
            val appContext = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                CallBlocker.logBlocked(appContext, number)
            }
        }
    }
}
