package com.fabrice.plansms.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.AppDatabase
import com.fabrice.plansms.data.RelayException
import com.fabrice.plansms.data.RelayItem
import com.fabrice.plansms.data.RelaySlot
import com.fabrice.plansms.data.RelayStatus
import com.fabrice.plansms.data.StoragePrefs
import com.fabrice.plansms.relay.RelayMailer
import com.fabrice.plansms.relay.RelayPrefs
import com.fabrice.plansms.relay.RelaySchedule
import com.fabrice.plansms.relay.SmsRelay
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Relais SMS : transfert automatique des SMS reçus vers d'autres numéros et/ou
 * des adresses email, pendant les plages configurées. Hors plage, les messages
 * attendent en file et partent au créneau suivant.
 */
@Composable
fun RelayScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    var tab by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("Relais SMS", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Statut", "Destinataires", "Plages", "Historique").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> RelayStatusTab(db)
            1 -> RelayRecipientsTab()
            2 -> RelaySlotsTab(db)
            else -> RelayHistoryTab(db)
        }
    }
}

// ---------------------------------------------------------------------------
// Statut
// ---------------------------------------------------------------------------

@Composable
private fun RelayStatusTab(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(RelayPrefs.enabled(context)) }
    val queued by db.relayItemDao().observeQueuedCount().collectAsStateWithLifecycle(0)
    val slots by db.relaySlotDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val exceptions by db.relayExceptionDao().observeAll().collectAsStateWithLifecycle(emptyList())

    var active by remember { mutableStateOf(false) }
    var nextAt by remember { mutableStateOf(0L) }
    LaunchedEffect(enabled, slots, exceptions) {
        active = SmsRelay.isActiveNow(context)
        nextAt = SmsRelay.nextActiveAt(context) ?: 0L
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Transfert des SMS reçus", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) "Relais en service" else "Relais à l'arrêt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            RelayPrefs.setEnabled(context, it)
                            scope.launch { withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) } }
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        !enabled -> "⏸ Aucun SMS n'est transféré."
                        slots.isEmpty() && exceptions.isEmpty() ->
                            "⚠️ Aucune plage définie : rien ne partira. Ajoute un créneau dans « Plages »."
                        active -> "🟢 Plage ouverte — les SMS reçus partent immédiatement."
                        nextAt > 0 -> "🟠 Hors plage — prochaine ouverture " + whenLabel(nextAt)
                        else -> "🟠 Hors plage — aucune ouverture prévue dans les trois semaines."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active && enabled) Success else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (queued == 0) "File d'attente vide."
                    else "📦 $queued SMS en attente du prochain créneau.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (queued > 0) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { withContext(Dispatchers.IO) { SmsRelay.flush(context) } } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Vider la file maintenant") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Comment ça marche", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Chaque SMS reçu sur ce téléphone est transféré aux destinataires " +
                        "configurés, sous la forme « De <expéditeur> : <texte> ». " +
                        "Les SMS reçus hors plage ne sont pas perdus : ils attendent en " +
                        "file et partent à l'ouverture du créneau suivant, dans l'ordre.\n\n" +
                        "PlanSMS n'a pas besoin d'être ton application SMS par défaut : la " +
                        "permission de réception suffit, tes conversations restent dans " +
                        "Messages.\n\n" +
                        "Anti-boucle : un SMS venant d'un numéro qui est lui-même " +
                        "destinataire du relais n'est jamais retransféré.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        RelayOptionsCard()
    }
}

