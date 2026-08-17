package com.fabrice.plansms.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.util.AppLogger
import com.fabrice.plansms.BuildConfig

@Composable
fun SettingsScreen(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Sécurité", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Verrouillage PIN", style = MaterialTheme.typography.titleMedium)
                        Text("PIN 4 chiffres au lancement", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = state.pinEnabled,
                        onCheckedChange = { on ->
                            if (on) showPinDialog = true
                            else vm.disablePin()
                        }
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Données", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        vm.export()
                        showExportDialog = true
                    }, modifier = Modifier.weight(1f)) { Text("Exporter JSON") }
                    OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.weight(1f)) { Text("Importer") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("À propos", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("PlanSMS v${BuildConfig.VERSION_NAME} — programmation de SMS fiable", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val logFile = AppLogger.saveToFile(context)
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("log", AppLogger.getLogText()))
                    message = "Log copié dans le presse-papiers (${logFile.name})"
                }) { Text("Copier le journal d'activité") }
            }
        }

        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (showPinDialog) {
        PinSetupDialog(
            onConfirm = { pin -> vm.enablePin(pin); showPinDialog = false },
            onDismiss = { showPinDialog = false }
        )
    }

    if (showExportDialog && state.exportText.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export JSON") },
            text = {
                Column {
                    Text("Sauvegarde de ${state.messages.size} message(s) et ${state.templates.size} modèle(s). Copie le contenu et stocke-le ailleurs.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.exportText.take(2000),
                        onValueChange = {},
                        readOnly = true,
                        minLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("plansms-export", state.exportText))
                    showExportDialog = false
                }) { Text("Copier tout") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Fermer") }
            }
        )
    }

    if (showImportDialog) {
        ImportDialog(
            onConfirm = { json ->
                val ok = vm.import(json)
                message = if (ok) "Import réussi" else "JSON invalide"
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

@Composable
private fun PinSetupDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activer le PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                    label = { Text("PIN 4 chiffres") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { pin2 = it.filter(Char::isDigit).take(4) },
                    label = { Text("Confirmer le PIN") },
                    singleLine = true
                )
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length != 4 -> error = "PIN : 4 chiffres requis"
                    pin != pin2 -> error = "Les PIN ne correspondent pas"
                    else -> onConfirm(pin)
                }
            }) { Text("Activer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun ImportDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importer JSON") },
        text = {
            OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                label = { Text("Contenu du fichier d'export") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (json.isNotBlank()) onConfirm(json) }) { Text("Importer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
