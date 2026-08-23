package com.fabrice.plansms.recorder

import android.content.Context

/** Préférences de l'enregistreur vocal. */
object RecorderPrefs {
    private const val PREFS = "plansms_recorder"
    private const val KEY_PROMPT_ON_CALL = "prompt_on_call"
    private const val KEY_SOURCE = "audio_source"

    /** Source audio : MIC (défaut) ou VOICE_RECOGNITION (souvent plus fidèle, sans traitement). */
    const val SOURCE_MIC = "MIC"
    const val SOURCE_RECOGNITION = "VOICE_RECOGNITION"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proposer d'enregistrer (notification) quand un appel téléphonique démarre. Désactivé par défaut. */
    fun promptOnCall(context: Context): Boolean = prefs(context).getBoolean(KEY_PROMPT_ON_CALL, false)

    fun setPromptOnCall(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROMPT_ON_CALL, on).apply()
    }

    fun audioSource(context: Context): String =
        prefs(context).getString(KEY_SOURCE, SOURCE_MIC) ?: SOURCE_MIC

    fun setAudioSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_SOURCE, source).apply()
    }
}