@Composable
private fun RelayOptionsCard() {
    val context = LocalContext.current
    var retention by remember { mutableStateOf(RelayPrefs.retentionDays(context).toString()) }
    var attempts by remember { mutableStateOf(RelayPrefs.maxAttempts(context).toString()) }
    var self by remember { mutableStateOf(RelayPrefs.selfNumber(context)) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Options", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = self,
                onValueChange = { self = it; RelayPrefs.setSelfNumber(context, it) },
                label = { Text("Numéro de ce téléphone (anti-boucle)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android ne donne pas ce numéro de façon fiable : le saisir évite " +
                    "qu'un SMS parti d'ici revienne ici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedTextField(
                    value = retention,
                    onValueChange = {
                        retention = it.filter { c -> c.isDigit() }.take(4)
                        RelayPrefs.setRetentionDays(context, retention.toIntOrNull() ?: 90)
                    },
                    label = { Text("Historique (jours)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = attempts,
                    onValueChange = {
                        attempts = it.filter { c -> c.isDigit() }.take(2)
                        RelayPrefs.setMaxAttempts(context, attempts.toIntOrNull() ?: 3)
                    },
                    label = { Text("Tentatives") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "0 jour = historique illimité. Les tentatives sont espacées de 2, 10 puis 30 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Destinataires
// ---------------------------------------------------------------------------

@Composable
private fun RelayRecipientsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var numbers by remember { mutableStateOf(RelayPrefs.numbers(context)) }
    var emails by remember { mutableStateOf(RelayPrefs.emails(context)) }
    var newNumber by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Numéros de téléphone", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNumber,
                        onValueChange = { newNumber = it },
                        label = { Text("06 12 34 56 78") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newNumber.isNotBlank()) {
                                numbers = (numbers + newNumber.trim()).distinct()
                                RelayPrefs.setNumbers(context, numbers)
                                newNumber = ""
                            }
                        }
                    ) { Text("Ajouter") }
                }
                Spacer(Modifier.height(8.dp))
                if (numbers.isEmpty()) {
                    Text(
                        "Aucun numéro. Les SMS ne seront transférés que par email.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    numbers.forEach { value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = {
                                numbers = numbers - value
                                RelayPrefs.setNumbers(context, numbers)
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Retirer", tint = Danger) }
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Adresses email", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = { Text("cabinet@exemple.fr") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newEmail.contains('@')) {
                                emails = (emails + newEmail.trim()).distinct()
                                RelayPrefs.setEmails(context, emails)
                                newEmail = ""
                            }
                        }
                    ) { Text("Ajouter") }
                }
                Spacer(Modifier.height(8.dp))
                emails.forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            emails = emails - value
                            RelayPrefs.setEmails(context, emails)
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Retirer", tint = Danger) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (RelayMailer.isConfigured(context))
                        "✅ Compte d'envoi : ${StoragePrefs.mailUser(context)} (Réglages → Stockage)"
                    else "⚠️ Aucun compte SMTP : configure-le dans Réglages → Stockage, sinon les emails ne partiront pas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (RelayMailer.isConfigured(context)) Success else MaterialTheme.colorScheme.error
                )
            }
        }

        Button(
            onClick = {
                testResult = ""
                scope.launch {
                    val message = withContext(Dispatchers.IO) { sendTest(context, numbers, emails) }
                    testOk = message.startsWith("✅")
                    testResult = message
                }
            },
            enabled = numbers.isNotEmpty() || emails.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Envoyer un message de test") }

        if (testResult.isNotEmpty()) {
            Text(
                testResult,
                style = MaterialTheme.typography.bodyMedium,
                color = if (testOk) Success else Danger
            )
        }
    }
}

private fun sendTest(context: android.content.Context, numbers: List<String>, emails: List<String>): String {
    val failures = mutableListOf<String>()
    val now = System.currentTimeMillis()
    for (number in numbers) {
        val error = com.fabrice.plansms.scheduler.SmsSender.send(
            context, number, "De TEST : ceci est un message de test du relais PlanSMS."
        )
        if (error != null) failures += "$number ($error)"
    }
    for (email in emails) {
        val error = RelayMailer.send(
            context, email, "TEST", "Ceci est un message de test du relais PlanSMS.", now
        )
        if (error != null) failures += "$email ($error)"
    }
    return if (failures.isEmpty()) "✅ Test envoyé à tous les destinataires."
    else "❌ Échec : " + failures.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Plages
// ---------------------------------------------------------------------------

private val DAY_NAMES = listOf("L", "M", "M", "J", "V", "S", "D")

@Composable
private fun RelaySlotsTab(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slots by db.relaySlotDao().observeAll().collectAsStateWithLifecycle(emptyList())
    val exceptions by db.relayExceptionDao().observeAll().collectAsStateWithLifecycle(emptyList())
    var showSlotDialog by remember { mutableStateOf(false) }
    var showExceptionDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Créneaux récurrents", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (slots.isEmpty()) {
                    Text(
                        "Aucun créneau : rien n'est transféré. Ajoute par exemple lun–ven 8h–19h.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    slots.forEach { slot ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    RelaySchedule.slotLabel(
                                        RelaySchedule.Slot(slot.daysMask, slot.startMin, slot.endMin, slot.enabled)
                                    ),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!slot.enabled) {
                                    Text(
                                        "désactivé",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = slot.enabled,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        db.relaySlotDao().update(slot.copy(enabled = on))
                                        withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) }
                                    }
                                }
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    db.relaySlotDao().delete(slot)
                                    withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) }
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { showSlotDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ajouter un créneau")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Jours d'exception", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Une exception prime sur les créneaux : elle ouvre ou ferme la journée entière.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (exceptions.isEmpty()) {
                    Text(
                        "Aucune exception.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    exceptions.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dayLabel(e.epochDay) + (if (e.active) " — ouvert" else " — fermé"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (e.active) Success else Danger
                                )
                                if (e.note.isNotBlank()) {
                                    Text(
                                        e.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    db.relayExceptionDao().delete(e)
                                    withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) }
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { showExceptionDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ajouter une exception")
                }
            }
        }
    }

    if (showSlotDialog) {
        SlotDialog(
            onDismiss = { showSlotDialog = false },
            onConfirm = { daysMask, startMin, endMin ->
                scope.launch {
                    db.relaySlotDao().insert(RelaySlot(daysMask = daysMask, startMin = startMin, endMin = endMin))
                    withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) }
                }
                showSlotDialog = false
            }
        )
    }

    if (showExceptionDialog) {
        ExceptionDialog(
            onDismiss = { showExceptionDialog = false },
            onConfirm = { epochDay, active, note ->
                scope.launch {
                    db.relayExceptionDao().insert(
                        RelayException(epochDay = epochDay, active = active, note = note)
                    )
                    withContext(Dispatchers.IO) { SmsRelay.scheduleNextWake(context) }
                }
                showExceptionDialog = false
            }
        )
    }
}

