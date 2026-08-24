package com.fabrice.plansms.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.CallEntry
import com.fabrice.plansms.data.CallLogRepository
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mise en avant « a déjà répondu par SMS ». */
private val Amber = Color(0xFFB26A00)

/** Filtres du journal d'appels. */
private enum class CallFilter(val label: String) {
    ALL("Tous"),
    MISSED("Manqués"),
    INCOMING("Reçus"),
    OUTGOING("Émis")
}

/**
 * Journal en 2 onglets :
 *  - Appels : journal d'appels du téléphone → sélection multiple → SMS groupé
 *  - Envois : historique des SMS envoyés par l'app
 */
@Composable
fun JournalScreen(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var composing by rememberSaveable { mutableStateOf(false) }
    // Numéros sélectionnés (clé = numéro normalisé)
    var selectedKeys by rememberSaveable { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf(CallFilter.ALL) }

    var mobilesOnly by rememberSaveable { mutableStateOf(true) }
    var showSmsInfo by remember { mutableStateOf(false) }

    var hasPermission by remember { mutableStateOf(CallLogRepository.hasPermission(context)) }
    var hasSmsPermission by remember { mutableStateOf(CallLogRepository.hasSmsReadPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result[Manifest.permission.READ_CALL_LOG] ?: hasPermission
        hasSmsPermission = result[Manifest.permission.READ_SMS] ?: hasSmsPermission
        if (hasPermission) vm.loadCallLog()
    }

    // Recharge aussi quand la permission SMS vient d'être accordée, sinon le
    // marquage « a déjà répondu » resterait vide jusqu'au prochain lancement.
    LaunchedEffect(hasPermission, hasSmsPermission) {
        if (hasPermission) vm.loadCallLog()
    }

    // Liste regroupée par numéro : filtre type d'appel, puis mobiles/étrangers
    val allGrouped = remember(state.callLog, filter) {
        val filtered = when (filter) {
            CallFilter.ALL -> state.callLog
            CallFilter.MISSED -> state.callLog.filter { it.isMissed }
            CallFilter.INCOMING -> state.callLog.filter { it.isIncoming }
            CallFilter.OUTGOING -> state.callLog.filter { it.isOutgoing }
        }
        CallLogRepository.groupByNumber(filtered)
    }
    val grouped = remember(allGrouped, mobilesOnly) {
        if (mobilesOnly) allGrouped.filter { CallLogRepository.canReceiveSms(it.number) } else allGrouped
    }
    val hiddenCount = allGrouped.size - grouped.size
    val repliesShown = allGrouped.count { it.hasRepliedBySms }
    val selectedEntries = remember(grouped, selectedKeys, state.callLog) {
        // On repart de la liste complète regroupée pour garder la sélection même si le filtre change
        CallLogRepository.groupByNumber(state.callLog)
            .filter { CallLogRepository.normalize(it.number) in selectedKeys }
    }

    // --- Étape 2 : écriture + envoi du SMS groupé ---
    if (composing) {
        BackHandler { if (!state.bulkSending) composing = false }
        ComposeSmsStep(
            vm = vm,
            recipients = selectedEntries,
            onRemove = { entry ->
                selectedKeys = selectedKeys - CallLogRepository.normalize(entry.number)
            },
            onBack = { composing = false },
            onDone = {
                vm.clearBulkReport()
                selectedKeys = emptySet()
                composing = false
            },
            modifier = modifier
        )
        return
    }

    if (showSmsInfo) {
        AlertDialog(
            onDismissRequest = { showSmsInfo = false },
            title = { Text("Détection des réponses SMS") },
            text = {
                Column {
                    Text(
                        "PlanSMS compare la date du dernier appel de chaque numéro avec celle du " +
                            "dernier SMS reçu de ce même numéro. Le rapprochement se fait sur les 9 " +
                            "derniers chiffres, donc les formats 06…, +336… et 00336… sont équivalents.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Si une réponse n'est pas détectée :", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Message RCS / « chat » : avec Google Messages, les échanges entre deux " +
                            "téléphones compatibles passent par RCS et ne sont PAS enregistrés comme " +
                            "des SMS. Android ne donne aucun accès à ces messages — ils resteront " +
                            "invisibles pour l'app. Désactiver le chat RCS dans Google Messages fait " +
                            "repasser les échanges en SMS classiques.\n\n" +
                            "• Message reçu AVANT l'appel : seuls les SMS postérieurs au dernier appel " +
                            "sont signalés.\n\n" +
                            "• Surcouche constructeur (Xiaomi, Samsung…) : vérifie que l'autorisation " +
                            "SMS est bien accordée dans les réglages Android de PlanSMS.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSmsInfo = false }) { Text("Compris") } }
        )
    }

    // --- Étape 1 : les 2 onglets ---
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("📞 Appels") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("📨 Envois") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("🎙 Audio") })
        }

        if (tab == 2) {
            RecordingsTab(vm)
        } else if (tab == 0) {
            CallLogTab(
                hasPermission = hasPermission,
                onAskPermission = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS)
                    )
                },
                onRefresh = { vm.loadCallLog() },
                loaded = state.callLogLoaded,
                calls = grouped,
                filter = filter,
                onFilter = { filter = it },
                mobilesOnly = mobilesOnly,
                onMobilesOnly = { mobilesOnly = it },
                hiddenCount = hiddenCount,
                smsPermission = hasSmsPermission,
                onAskSmsPermission = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS)) },
                smsScanned = state.smsScanned,
                smsRepliesFound = repliesShown,
                onShowSmsInfo = { showSmsInfo = true },
                selectedKeys = selectedKeys,
                onToggle = { entry ->
                    val key = CallLogRepository.normalize(entry.number)
                    selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                },
                onSelectAll = {
                    // On écarte volontairement ceux qui ont déjà répondu par SMS
                    selectedKeys = grouped.filterNot { it.hasRepliedBySms }
                        .map { CallLogRepository.normalize(it.number) }.toSet()
                },
                onSelectNone = { selectedKeys = emptySet() },
                selectedCount = selectedEntries.size,
                onCompose = {
                    vm.clearBulkReport()
                    composing = true
                }
            )
        } else {
            SendLogTab(vm = vm, logs = state.logs)
        }
    }
}

