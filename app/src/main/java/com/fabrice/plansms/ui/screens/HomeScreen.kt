package com.fabrice.plansms.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Mint
import com.fabrice.plansms.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: PlanSmsViewModel,
    onNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onConfirmRdv: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        CallResponderCard()
        androidx.compose.material3.OutlinedButton(
            onClick = onConfirmRdv,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)
        ) {
            Text("📅 Confirmer les prochains RDV (demain · lundi si vendredi)")
        }
        if (state.messages.isEmpty()) {
            EmptyState(onNew)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageCard(msg, onEdit, onDelete = { vm.deleteMessage(msg) })
                }
                item { Spacer(Modifier.height(70.dp)) }
            }
        }
    }
}

/**
 * Répondeur SMS : à l'activation, tout appel entrant d'un MOBILE non décroché
 * reçoit automatiquement le message configuré. Les fixes et numéros courts
 * sont écartés, et un même numéro n'est répondu qu'une fois par 4 h.
 */
@Composable
private fun CallResponderCard() {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(com.fabrice.plansms.scheduler.CallResponder.enabled(context))
    }
    var showEdit by remember { mutableStateOf(false) }
    val phoneStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Sans PHONE_STATE, impossible de voir les appels : on n'active pas.
            enabled = false
            com.fabrice.plansms.scheduler.CallResponder.setEnabled(context, false)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Success.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📵 Répondeur SMS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabled)
                            "Actif — les appels de mobiles non décrochés reçoivent un SMS."
                        else "À l'arrêt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) Success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        com.fabrice.plansms.scheduler.CallResponder.setEnabled(context, on)
                        if (on && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.READ_PHONE_STATE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    }
                )
            }
            if (enabled) {
                TextButton(onClick = { showEdit = true }) { Text("✏️ Message et options…") }
            }
        }
    }

    if (showEdit) {
        CallResponderDialog(onDismiss = { showEdit = false })
    }
}

@Composable
private fun CallResponderDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember {
        mutableStateOf(com.fabrice.plansms.scheduler.CallResponder.message(context))
    }
    var mode by remember {
        mutableStateOf(com.fabrice.plansms.scheduler.CallResponder.mode(context))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Répondeur SMS") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Message envoyé à l'appelant") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("Répondre à :", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == com.fabrice.plansms.scheduler.CallResponder.MODE_MISSED,
                        onClick = { mode = com.fabrice.plansms.scheduler.CallResponder.MODE_MISSED },
                        label = { Text("Appels non décrochés") }
                    )
                    FilterChip(
                        selected = mode == com.fabrice.plansms.scheduler.CallResponder.MODE_ALL,
                        onClick = { mode = com.fabrice.plansms.scheduler.CallResponder.MODE_ALL },
                        label = { Text("Tous les appels") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Seuls les numéros pouvant recevoir un SMS sont répondus : mobiles " +
                        "français (06/07) et numéros étrangers — jamais les fixes ni les " +
                        "numéros courts. Un même numéro n'est répondu qu'une fois par 4 h, " +
                        "même s'il rappelle. Chaque envoi apparaît dans Journal → Envois.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        com.fabrice.plansms.scheduler.CallResponder.setMessage(context, text)
                        com.fabrice.plansms.scheduler.CallResponder.setMode(context, mode)
                        onDismiss()
                    }
                },
                enabled = text.isNotBlank()
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✉️", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Aucun message programmé", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Programme un SMS : anniversaire, relance client, rappel…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNew, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Nouveau message")
        }
    }
}

@Composable
private fun MessageCard(
    msg: ScheduledMessage,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg.phone,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(msg.status)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                msg.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scheduleLabel(msg),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onEdit(msg.id) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: SmsStatus) {
    val (label, color) = when (status) {
        SmsStatus.SCHEDULED -> "Programmé" to MaterialTheme.colorScheme.primary
        SmsStatus.SENT -> "Envoyé" to Success
        SmsStatus.FAILED -> "Échec" to Danger
        SmsStatus.EXPIRED -> "Expiré" to MaterialTheme.colorScheme.onSurfaceVariant
        SmsStatus.CANCELLED -> "Annulé" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun scheduleLabel(msg: ScheduledMessage): String {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    val base = fmt.format(Date(msg.targetDate))
    return when (msg.repeatRule) {
        RepeatRule.ONCE -> base
        RepeatRule.DAILY -> "$base · tous les jours"
        RepeatRule.WEEKDAYS -> "$base · lundi-vendredi"
        RepeatRule.WEEKLY -> "$base · hebdo"
        RepeatRule.MONTHLY -> "$base · mensuel"
    }
}