@Composable
private fun SlotDialog(onDismiss: () -> Unit, onConfirm: (Int, Int, Int) -> Unit) {
    var daysMask by remember { mutableStateOf(0b0011111) }
    var start by remember { mutableStateOf("08:00") }
    var end by remember { mutableStateOf("19:00") }
    val startMin = parseTime(start)
    val endMin = parseTime(end)
    val valid = daysMask != 0 && startMin != null && endMin != null && endMin > startMin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau créneau") },
        text = {
            Column {
                Text("Jours", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_NAMES.forEachIndexed { i, label ->
                        FilterChip(
                            selected = daysMask and (1 shl i) != 0,
                            onClick = { daysMask = daysMask xor (1 shl i) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("Début (hh:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("Fin (hh:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choisis au moins un jour, et une heure de fin après l'heure de début.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(daysMask, startMin!!, endMin!!) },
                enabled = valid
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun ExceptionDialog(onDismiss: () -> Unit, onConfirm: (Long, Boolean, String) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var active by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val day = try { LocalDate.parse(date.trim()) } catch (_: Exception) { null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jour d'exception") },
        text = {
            Column {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (AAAA-MM-JJ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !active,
                        onClick = { active = false },
                        label = { Text("Fermé toute la journée") }
                    )
                    FilterChip(
                        selected = active,
                        onClick = { active = true },
                        label = { Text("Ouvert toute la journée") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Motif (facultatif)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (day == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Date illisible — format attendu : 2026-08-31.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { day?.let { onConfirm(it.toEpochDay(), active, note.trim()) } },
                enabled = day != null
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ---------------------------------------------------------------------------
// Historique
// ---------------------------------------------------------------------------

@Composable
private fun RelayHistoryTab(db: AppDatabase) {
    val items by db.relayItemDao().observeRecent().collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableStateOf("TOUS") }
    val shown = if (filter == "TOUS") items else items.filter { it.status == filter }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "TOUS" to "Tous",
                RelayStatus.QUEUED to "En file",
                RelayStatus.SENT to "Envoyés",
                RelayStatus.FAILED to "Échecs"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Text(
                "Aucun transfert.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { item -> RelayItemCard(item) }
            }
        }
    }
}

@Composable
private fun RelayItemCard(item: RelayItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.sender, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    statusLabel(item.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (item.status) {
                        RelayStatus.SENT -> Success
                        RelayStatus.FAILED -> Danger
                        RelayStatus.PARTIAL -> Danger
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(item.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            Spacer(Modifier.height(6.dp))
            Text(
                "Reçu " + stampLabel(item.receivedAt) +
                    if (item.detail.isNotBlank()) " · ${item.detail}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utilitaires d'affichage
// ---------------------------------------------------------------------------

private fun statusLabel(status: String): String = when (status) {
    RelayStatus.SENT -> "envoyé"
    RelayStatus.QUEUED -> "en file"
    RelayStatus.PARTIAL -> "partiel"
    else -> "échec"
}

private fun parseTime(value: String): Int? {
    val parts = value.trim().split(':', 'h', 'H')
    if (parts.size < 2) return null
    val hours = parts[0].trim().toIntOrNull() ?: return null
    val minutes = parts[1].trim().ifBlank { "0" }.toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
}

private fun stampLabel(millis: Long): String =
    SimpleDateFormat("dd/MM 'à' HH:mm", Locale.FRANCE).format(Date(millis))

private fun whenLabel(millis: Long): String =
    SimpleDateFormat("EEEE dd/MM 'à' HH:mm", Locale.FRANCE).format(Date(millis))

private fun dayLabel(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy", Locale.FRANCE)
    )