// ---------------------------------------------------------------------------
// Onglet 1 : journal d'appels + sélection multiple
// ---------------------------------------------------------------------------

@Composable
private fun CallLogTab(
    hasPermission: Boolean,
    onAskPermission: () -> Unit,
    onRefresh: () -> Unit,
    loaded: Boolean,
    calls: List<CallEntry>,
    filter: CallFilter,
    onFilter: (CallFilter) -> Unit,
    mobilesOnly: Boolean,
    onMobilesOnly: (Boolean) -> Unit,
    hiddenCount: Int,
    smsPermission: Boolean,
    onAskSmsPermission: () -> Unit,
    smsScanned: Int,
    smsRepliesFound: Int,
    onShowSmsInfo: () -> Unit,
    selectedKeys: Set<String>,
    onToggle: (CallEntry) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    selectedCount: Int,
    onCompose: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        if (!hasPermission) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Pour envoyer un SMS aux numéros qui t'ont appelé, l'app doit lire le journal d'appels du téléphone (lecture seule, rien n'est modifié ni envoyé ailleurs).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAskPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Autoriser l'accès au journal d'appels")
            }
            return
        }

        // Filtres
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilter(f) },
                    label = { Text(f.label) }
                )
            }
        }

        // Mobiles uniquement (les fixes français ne reçoivent pas de SMS)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = mobilesOnly,
                onClick = { onMobilesOnly(!mobilesOnly) },
                label = { Text(if (mobilesOnly) "📱 Mobiles + étrangers" else "Tous les numéros") }
            )
            Spacer(Modifier.width(8.dp))
            if (mobilesOnly && hiddenCount > 0) {
                Text(
                    "$hiddenCount fixe(s)/service(s) masqué(s)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Diagnostic de la détection « a déjà répondu par SMS »
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                !smsPermission -> Text(
                    "Autorise la lecture des SMS pour repérer ceux qui t'ont déjà répondu.",
                    fontSize = 12.sp,
                    color = Danger,
                    modifier = Modifier.weight(1f)
                )
                smsScanned <= 0 -> Text(
                    "⚠️ Aucun SMS reçu n'a pu être lu — la détection des réponses ne peut pas fonctionner.",
                    fontSize = 12.sp,
                    color = Danger,
                    modifier = Modifier.weight(1f)
                )
                else -> Text(
                    "📩 $smsScanned SMS analysés · $smsRepliesFound réponse(s) depuis un appel",
                    fontSize = 12.sp,
                    color = if (smsRepliesFound > 0) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!smsPermission) {
                TextButton(onClick = onAskSmsPermission) { Text("Autoriser") }
            } else {
                TextButton(onClick = onShowSmsInfo) { Text("?") }
            }
        }

        // Barre de sélection
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selectedCount == 0) "Coche les numéros à contacter"
                else "$selectedCount numéro${if (selectedCount > 1) "s" else ""} sélectionné${if (selectedCount > 1) "s" else ""}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll) { Text("Tout") }
            TextButton(onClick = onSelectNone, enabled = selectedCount > 0) { Text("Aucun") }
        }

        if (!loaded) {
            Text("Chargement du journal d'appels…", style = MaterialTheme.typography.bodyMedium)
        } else if (calls.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Aucun appel trouvé pour ce filtre.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRefresh) { Text("Actualiser") }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(calls, key = { CallLogRepository.normalize(it.number) }) { call ->
                    CallCard(
                        call = call,
                        checked = CallLogRepository.normalize(call.number) in selectedKeys,
                        onToggle = { onToggle(call) }
                    )
                }
            }
        }

        // Bouton d'action principal, toujours visible en bas
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onCompose,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedCount == 0) "✉️ Écrire le SMS"
                else "✉️ Écrire le SMS ($selectedCount destinataire${if (selectedCount > 1) "s" else ""})"
            )
        }
    }
}

