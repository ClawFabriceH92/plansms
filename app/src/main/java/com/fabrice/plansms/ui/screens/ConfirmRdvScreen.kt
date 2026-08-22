package com.fabrice.plansms.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.fabrice.plansms.data.ContactsHelper
import com.fabrice.plansms.data.TomorrowRdv
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RDV du prochain jour ouvré (demain, ou lundi si vendredi/week-end) avec participant :
 *  - contact trouvé par email → coché, prêt à envoyer ;
 *  - sinon, rapprochement par nom/prénom PROPOSÉ : rien n'est utilisé ni associé
 *    sans validation explicite (dialogue de confirmation, association opt-in).
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
    var excluded by rememberSaveable { mutableStateOf(setOf<Long>()) }   // RDV matchés par email, décochés
    // RDV envoyés via un rapprochement VALIDÉ : eventId → (numéro, nom)
    var chosen by rememberSaveable { mutableStateOf(hashMapOf<Long, Pair<String, String>>()) }
    var dialogFor by remember { mutableStateOf<TomorrowRdv?>(null) }
    var pendingAttach by remember { mutableStateOf<Pair<Long, String>?>(null) }  // (contactId, email)

    val writeContactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val attach = pendingAttach
        pendingAttach = null
        if (granted && attach != null) {
            vm.attachEmailToContact(attach.first, attach.second)
        }
    }
    val attachWithPermission: (Long, String) -> Unit = { contactId, email ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            vm.attachEmailToContact(contactId, email)
        } else {
            pendingAttach = contactId to email
            writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    LaunchedEffect(Unit) { vm.loadTomorrowRdv() }
    BackHandler { if (!state.bulkSending) onClose() }

    val targetLabel = if (state.tomorrowRdvTarget > 0)
        SimpleDateFormat("EEEE dd/MM", Locale.FRANCE).format(Date(state.tomorrowRdvTarget))
    else "demain"

    // Dialogue de validation d'un rapprochement (jamais d'association silencieuse)
    dialogFor?.let { rdv ->
        AssociateDialog(
            rdv = rdv,
            onDismiss = { dialogFor = null },
            onValidate = { contact, alsoAttachEmail ->
                chosen = HashMap(chosen).apply { put(rdv.event.id, contact.phone to contact.name) }
                if (alsoAttachEmail) attachWithPermission(contact.contactId, rdv.email)
                dialogFor = null
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !state.bulkSending) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("RDV de $targetLabel", style = MaterialTheme.typography.titleLarge)
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
                    "Aucun rendez-vous $targetLabel avec un participant (email).",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.tomorrowRdvNoEmail > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.tomorrowRdvNoEmail} RDV sans participant — ignoré(s).",
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
                val recipients = rdvList.mapNotNull { r ->
                    when {
                        r.phone.isNotEmpty() && r.event.id !in excluded ->
                            Triple(r.phone, r.contactName, r.event.start)
                        r.event.id in chosen ->
                            chosen[r.event.id]?.let { (phone, name) -> Triple(phone, name, r.event.start) }
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
                            checked = if (r.phone.isNotEmpty()) r.event.id !in excluded else r.event.id in chosen,
                            chosenContact = chosen[r.event.id],
                            enabled = !state.bulkSending,
                            onToggle = {
                                when {
                                    r.phone.isNotEmpty() ->
                                        excluded = if (r.event.id in excluded) excluded - r.event.id else excluded + r.event.id
                                    r.event.id in chosen ->
                                        chosen = HashMap(chosen).apply { remove(r.event.id) }
                                    r.suggestions.isNotEmpty() ->
                                        dialogFor = r   // toujours valider via le dialogue — jamais de coche directe
                                }
                            },
                            onOpenDialog = { dialogFor = r }
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
                    "Texte mémorisé pour la prochaine fois. Variables : {{prenom}}, {{nom}}, {{date}}, {{heure}} (heure du RDV).",
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
    chosenContact: Pair<String, String>?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenDialog: () -> Unit
) {
    val hourFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    val selectable = r.phone.isNotEmpty() || r.suggestions.isNotEmpty()
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
                    // Cas 1 : contact trouvé par email → fiable
                    r.phone.isNotEmpty() -> {
                        Text(
                            "👤 ${r.contactName.ifBlank { r.email }} · ${r.phone}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Success
                        )
                        Text(r.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Cas 2 : rapprochement déjà validé par toi
                    chosenContact != null -> {
                        Text(
                            "✔ Rapprochement validé : ${chosenContact.second} · ${chosenContact.first}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Success
                        )
                        Text(r.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenDialog, enabled = enabled) { Text("Changer…") }
                    }
                    // Cas 3 : rapprochement proposé — à valider
                    r.suggestions.isNotEmpty() -> {
                        Text(
                            "⚠️ ${r.email} absent des contacts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Danger
                        )
                        val best = r.suggestions.first()
                        Text(
                            if (r.suggestionStrong)
                                "Rapprochement proposé : ${best.name} · ${best.phone}"
                            else
                                "Rapprochement incertain : ${best.name}${if (r.suggestions.size > 1) " (+${r.suggestions.size - 1} autre(s))" else ""} — à vérifier",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = onOpenDialog, enabled = enabled) {
                            Text("Vérifier et valider…")
                        }
                    }
                    // Cas 4 : rien trouvé
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

/**
 * Validation d'un rapprochement : choix du contact parmi les candidats,
 * association de l'email en OPTION (décochée par défaut). Rien n'est modifié
 * dans le répertoire sans passer par ce dialogue.
 */
@Composable
private fun AssociateDialog(
    rdv: TomorrowRdv,
    onDismiss: () -> Unit,
    onValidate: (ContactsHelper.PhoneContact, Boolean) -> Unit
) {
    var selected by remember {
        mutableStateOf(if (rdv.suggestionStrong) rdv.suggestions.firstOrNull() else null)
    }
    var alsoAttach by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vérifier le rapprochement") },
        text = {
            Column {
                Text(
                    "Participant : ${rdv.attendeeName.ifBlank { "(sans nom)" }}\n${rdv.email}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Text("Contact du répertoire :", style = MaterialTheme.typography.labelLarge)
                rdv.suggestions.forEach { contact ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selected = contact }
                    ) {
                        RadioButton(selected = selected == contact, onClick = { selected = contact })
                        Column {
                            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                            Text(contact.phone, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { alsoAttach = !alsoAttach }
                ) {
                    Checkbox(checked = alsoAttach, onCheckedChange = { alsoAttach = it })
                    Text(
                        "Ajouter aussi l'email à la fiche de ce contact",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "⚠️ Vérifie qu'il s'agit bien de la même personne : rien n'est utilisé ni modifié sans ta validation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onValidate(it, alsoAttach) } },
                enabled = selected != null
            ) { Text("Valider") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
