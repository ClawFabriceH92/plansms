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
import com.fabrice.plansms.scheduler.UpdateDownloader
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
        checkUpdateAtLaunch()
        setContent {
            PlanSmsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(vm)
                }
            }
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

    /** MAJ auto : vérifie au lancement si activé, télécharge et installe. */
    private fun checkUpdateAtLaunch() {
        if (!UpdateChecker.isAutoUpdateEnabled(this)) return
        val info = UpdateChecker.check(this, UpdateChecker.versionName(this))
        if (info != null) {
            UpdateDownloader.start(this, info.apkUrl)
            AppLogger.i("MainActivity", "MAJ auto : téléchargement v${info.version}")
        }
    }
}