@Composable
private fun CallCard(call: CallEntry, checked: Boolean, onToggle: () -> Unit) {
    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
    val (icon, label, color) = when {
        call.isMissed -> Triple("❌", "Manqué", Danger)
        call.isIncoming -> Triple("📥", "Reçu", Success)
        call.isOutgoing -> Triple("📤", "Émis", MaterialTheme.colorScheme.secondary)
        else -> Triple("📞", "Appel", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val replied = call.hasRepliedBySms
    val smsFmt = SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                replied -> Amber.copy(alpha = 0.16f)
                checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    call.name.ifBlank { call.number },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                if (call.name.isNotBlank()) {
                    Text(call.number, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    Text("$icon $label", fontSize = 12.sp, color = color)
                    if (call.count > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text("· ${call.count} appels", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!CallLogRepository.canReceiveSms(call.number)) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "· ${CallLogRepository.kindLabel(call.number)}",
                            fontSize = 12.sp,
                            color = Danger
                        )
                    }
                }
                if (replied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📩 A DÉJÀ RÉPONDU PAR SMS — ${smsFmt.format(Date(call.lastSmsAt))}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Amber
                    )
                    if (call.lastSmsPreview.isNotBlank()) {
                        Text(
                            "« ${call.lastSmsPreview} »",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (call.hasEarlierSms) {
                    Text(
                        "✉️ dernier SMS reçu le ${smsFmt.format(Date(call.lastSmsAt))} (avant l'appel)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                fmt.format(Date(call.date)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Étape 2 : écriture du message + envoi groupé
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeSmsStep(
    vm: PlanSmsViewModel,
    recipients: List<CallEntry>,
    onRemove: (CallEntry) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var showTemplatePicker by remember { mutableStateOf(false) }

    if (showTemplatePicker) {
        AlertDialog(
            onDismissRequest = { showTemplatePicker = false },
            title = { Text("Choisir un modèle") },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(340.dp)
                ) {
                    items(state.templates, key = { it.id }) { t ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth().clickable {
                                text = t.body
                                showTemplatePicker = false
                            }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    t.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTemplatePicker = false }) { Text("Fermer") } }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        // En-tête avec retour
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.bulkSending) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("SMS aux appelants", style = MaterialTheme.typography.titleLarge)
        }

        // Rapport final → écran de résultat avec retour
        if (state.bulkReport.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Envoi terminé", style = MaterialTheme.typography.titleMedium, color = Success)
                    Spacer(Modifier.height(4.dp))
                    Text(state.bulkReport, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Le détail de chaque envoi est dans l'onglet 📨 Envois.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("← Retour au journal")
            }
            return
        }

        // Destinataires (supprimables tant que l'envoi n'a pas démarré)
        Text(
            "${recipients.size} destinataire${if (recipients.size > 1) "s" else ""} :",
            style = MaterialTheme.typography.labelLarge
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            recipients.forEach { r ->
                InputChip(
                    selected = false,
                    onClick = { if (!state.bulkSending) onRemove(r) },
                    label = { Text((r.name.ifBlank { r.number }) + "  ✕", maxLines = 1) }
                )
            }
        }
        if (recipients.isEmpty()) {
            Text(
                "Plus aucun destinataire — reviens en arrière pour en sélectionner.",
                style = MaterialTheme.typography.bodyMedium,
                color = Danger
            )
        }

        // Rappel fort : certains destinataires ont déjà répondu par SMS
        val alreadyReplied = recipients.filter { it.hasRepliedBySms }
        if (alreadyReplied.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.16f))) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "📩 ${alreadyReplied.size} destinataire(s) t'ont déjà répondu par SMS",
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber
                    )
                    alreadyReplied.forEach { r ->
                        Text(
                            "• " + r.name.ifBlank { r.number },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Retire-les si un nouveau SMS n'est pas nécessaire.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Choix d'un modèle (liste complète)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showTemplatePicker = true },
            enabled = !state.bulkSending && state.templates.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state.templates.isEmpty()) "📝 Aucun modèle — crée-les dans Réglages"
                else "📝 Utiliser un modèle (${state.templates.size})"
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Message") },
            minLines = 4,
            enabled = !state.bulkSending,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "{{prenom}} et {{nom}} sont remplacés par le nom de l'appelant (si connu), {{date}} et {{heure}} par le moment de l'envoi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        if (state.bulkSending) {
            Text(
                "Envoi en cours… ${state.bulkProgress} (3 s entre chaque envoi)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.sendBulkSms(recipients, text.trim()) },
                enabled = !state.bulkSending && recipients.isNotEmpty() && text.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Envoyer maintenant (${recipients.size})")
            }
            OutlinedButton(onClick = onBack, enabled = !state.bulkSending) {
                Text("Retour")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Onglet 2 : historique des envois de l'app (ancien journal)
// ---------------------------------------------------------------------------

@Composable
private fun SendLogTab(vm: PlanSmsViewModel, logs: List<SendLog>) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Historique des envois", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearLogs() }, enabled = logs.isNotEmpty()) { Text("Effacer") }
        }
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text(
                "Aucun envoi enregistré. Les tentatives d'envoi (réussies, échecs, rattrapages) apparaîtront ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs, key = { it.id }) { log ->
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
