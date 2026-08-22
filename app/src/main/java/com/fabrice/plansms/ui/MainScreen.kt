package com.fabrice.plansms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.plansms.ui.screens.EditScreen
import com.fabrice.plansms.ui.screens.GroupsScreen
import com.fabrice.plansms.ui.screens.HelpScreen
import com.fabrice.plansms.ui.screens.HomeScreen
import com.fabrice.plansms.ui.screens.JournalScreen
import com.fabrice.plansms.ui.screens.PinScreen
import com.fabrice.plansms.ui.screens.SettingsScreen
import com.fabrice.plansms.ui.screens.TemplatesScreen
import com.fabrice.plansms.ui.theme.NightBlue

private data class Tab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: PlanSmsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.locked) {
        PinScreen(vm)
        return
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableLongStateOf(-1L) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val tabs = listOf(
        Tab("Accueil", Icons.Filled.Home),
        Tab("Groupes", Icons.Filled.Person),
        Tab("Modèles", Icons.Filled.Email),
        Tab("Journal", Icons.AutoMirrored.Filled.List),
        Tab("Réglages", Icons.Filled.Settings)
    )

    val onSubScreen = creating || editingId >= 0 || showHelp
    val closeSubScreen = { creating = false; editingId = -1L; showHelp = false }

    // Bouton retour du téléphone : ferme l'écran secondaire au lieu de quitter l'app
    BackHandler(enabled = onSubScreen) { closeSubScreen() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(
                    when {
                        editingId >= 0 -> "Modifier le message"
                        creating -> "Nouveau message"
                        showHelp -> "Aide"
                        else -> tabs[selectedTab].label
                    }
                ) },
                navigationIcon = {
                    if (onSubScreen) {
                        IconButton(onClick = closeSubScreen) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Retour",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (!creating && editingId < 0 && !showHelp) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        when {
            creating || editingId >= 0 -> EditScreen(
                vm = vm,
                editingId = editingId,
                onClose = { creating = false; editingId = -1 },
                modifier = Modifier.padding(padding)
            )
            showHelp -> HelpScreen(
                vm = vm,
                onClose = { showHelp = false },
                modifier = Modifier.padding(padding)
            )
            selectedTab == 0 -> HomeScreen(
                vm = vm,
                onNew = { creating = true },
                onEdit = { id -> editingId = id },
                modifier = Modifier.padding(padding)
            )
            selectedTab == 1 -> GroupsScreen(vm, Modifier.padding(padding))
            selectedTab == 2 -> TemplatesScreen(vm, modifier = Modifier.padding(padding))
            selectedTab == 3 -> JournalScreen(vm, Modifier.padding(padding))
            else -> SettingsScreen(
                vm = vm,
                onShowHelp = { showHelp = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
}
