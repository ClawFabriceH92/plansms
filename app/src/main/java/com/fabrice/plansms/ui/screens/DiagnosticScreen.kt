package com.fabrice.plansms.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabrice.plansms.data.CallLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mode diagnostic : montre exactement ce que l'app lit pour un numéro donné
 * (appels et SMS bruts, dates, clés de rapprochement) afin de comprendre
 * pourquoi une alerte « a déjà répondu » s'affiche ou non.
 */
@Composable
fun DiagnosticScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("Diagnostic appels / SMS", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Saisis un numéro pour voir ce que PlanSMS lit réellement : ses appels, " +
                "les SMS reçus de ce numéro, leurs dates exactes et le verdict de la " +
                "détection « a déjà répondu ».",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Numéro (ex. 0681371545 ou +33681371545)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                running = true
                report = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val base = CallLogRepository.diagnosticReport(context, number.trim())
                        val key = CallLogRepository.matchKey(number.trim())
                        val captured = try {
                            com.fabrice.plansms.data.AppDatabase.get(context)
                                .inboundMessageDao().forKey(key)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE)
                        val extra = StringBuilder("\n\n--- Messages RCS captés via les notifications ---\n")
                        if (!com.fabrice.plansms.notif.NotifPrefs.captureEnabled(context)) {
                            extra.append("Capture désactivée (Réglages → Messages RCS / chat).\n")
                        } else if (!com.fabrice.plansms.notif.MessageNotificationListener.isEnabled(context)) {
                            extra.append("Capture activée MAIS accès aux notifications non accordé.\n")
                        } else if (captured.isEmpty()) {
                            extra.append("Capture active, aucun message relevé pour ce numéro.\n")
                            extra.append("(seuls les messages reçus APRÈS activation sont visibles)\n")
                        } else {
                            captured.forEach { m ->
                                extra.append(fmt.format(java.util.Date(m.receivedAt)))
                                    .append("  [").append(m.address).append("]\n")
                                    .append("    « ").append(m.preview).append(" »\n")
                            }
                        }
                        base + extra.toString()
                    }
                    report = result
                    running = false
                }
            },
            enabled = !running && number.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (running) "Analyse en cours…" else "Analyser ce numéro") }

        val voicemailLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    com.fabrice.plansms.data.VoicemailProbe.report(context)
                }
                report = result
                running = false
            }
        }

        OutlinedButton(
            onClick = {
                running = true
                report = ""
                voicemailLauncher.launch("com.android.voicemail.permission.ADD_VOICEMAIL")
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sonder la messagerie vocale") }

        if (report.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text(
                    report,
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            OutlinedButton(
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("plansms-diagnostic", report))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Copier le rapport") }
        }

        Spacer(Modifier.height(20.dp))
    }
}
