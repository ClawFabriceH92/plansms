package com.fabrice.plansms.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.VoiceRecording
import com.fabrice.plansms.recorder.RecorderPrefs
import com.fabrice.plansms.recorder.RecorderState
import com.fabrice.plansms.recorder.RecordingService
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.data.StoragePrefs
import com.fabrice.plansms.security.BiometricAuth
import com.fabrice.plansms.security.PinManager
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistreur vocal : appels en haut-parleur (GSM, WhatsApp, Teams, Meet…),
 * réunions en présentiel, mémos. Capture par le micro du téléphone.
 */
@Composable
fun RecordingsTab(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(!RecorderPrefs.lockRecordings(context)) }
    if (unlocked) {
        RecordingsContent(vm, modifier)
    } else {
        RecordingsLockPanel(onUnlocked = { unlocked = true }, modifier = modifier)
    }
}

/** Écran de déverrouillage de l'onglet Audio (empreinte / visage, ou PIN de l'app). */
@Composable
private fun RecordingsLockPanel(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var lockError by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("🔒", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Enregistrements protégés", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
                "Authentifie-toi pour ouvrir la liste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (BiometricAuth.isAvailable(context)) {
                Button(
                    onClick = {
                        BiometricAuth.authenticate(
                            context,
                            "Enregistrements PlanSMS",
                            "Empreinte ou reconnaissance faciale",
                            "Utiliser le PIN"
                        ) { ok, msg ->
                            if (ok) { lockError = ""; onUnlocked() } else if (msg.isNotEmpty()) lockError = msg
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Déverrouiller avec l'empreinte") }
                Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
                value = pinInput,
                onValueChange = { value ->
                    pinInput = value.filter(Char::isDigit).take(4)
                    if (pinInput.length == 4) {
                        if (PinManager.verify(context, pinInput)) {
                            lockError = ""
                            onUnlocked()
                        } else {
                            lockError = "PIN incorrect"
                        }
                        pinInput = ""
                    }
                },
                label = { Text("PIN de l'app (4 chiffres)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
        )
        if (!PinManager.isEnabled(context)) {
                Text(
                    "Aucun PIN défini : le PIN par défaut est 0000. Configure-le dans Réglages → Sécurité.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }
        if (lockError.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(lockError, color = Danger, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecordingsContent(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRecording by RecorderState.isRecording.collectAsStateWithLifecycle()
    val elapsed by RecorderState.elapsedMs.collectAsStateWithLifecycle()
    val recorderError by RecorderState.lastError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var renaming by remember { mutableStateOf<VoiceRecording?>(null) }
    var deleting by remember { mutableStateOf<VoiceRecording?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var playingPath by remember { mutableStateOf("") }
    val player = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            try { player.reset(); player.release() } catch (_: Exception) {}
        }
    }

    val togglePlay: (VoiceRecording) -> Unit = { rec ->
        try {
            if (playingPath == rec.filePath) {
                player.reset()
                playingPath = ""
            } else {
                player.reset()
                player.setDataSource(rec.filePath)
                player.prepare()
                player.start()
                playingPath = rec.filePath
                player.setOnCompletionListener { playingPath = "" }
            }
        } catch (_: Exception) {
            playingPath = ""
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {

        // Rappel des limites Android + cadre légal
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp)) {
                Text("🎙 Enregistrement par le micro", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pour un appel (téléphone, WhatsApp, Teams, Meet…) : active le HAUT-PARLEUR, " +
                        "le micro capte alors les deux voix. Sans haut-parleur, seule ta voix est enregistrée.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { showInfo = true }) { Text("Pourquoi ? · Cadre légal") }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Bouton principal
        if (!hasPermission) {
            Text(
                "L'enregistrement nécessite l'accès au microphone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Autoriser le microphone") }
        } else {
            Button(
                onClick = {
                    if (isRecording) RecordingService.stop(context) else RecordingService.start(context)
                },
                colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = Danger) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isRecording) "⏹ Arrêter (${fmtDuration(elapsed)})" else "⏺ Démarrer l'enregistrement",
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isRecording) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Qualité :", style = MaterialTheme.typography.labelLarge)
                    var source by remember { mutableStateOf(RecorderPrefs.audioSource(context)) }
                    listOf(
                        RecorderPrefs.SOURCE_MIC to "Standard",
                        RecorderPrefs.SOURCE_RECOGNITION to "Voix (brut)"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = source == value,
                            onClick = {
                                source = value
                                RecorderPrefs.setAudioSource(context, value)
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        if (state.transcriptionError.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠️ Transcription : ${state.transcriptionError}",
                style = MaterialTheme.typography.bodyMedium,
                color = Danger
            )
        }
        if (state.transcriptionNote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.transcriptionNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (recorderError.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(recorderError, style = MaterialTheme.typography.bodyMedium, color = Danger)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "${state.recordings.size} enregistrement(s) · destination : ${StoragePrefs.label(context)}",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(6.dp))

        if (state.recordings.isEmpty()) {
            Text(
                "Aucun enregistrement. Les fichiers restent sur le téléphone (dossier privé de l'app), rien n'est envoyé ailleurs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.recordings, key = { it.id }) { rec ->
                    RecordingCard(
                        rec = rec,
                        playing = playingPath == rec.filePath,
                        onPlay = { togglePlay(rec) },
                        onRename = { renaming = rec },
                        onShare = { shareRecording(context, rec) },
                        onExport = { vm.exportRecording(rec) },
                        transcribing = state.transcribingId == rec.id,
                        canTranscribe = com.fabrice.plansms.data.TranscriptionPrefs.isConfigured(context),
                        onTranscribe = { vm.transcribeRecording(rec) },
                        onCopyTranscript = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clip.setPrimaryClip(
                                android.content.ClipData.newPlainText("transcription", rec.transcript)
                            )
                        },
                        onDelete = { deleting = rec }
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Enregistrement des appels") },
            text = {
                Column {
                    Text(
                        "Depuis Android 10, Google interdit aux applications installées hors Play Store " +
                            "(et à toutes les apps non-système) de capter directement la voix du correspondant " +
                            "sur la ligne d'appel. Aucune app ne peut contourner cela sans root.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ce qui fonctionne : mettre l'appel en haut-parleur — le micro capte ta voix et celle du " +
                            "correspondant. Cela marche pour les appels téléphoniques comme pour WhatsApp, Teams, " +
                            "Google Meet, Zoom, etc.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Cadre légal (France)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Enregistrer une conversation à l'insu des participants est sanctionné par l'article 226-1 " +
                            "du Code pénal. Préviens systématiquement ton interlocuteur et recueille son accord. " +
                            "En usage professionnel, le RGPD s'applique également (information, durée de conservation).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Compris") } }
        )
    }

    renaming?.let { rec ->
        var label by remember(rec.id) { mutableStateOf(rec.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Libellé (ex. Appel M. Dupont)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameRecording(rec, label); renaming = null }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } }
        )
    }

    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer l'enregistrement ?") },
            text = { Text("Le fichier audio sera définitivement effacé du téléphone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingPath == rec.filePath) {
                        try { player.reset() } catch (_: Exception) {}
                        playingPath = ""
                    }
                    vm.deleteRecording(rec)
                    deleting = null
                }) { Text("Supprimer", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun RecordingCard(
    rec: VoiceRecording,
    playing: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    transcribing: Boolean,
    canTranscribe: Boolean,
    onTranscribe: () -> Unit,
    onCopyTranscript: () -> Unit,
    onDelete: () -> Unit
) {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                rec.label.ifBlank { "Enregistrement du ${fmt.format(Date(rec.createdAt))}" },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${fmt.format(Date(rec.createdAt))} · ${fmtDuration(rec.durationMs)} · ${rec.sizeBytes / 1024} Ko",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rec.exportStatus.isNotEmpty()) {
                Text(
                    (if (rec.exportStatus == "OK") "☁️ " else "⚠️ ") + rec.exportInfo,
                    fontSize = 12.sp,
                    color = if (rec.exportStatus == "OK") Success else Danger
                )
            }
            if (rec.transcript.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("📝 Transcription", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(rec.transcript, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onCopyTranscript) { Text("Copier le texte") }
                    }
                }
            } else if (transcribing) {
                Text(
                    "📝 Transcription en cours…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else if (canTranscribe) {
                TextButton(onClick = onTranscribe) { Text("📝 Transcrire en texte") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(if (playing) " Arrêter" else " Écouter")
                }
                Spacer(Modifier.fillMaxWidth(0.02f))
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Renommer", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Partager", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Renvoyer vers la destination", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger)
                }
            }
        }
    }
}

private fun shareRecording(context: android.content.Context, rec: VoiceRecording) {
    try {
        val file = File(rec.filePath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Partager l'enregistrement"))
    } catch (_: Exception) {
    }
}

private fun fmtDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
