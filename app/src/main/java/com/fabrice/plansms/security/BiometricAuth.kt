package com.fabrice.plansms.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Déverrouillage biométrique (empreinte / visage) via l'API système.
 * Aucune donnée biométrique ne transite par l'app : Android renvoie
 * seulement « authentifié » ou « échec ».
 */
object BiometricAuth {

    /** true si le téléphone a une biométrie configurée et utilisable. */
    fun isAvailable(context: Context): Boolean = try {
        val bm = context.getSystemService(BiometricManager::class.java)
        if (bm == null) {
            false
        } else if (Build.VERSION.SDK_INT >= 30) {
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Affiche la demande d'authentification.
     * [onResult] : true = authentifié ; false = annulé ou échec (message éventuel).
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String,
        negativeLabel: String,
        onResult: (Boolean, String) -> Unit
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(negativeLabel, executor) { _, _ -> onResult(false, "") }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onResult(true, "")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onResult(false, errString.toString())
                    }
                }
            )
        } catch (e: Exception) {
            onResult(false, e.message ?: "Biométrie indisponible")
        }
    }
}
