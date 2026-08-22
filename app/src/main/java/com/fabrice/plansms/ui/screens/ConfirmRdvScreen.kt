package com.fabrice.plansms.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.TomorrowRdv
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RDV de demain avec participant (email) : contact trouvé par email → coche + envoi ;
 * sinon rapprochement par nom/prénom proposé (utiliser le numéro / associer l'email au contact).
 */
@Composable
fun ConfirmRdvScreen(
    vm: PlanSmsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(vm.rdvConfirmMessage()) }
    var excluded by rememberSaveable { mutableStateOf(setOf<Long>()) }        // eventIds décochés
    var useSuggestion by rememberSaveable { mutableStateOf(setOf<Long>()) }   // eventIds envoyés via rapprochement
    var pendingAttach by remember { mutableStateOf<TomorrowRdv?>(null) }

    val writeContactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val rdv = pendingAttach
        pendingAttach = null
        if (granted && rdv != null && rdv.suggestionContactId > 0) {
            vm.attachEmailToContact(rdv.suggestionContactId, rdv.email)
        }
    }

    LaunchedEffect(Unit) { vm.loadTomorrowRdv() }
    BackHandler { if (!state.bulkSending) onClose() }

    val tomorrowLabel = remember {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        SimpleDateFormat("EEEE dd/MM", Locale.FRANCE).format(cal.time)
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !state.bulkSending) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("RDV de demain ($tomorrowLabel)", style = MaterialTheme.typography.titleLarge)
        }

        // Rapport final
        if (state.bulkReport.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Envoi terminé", style = MaterialTheme.typography.titleMedium, color = Success)
                    Spacer(Modifier.height(4.dp))
                    Text(state.bulkReport, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Le détail est dans Journal → 📨 Envois.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.clearBulkReport(); onClose() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("← Retour à l'accueil") }
            return
        }

        val rdvList = state.tomorrowRdv
        when {
            rdvList == null -> {
                Spacer(Modifier.height(10.dp))
                Text("Chargement de l'agenda…", style = MaterialTheme.typography.bodyMedium)
            }
            rdvList.isEmpty() -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Aucun rendez-vous demain avec un participant (email).",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.tomorrowRdvNoEmail > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.tomorrowRdvNoEmail} RDV demain sans participant — ignoré(s).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Astuce : vérifie dans Réglages → Calendrier que le bon calendrier n'est pas masqué.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("← Retour") }
            }
            else -> {
                // Destinataires effectifs : match email coché, ou rapprochement activé
                val recipients = rdvList.mapNotNull { r ->
                    when {
                        r.phone.isNotEmpty() && r.event.id !in excluded ->
                            Triple(r.phone, r.contactName, r.event.start)
                        r.suggestionPhone.isNotEmpty() && r.event.id in useSuggestion ->
                            Triple(r.suggestionPhone, r.suggestionName, r.event.start)
                        else -> null
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(rdvList, key = { "${it.event.id}-${it.event.start}" }) { r ->
                        RdvCard(
                            r = r,
                            checked = when {
                                r.phone.isNotEmpty() -> r.event.id !in excluded
                                else -> r.event.id in useSuggestion
                            },
                            enabled = !state.bulkSending,
                            onToggle = {
                                if (r.phone.isNotEmpty()) {
                                    excluded = if (r.event.id in excluded) excluded - r.event.id else excluded + r.event.id
                                } else if (r.suggestionPhone.isNotEmpty()) {
                                    useSuggestion = if (r.event.id in useSuggestion) useSuggestion - r.event.id else useSuggestion + r.event.id
                                }
                            },
                            onAttachEmail = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    vm.attachEmailToContact(r.suggestionContactId, r.email)
                                } else {
                                    pendingAttach = r
                                    writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                                }
                            }
                        )
                    }
                    if (state.tomorrowRdvNoEmail > 0) {
                        item {
                            Text(
                                "+ ${state.tomorrowRdvNoEmail} RDV sans participant (ignorés)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Message de confirmation") },
                    minLines = 3,
                    enabled = !state.bulkSending,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Ce texte est mémorisé pour la prochaine fois. Variables : {{prenom}}, {{nom}}, {{date}}, {{heure}} (heure du RDV).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                if (state.bulkSending) {
                    Text(
                        "Envoi en cours… ${state.bulkProgress}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { vm.sendRdvConfirmations(recipients, text.trim()) },
                        enabled = !state.bulkSending && recipients.isNotEmpty() && text.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Envoyer la confirmation (${recipients.size})")
                    }
                    OutlinedButton(onClick = onClose, enabled = !state.bulkSending) { Text("Retour") }
                }
            }
        }
    }
}

@Composable
private fun RdvCard(
    r: TomorrowRdv,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAttachEmail: () -> Unit
) {
    val hourFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    val selectable = r.phone.isNotEmpty() || r.suggestionPhone.isNotEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && selectable) { onToggle() }
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled && selectable
            )
            Column(Modifier.weight(1f).padding(top = 12.dp, end = 6.dp)) {
                Text(
                    "${hourFmt.format(Date(r.event.start))} — ${r.event.title.ifBlank { "(sans titre)" }}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (r.event.calendarName.isNotBlank()) {
                    Text("📅 ${r.event.calendarName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    // Cas 1 : contact trouvé par email
                    r.phone.isNotEmpty() -> {
                        Text(
                            "👤 ${r.contactName.ifBlank { r.email }} · ${r.phone}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Success
                        )
                        Text(r.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Cas 2 : rapprochement par nom/prénom proposé
                    r.suggestionPhone.isNotEmpty() -> {
                        Text(
                            "⚠️ ${r.email} absent des contacts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Danger
                        )
                        Text(
                            "Rapprochement proposé : ${r.suggestionName} · ${r.suggestionPhone}\n(coche pour utiliser ce numéro)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = onAttachEmail, enabled = enabled) {
                            Text("Associer l'email à ce contact")
                        }
                    }
                    // Cas 3 : rien trouvé
                    else -> {
                        Text(
                            "⚠️ ${r.email} : aucun contact trouvé (ni par email, ni par nom). Ajoute ce contact avec son numéro pour l'inclure.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Danger
                        )
                    }
                }
            }
        }
    }
}
