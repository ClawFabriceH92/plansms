package com.fabrice.plansms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fabrice.plansms.data.CalendarEvent
import com.fabrice.plansms.data.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Résultat de la sélection d'un événement agenda. */
data class AgendaSelection(
    val event: CalendarEvent,
    val phone: String,       // numéro du participant mappé (ou email en repli)
    val contactName: String
)

/**
 * Dialog de sélection d'un événement du calendrier (30 prochains jours).
 * Au clic : mapping participants → répertoire, préremplissage.
 */
@Composable
fun AgendaPickerDialog(
    onSelect: (AgendaSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<CalendarEvent>?>(null) }
    var mapped by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val evts = withContext(Dispatchers.IO) {
            CalendarRepository.readUpcomingEvents(context)
        }
        val map = HashMap<Long, Pair<String, String>>()
        for (e in evts) {
            val first = e.attendees.firstNotNullOfOrNull { email ->
                CalendarRepository.findContactPhone(context, email)
            }
            if (first != null) map[e.id] = first
        }
        events = evts
        mapped = map
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir un rendez-vous") },
        text = {
            val evts = events
            if (evts == null) {
                Text("Chargement du calendrier…")
            } else if (evts.isEmpty()) {
                Text(
                    "Aucun événement dans les 30 prochains jours. " +
                    "Vérifie que PlanSMS a accès au calendrier (Réglages Android → Applications → PlanSMS → Calendrier)."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(380.dp)
                ) {
                    items(evts, key = { it.id }) { e ->
                        val contact = mapped[e.id]
                        Card(
                            onClick = {
                                val phone = contact?.first ?: (e.attendees.firstOrNull() ?: "")
                                val name = contact?.second ?: ""
                                onSelect(
                                    AgendaSelection(
                                        event = e,
                                        phone = phone,
                                        contactName = name
                                    )
                                )
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(e.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    fmtDate(e.start),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (e.location.isNotBlank()) {
                                    Text("📍 ${e.location}", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (contact != null) {
                                    Text(
                                        "✓ Contact trouvé : ${contact.first} (${contact.second})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                } else if (e.attendees.isNotEmpty()) {
                                    Text(
                                        "Participant : ${e.attendees.first()} (pas de numéro dans le répertoire — tu pourras l'ajouter)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

private fun fmtDate(millis: Long): String =
    SimpleDateFormat("EEE dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(millis))
