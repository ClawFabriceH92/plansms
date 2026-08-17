package com.fabrice.plansms.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.Channel
import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.ui.PlanSmsViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    vm: PlanSmsViewModel,
    editingId: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editing = if (editingId >= 0) state.messages.find { it.id == editingId } else null

    var phone by remember { mutableStateOf(editing?.phone ?: "") }
    var text by remember { mutableStateOf(editing?.text ?: "") }
    var date by remember { mutableStateOf(editing?.let { fmtDate(it.targetDate) } ?: fmtDate(System.currentTimeMillis() + 3600_000)) }
    var hour by remember { mutableStateOf(editing?.hourOfDay?.toString() ?: "09") }
    var minute by remember { mutableStateOf(editing?.minuteOfHour?.toString() ?: "00") }
    var repeat by remember { mutableStateOf(editing?.repeatRule ?: RepeatRule.ONCE) }
    var noSend by remember { mutableStateOf(editing?.noSendStart ?: -1 >= 0) }
    var noSendStart by remember { mutableStateOf(editing?.noSendStart?.toString() ?: "22") }
    var noSendEnd by remember { mutableStateOf(editing?.noSendEnd?.toString() ?: "07") }
    var channel by remember { mutableStateOf(editing?.channel ?: Channel.SMS) }
    var useGroup by remember { mutableStateOf(editing?.groupId ?: 0L > 0) }
    var selectedGroupId by remember { mutableStateOf(editing?.groupId ?: 0L) }
    var error by remember { mutableStateOf("") }

    // Boutons templates rapides
    var showTemplatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Canal d'envoi
        Text("Canal", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Channel.SMS to "SMS", Channel.WHATSAPP to "WhatsApp").forEach { (ch, label) ->
                FilterChip(
                    selected = channel == ch,
                    onClick = { channel = ch },
                    label = { Text(label) }
                )
            }
        }
        if (channel == Channel.WHATSAPP) {
            Text(
                "WhatsApp est semi-automatique : une notification apparaîtra à l'heure dite, appuie pour ouvrir WhatsApp avec le message pré-rempli.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Destinataire : numéro ou groupe
        if (state.groups.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Destinataire :", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = useGroup,
                    onClick = { useGroup = !useGroup },
                    label = { Text(if (useGroup) "Groupe" else "Numéro") }
                )
            }
        }
        if (useGroup) {
            val groups = state.groups
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    FilterChip(
                        selected = selectedGroupId == g.id,
                        onClick = { selectedGroupId = g.id },
                        label = { Text(g.name, maxLines = 1) }
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Destinataire (numéro)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.templates.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modèles :", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically))
                state.templates.take(4).forEach { t ->
                    FilterChip(
                        selected = false,
                        onClick = { text = t.body },
                        label = { Text(t.name, maxLines = 1) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Message") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("Date (JJ/MM/AAAA)") },
                singleLine = true,
                modifier = Modifier.weight(1.4f)
            )
            OutlinedTextField(
                value = hour,
                onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                label = { Text("Heure") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.8f)
            )
            OutlinedTextField(
                value = minute,
                onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                label = { Text("Min") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.8f)
            )
        }

        // Répétition (scroll horizontal pour ne pas tronquer "Mensuel" sur petit écran)
        Text("Répétition", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            listOf(
                RepeatRule.ONCE to "Une fois",
                RepeatRule.DAILY to "Quotidien",
                RepeatRule.WEEKDAYS to "Jours ouvrés",
                RepeatRule.MONTHLY to "Mensuel"
            ).forEach { (rule, label) ->
                FilterChip(
                    selected = repeat == rule,
                    onClick = { repeat = rule },
                    label = { Text(label) }
                )
            }
        }

        // Plage d'envoi interdite
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Plage d'envoi interdite", style = MaterialTheme.typography.titleMedium)
                Text("Ex. 22h→7h : le message part après 7h", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = noSend, onCheckedChange = { noSend = it })
        }
        if (noSend) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = noSendStart,
                    onValueChange = { noSendStart = it.filter(Char::isDigit).take(2) },
                    label = { Text("Début (h)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = noSendEnd,
                    onValueChange = { noSendEnd = it.filter(Char::isDigit).take(2) },
                    label = { Text("Fin (h)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val h = hour.toIntOrNull() ?: run { error = "Heure invalide"; return@Button }
                    val m = minute.toIntOrNull() ?: run { error = "Minutes invalides"; return@Button }
                    if (h !in 0..23 || m !in 0..59) { error = "Heure hors limites"; return@Button }
                    val parsedDate = parseDate(date) ?: run { error = "Date invalide (JJ/MM/AAAA)"; return@Button }
                    val finalGroupId = if (useGroup) selectedGroupId else 0L
                    if (finalGroupId <= 0 && phone.isBlank()) { error = "Destinataire (numéro ou groupe) obligatoire"; return@Button }
                    if (text.isBlank()) { error = "Message obligatoire"; return@Button }
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = parsedDate
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val base = editing ?: ScheduledMessage(phone = phone, text = text, targetDate = cal.timeInMillis, hourOfDay = h, minuteOfHour = m)
                    val updated = base.copy(
                        phone = phone.trim(),
                        text = text,
                        targetDate = cal.timeInMillis,
                        hourOfDay = h,
                        minuteOfHour = m,
                        repeatRule = repeat,
                        noSendStart = if (noSend) (noSendStart.toIntOrNull() ?: 22) else -1,
                        noSendEnd = if (noSend) (noSendEnd.toIntOrNull() ?: 7) else -1,
                        status = com.fabrice.plansms.data.SmsStatus.SCHEDULED,
                        lastError = "",
                        channel = channel,
                        groupId = finalGroupId
                    )
                    if (editingId >= 0) vm.updateMessage(updated) else vm.addMessage(updated)
                    onClose()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (editingId >= 0) "Enregistrer" else "Programmer")
            }
            OutlinedButton(onClick = onClose) { Text("Annuler") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun fmtDate(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d/%02d/%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
}

private fun parseDate(s: String): Long? {
    val parts = s.trim().split("/")
    if (parts.size != 3) return null
    val d = parts[0].toIntOrNull() ?: return null
    val mo = parts[1].toIntOrNull() ?: return null
    val y = parts[2].toIntOrNull() ?: return null
    if (d !in 1..31 || mo !in 1..12 || y < 2020) return null
    return Calendar.getInstance().apply {
        clear()
        set(y, mo - 1, d, 12, 0, 0)
    }.timeInMillis
}
