package com.fabrice.plansms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.fabrice.plansms.scheduler.UpdateChecker
import com.fabrice.plansms.ui.MainScreen
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.PlanSmsTheme
import com.fabrice.plansms.util.AppLogger

class MainActivity : ComponentActivity() {

    private val vm: PlanSmsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AppLogger.i("MainActivity", "Réponse permissions : $it")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        if (UpdateChecker.isAutoUpdateEnabled(this)) {
            vm.checkForUpdate(doDownloadIfAvailable = true)
        }
        // Rappel 15h des RDV du lendemain (jours ouvrés)
        com.fabrice.plansms.scheduler.RdvReminder.schedule(this)
        val openRdv = intent?.getBooleanExtra(com.fabrice.plansms.scheduler.RdvReminder.EXTRA_OPEN_RDV, false) == true
        setContent {
            PlanSmsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(vm, openRdvOnStart = openRdv)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-scan à chaque ouverture / retour dans l'app : un SMS ou un rappel
        // survenu entre-temps doit être pris en compte immédiatement.
        if (com.fabrice.plansms.data.CallLogRepository.hasPermission(this)) {
            vm.loadCallLog()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
