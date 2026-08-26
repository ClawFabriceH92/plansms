package com.fabrice.plansms.data

import android.content.Context
import com.fabrice.plansms.security.SecretStore

/**
 * Configuration de la transcription audio → texte.
 * Cible : tout serveur exposant l'API compatible OpenAI /v1/audio/transcriptions
 * (whisper.cpp --server, faster-whisper-server, Speaches…), idéalement sur ton
 * propre réseau pour que les enregistrements n'en sortent jamais.
 */
object TranscriptionPrefs {
    private const val PREFS = "plansms_transcription"
    private const val K_URL = "server_url"
    private const val K_MODEL = "model"
    private const val K_KEY = "api_key"
    private const val K_LANG = "language"
    private const val K_MODE = "mode"

    /** Transcription par le moteur hors ligne d'Android — rien ne sort du téléphone. */
    const val MODE_DEVICE = "DEVICE"

    /** Transcription par un serveur compatible OpenAI (whisper.cpp, faster-whisper…). */
    const val MODE_SERVER = "SERVER"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ex. http://192.168.0.162:8080 (whisper.cpp) — vide = transcription désactivée. */
    fun serverUrl(context: Context): String =
        prefs(context).getString(K_URL, "") ?: ""

    fun setServerUrl(context: Context, value: String) {
        prefs(context).edit().putString(K_URL, value.trim().trimEnd('/')).apply()
    }

    fun model(context: Context): String =
        prefs(context).getString(K_MODEL, "whisper-1") ?: "whisper-1"

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(K_MODEL, value.trim()).apply()
    }

    fun language(context: Context): String =
        prefs(context).getString(K_LANG, "fr") ?: "fr"

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(K_LANG, value.trim()).apply()
    }

    /** Facultatif : inutile pour un serveur local, requis pour un service en ligne. */
    fun apiKey(context: Context): String = SecretStore.decrypt(prefs(context).getString(K_KEY, "") ?: "")

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(K_KEY, SecretStore.encrypt(value)).apply()
    }

    fun mode(context: Context): String =
        prefs(context).getString(K_MODE, MODE_DEVICE) ?: MODE_DEVICE

    fun setMode(context: Context, value: String) {
        prefs(context).edit().putString(K_MODE, value).apply()
    }

    /** Vrai si la transcription est utilisable en l'état. */
    fun isConfigured(context: Context): Boolean =
        if (mode(context) == MODE_DEVICE) OnDeviceTranscriber.isSupported(context)
        else serverUrl(context).isNotBlank()
}
