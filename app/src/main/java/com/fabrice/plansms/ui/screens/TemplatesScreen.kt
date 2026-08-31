package com.fabrice.plansms.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.fabrice.plansms.data.Template
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger

/**
 * Gestion des modèles : créer (bouton), modifier (touche la carte ou ✏️),
 * supprimer (🗑). Utilisable comme onglet, ou depuis Réglages via [onBack]
 * (affiche alors un en-tête avec flèche retour).
 */
@Composable
fun TemplatesScreen(
    vm: PlanSmsViewModel,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Template?>(null) }
    var showLibrary by remember { mutableStateOf(false) }

    if (onBack != null) {
        BackHandler { onBack() }
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Text("Modèles de messages", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
        }
        Button(
            onClick = { showCreate = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Nouveau modèle")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showLibrary = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("📚 Ajouter des modèles types (${com.fabrice.plansms.data.TemplateLibrary.all.size})") }
        Spacer(Modifier.height(12.dp))
        if (state.templates.isEmpty()) {
            Text(
                "Aucun modèle. Crée des modèles avec des variables : {{prenom}}, {{jour}}, {{date}}, {{heure}}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Touche un modèle pour le modifier.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.templates, key = { it.id }) { t ->
                    TemplateCard(
                        t = t,
                        onEdit = { editing = t },
                        onDelete = { vm.deleteTemplate(t) }
                    )
                }
            }
        }
    }

    if (showLibrary) {
        val existing = state.templates.map { it.name }.toSet()
        var selected by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("Modèles types") },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(380.dp)
                ) {
                    com.fabrice.plansms.data.TemplateLibrary.categories.forEach { category ->
                        item {
                            Text(
                                category.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(
                            com.fabrice.plansms.data.TemplateLibrary.all.filter { it.category == category },
                            key = { it.name }
                        ) { suggestion ->
                            val already = suggestion.name in existing
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !already) {
                                    selected = if (suggestion.name in selected) selected - suggestion.name
                                    else selected + suggestion.name
                                }
                            ) {
                                Checkbox(
                                    checked = already || suggestion.name in selected,
                                    enabled = !already,
                                    onCheckedChange = {
                                        selected = if (suggestion.name in selected) selected - suggestion.name
                                        else selected + suggestion.name
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        suggestion.name + if (already) " (déjà ajouté)" else "",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        suggestion.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.fabrice.plansms.data.TemplateLibrary.all
                            .filter { it.name in selected }
                            .forEach { vm.addTemplate(Template(name = it.name, body = it.body)) }
                        showLibrary = false
                    },
                    enabled = selected.isNotEmpty()
                ) { Text("Ajouter (${selected.size})") }
            },
            dismissButton = { TextButton(onClick = { showLibrary = false }) { Text("Fermer") } }
        )
    }

    if (showCreate) {
        TemplateDialog(
            title = "Nouveau modèle",
            onConfirm = { name, body ->
                vm.addTemplate(Template(name = name, body = body))
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    editing?.let { t ->
        TemplateDialog(
            title = "Modifier le modèle",
            initialName = t.name,
            initialBody = t.body,
            onConfirm = { name, body ->
                vm.updateTemplate(t.copy(name = name, body = body))
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun TemplateCard(t: Template, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium)
                Text(t.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger)
            }
        }
    }
}

@Composable
private fun TemplateDialog(
    title: String,
    initialName: String = "",
    initialBody: String = "",
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(initialBody) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message ({{prenom}}, {{jour}}, {{date}}, {{heure}}…)") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank() && body.isNotBlank()) onConfirm(name.trim(), body) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
