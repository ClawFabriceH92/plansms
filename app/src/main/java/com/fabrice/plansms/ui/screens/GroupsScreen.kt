package com.fabrice.plansms.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.data.ContactGroup
import com.fabrice.plansms.data.GroupMember
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.Danger

@Composable
fun GroupsScreen(vm: PlanSmsViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<ContactGroup?>(null) }

    if (selectedGroup != null) {
        GroupDetailScreen(vm, selectedGroup!!, onBack = { selectedGroup = null })
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        Button(
            onClick = { showCreate = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Nouveau groupe")
        }
        Spacer(Modifier.height(12.dp))
        if (state.groups.isEmpty()) {
            Text(
                "Crée des groupes de contacts (clients, famille, amis…) pour envoyer un message programmé à tous les membres.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.groups, key = { it.id }) { g ->
                    val count = vm.groupMembers(g.id).size
                    Card(
                        onClick = { selectedGroup = g },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleMedium)
                                Text("$count membre(s)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.deleteGroup(g.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = Danger)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        GroupCreateDialog(
            onConfirm = { name ->
                vm.addGroup(name)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
}

@Composable
private fun GroupDetailScreen(vm: PlanSmsViewModel, group: ContactGroup, onBack: () -> Unit) {
    val members = vm.groupMembers(group.id)
    var showAdd by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        TextButton(onClick = onBack) { Text("← Retour") }
        Text(group.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("${members.size} membre(s)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Ajouter un membre")
        }
        Spacer(Modifier.height(12.dp))
        if (members.isEmpty()) {
            Text("Aucun membre. Ajoute un numéro (nom optionnel).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(members, key = { it.phone }) { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (m.name.isNotBlank()) Text(m.name, style = MaterialTheme.typography.titleMedium)
                                Text(m.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.removeMember(group.id, m.phone) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Retirer", tint = Danger)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        MemberAddDialog(
            onConfirm = { phone, name ->
                vm.addMembers(group.id, listOf(GroupMember(groupId = group.id, phone = phone, name = name)))
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
}

@Composable
private fun GroupCreateDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau groupe") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du groupe (clients, famille…)") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun MemberAddDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter un membre") },
        text = {
            Column {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numéro") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (optionnel)") },
                    singleLine = true
                )
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (phone.replace(Regex("[^0-9+]"), "").length < 6) error = "Numéro invalide"
                else onConfirm(phone.trim(), name.trim())
            }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
