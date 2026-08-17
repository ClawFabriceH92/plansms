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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fabrice.plansms.data.Channel
import com.fabrice.plansms.data.RepeatRule
import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Success
import java.util.Calendar

private data class Example(
    val title: String,
    val description: String,
    val phone: String,
    val text: String,
    val hour: Int,
    val minute: Int,
    val repeat: RepeatRule,
    val channel: Channel = Channel.SMS
)

private val examples = listOf(
    Example(
        "Anniversaire",
        "Souhaite un anniversaire chaque année à la même heure",
        "0612345678",
        "Joyeux anniversaire {{prenom}} ! 🎂",
        9, 0, RepeatRule.MONTHLY
    ),
    Example(
        "Relance client (facture)",
        "Rappel de paiement à J-2 avant l'échéance — récurrent tous les mois",
        "0698765432",
        "Bonjour {{prenom}}, rappel : votre facture arrive à échéance le {{date}}. Merci de votre règlement.",
        10, 0, RepeatRule.MONTHLY
    ),
    Example(
        "RDV client (jours ouvrés)",
        "Confirmation de rendez-vous la veille, du lundi au vendredi",
        "0698765432",
        "Bonjour {{prenom}}, confirmation de notre rendez-vous du {{date}} à {{heure}}. À très vite !",
        17, 30, RepeatRule.WEEKDAYS
    ),
    Example(
        "Point équipe (quotidien)",
        "Message quotidien à l'équipe à heure fixe",
        "0612345678",
        "Bonjour à tous, pensez à remonter vos points du jour avant 18h.",
        8, 30, RepeatRule.DAILY
    ),
    Example(
        "WhatsApp programmé",
        "Ouvre WhatsApp avec le message pré-rempli (envoi manuel final)",
        "0612345678",
        "Bonjour, ce message a été programmé avec PlanSMS 😊",
        18, 0, RepeatRule.ONCE, Channel.WHATSAPP
    )
)

@Composable
fun HelpScreen(vm: PlanSmsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        TextButton(onClick = onClose) { Text("← Retour") }
        Text("Aide & exemples", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Un SMS programmé part à la date et l'heure choisies. " +
            "Les variables {{prenom}}, {{nom}}, {{date}}, {{heure}} sont remplacées à l'envoi. " +
            "La plage d'envoi interdite décale le message si l'heure tombe dans la plage (ex. 22h→7h). " +
            "Canal WhatsApp : notification à l'heure dite, appui pour ouvrir WhatsApp pré-rempli.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(examples) { ex ->
                ExampleCard(ex, onCreate = {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, ex.hour)
                        set(Calendar.MINUTE, ex.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    vm.addMessage(
                        ScheduledMessage(
                            phone = ex.phone,
                            text = ex.text,
                            targetDate = cal.timeInMillis,
                            hourOfDay = ex.hour,
                            minuteOfHour = ex.minute,
                            repeatRule = ex.repeat,
                            channel = ex.channel
                        )
                    )
                })
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ExampleCard(ex: Example, onCreate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(ex.title, style = MaterialTheme.typography.titleMedium)
            Text(ex.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "\"${ex.text}\" → ${ex.phone} à ${ex.hour.toString().padStart(2, '0')}:${ex.minute.toString().padStart(2, '0')} " +
                "(${ex.repeat.name}${if (ex.channel == Channel.WHATSAPP) " · WhatsApp" else ""})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCreate) {
                Text("Créer cet exemple (modifiable ensuite)")
            }
        }
    }
}
