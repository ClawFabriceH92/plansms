package com.fabrice.plansms.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.AutoReplyRule
import com.fabrice.plansms.ui.PlanSmsViewModel

@Composable
fun AutoReplyScreen(vm: PlanSmsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rule = state.autoReply ?: AutoReplyRule()

    var enabled by remember { mutableStateOf(rule.enabled) }
    var replyText by remember { mutableStateOf(rule.replyText) }
    var mode by remember { mutableStateOf(rule.mode) }
    var numbers by remember { mutableStateOf(rule.numbers) }
    var delay by remember { mutableStateOf(rule.delayMinutes.toString()) }
    var onlyWhenIdle by remember { mutableStateOf(rule.onlyWhenIdle) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Retour") }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-réponse", style = MaterialTheme.typography.titleLarge)
                        Text("Répond automatiquement aux SMS reçus", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        }

        if (enabled) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Répondre à…", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == "ALL_EXCEPT",
                            onClick = { mode = "ALL_EXCEPT" },
                            label = { Text("Tous sauf…") }
                        )
                        FilterChip(
                            selected = mode == "ONLY",
                            onClick = { mode = "ONLY" },
                            label = { Text("Uniquement…") }
                        )
                    }
                    if (mode == "ALL_EXCEPT") {
                        Text("Numéros à EXCLURE (séparés par des virgules) :", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Numéros autorisés (séparés par des virgules) :", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(
                        value = numbers,
                        onValueChange = { numbers = it },
                        label = { Text("Ex. 0612345678, 0698765432") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Réponse", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        label = { Text("Texte de réponse ({{prenom}}, {{date}}…)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = delay,
                        onValueChange = { delay = it.filter(Char::isDigit).take(2) },
                        label = { Text("Délai avant réponse (minutes, 0 = immédiat)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ne répondre que si inoccupé", style = MaterialTheme.typography.bodyMedium)
                            Text("Pas de réponse pendant un appel", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = onlyWhenIdle, onCheckedChange = { onlyWhenIdle = it })
                    }
                }
            }
        }

        Button(
            onClick = {
                vm.saveAutoReply(
                    AutoReplyRule(
                        enabled = enabled,
                        replyText = replyText.ifBlank { "Je ne peux pas répondre pour le moment." },
                        mode = mode,
                        numbers = numbers,
                        delayMinutes = delay.toIntOrNull() ?: 0,
                        onlyWhenIdle = onlyWhenIdle
                    )
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer") }
        Spacer(Modifier.height(20.dp))
    }
}
