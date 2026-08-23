package com.fabrice.plansms.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** PIN 4 chiffres (hash SHA-256 stocké, pas en clair). Défaut 0000. */
object PinManager {
    private const val PREFS = "plansms_pin"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ENABLED = "pin_enabled"
    private const val KEY_BIOMETRIC = "biometric_enabled"
    private const val DEFAULT_PIN = "0000"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun enable(context: Context, pin: String) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).putString(KEY_HASH, hash(pin)).apply()
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).remove(KEY_HASH).apply()
    }

    /** Déverrouillage par empreinte/visage en complément du PIN. */
    fun isBiometricEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, on).apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val h = prefs(context).getString(KEY_HASH, hash(DEFAULT_PIN)) ?: hash(DEFAULT_PIN)
        return hash(pin) == h
    }
}
