package com.fabrice.plansms.recorder

import kotlinx.coroutines.flow.MutableStateFlow

/** État partagé entre le service d'enregistrement et l'UI. */
object RecorderState {
    val isRecording = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val lastError = MutableStateFlow("")
}
