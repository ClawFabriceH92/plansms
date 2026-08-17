package com.fabrice.plansms.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Journal de bord", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearLogs() }) { Text("Effacer") }
        }
        Spacer(Modifier.height(8.dp))
        if (state.logs.isEmpty()) {
            Text(
                "Aucun envoi enregistré. Les tentatives d'envoi (réussies, échecs, rattrapages) apparaîtront ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.logs, key = { it.id }) { log ->
                    LogCard(log)
                }
            }
        }
    }
}

@Composable
private fun LogCard(log: SendLog) {
    val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
    val (label, color) = when (log.status) {
        "SENT" -> "Envoyé" to Success
        "RATTRAPAGE" -> "Rattrapage" to MaterialTheme.colorScheme.secondary
        else -> "Échec" to Danger
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
                Text(fmt.format(Date(log.sentAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(log.phone, style = MaterialTheme.typography.labelLarge)
            Text(log.textPreview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (log.error.isNotEmpty()) {
                Text(log.error, fontSize = 11.sp, color = Danger)
            }
        }
    }
}
