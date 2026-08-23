package com.fabrice.plansms.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fabrice.plansms.data.StoragePrefs
import com.fabrice.plansms.export.RecordingExporter
import com.fabrice.plansms.ui.theme.Danger
import com.fabrice.plansms.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Destination des enregistrements : téléphone, dossier (carte SD / OneDrive /
 * Dropbox / Nextcloud…), serveur FTP-FTPS, ou envoi automatique par email.
 */
@Composable
fun StorageScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var destination by remember { mutableStateOf(StoragePrefs.destination(context)) }
    var folderUri by remember { mutableStateOf(StoragePrefs.folderUri(context)) }
    var deleteAfter by remember { mutableStateOf(StoragePrefs.deleteAfterExport(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            StoragePrefs.setFolderUri(context, uri.toString())
            folderUri = uri.toString()
        }
    }

    BackHandler { onBack() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text("Stockage des enregistrements", style = MaterialTheme.typography.titleLarge)
        }

        Text("Destination", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                StoragePrefs.DEST_LOCAL to "Téléphone",
                StoragePrefs.DEST_FOLDER to "Dossier",
                StoragePrefs.DEST_FTP to "FTP",
                StoragePrefs.DEST_EMAIL to "Email"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = destination == value,
                    onClick = {
                        destination = value
                        StoragePrefs.setDestination(context, value)
                        testResult = ""
                    },
                    label = { Text(label) }
                )
            }
        }

        when (destination) {
            StoragePrefs.DEST_LOCAL -> {
                Text(
                    "Les enregistrements restent uniquement dans le dossier privé de l'app. " +
                        "Tu peux toujours en partager un à la main depuis la liste.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StoragePrefs.DEST_FOLDER -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Chaque enregistrement est copié dans le dossier choisi. Fonctionne avec la " +
                                "mémoire du téléphone, une carte SD, et les applis cloud qui exposent un " +
                                "dossier : OneDrive, Dropbox, Nextcloud, Synology, kDrive…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "⚠️ Google Drive n'expose pas de dossier inscriptible sous Android : pour Drive, " +
                                "utilise plutôt l'envoi par email, ou le partage manuel depuis la liste.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (folderUri.isBlank()) "Aucun dossier choisi."
                            else "Dossier : " + Uri.decode(folderUri.substringAfterLast("/")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (folderUri.isBlank()) MaterialTheme.colorScheme.error else Success
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (folderUri.isBlank()) "Choisir le dossier" else "Changer de dossier") }
                    }
                }
            }

            StoragePrefs.DEST_FTP -> {
                var host by remember { mutableStateOf(StoragePrefs.ftpHost(context)) }
                var port by remember { mutableStateOf(StoragePrefs.ftpPort(context).toString()) }
                var user by remember { mutableStateOf(StoragePrefs.ftpUser(context)) }
                var pass by remember { mutableStateOf(StoragePrefs.ftpPassword(context)) }
                var path by remember { mutableStateOf(StoragePrefs.ftpPath(context)) }
                var secure by remember { mutableStateOf(StoragePrefs.ftpSecure(context)) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it; StoragePrefs.setFtpHost(context, it) },
                            label = { Text("Serveur (ex. ftp.mondomaine.fr)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter(Char::isDigit).take(5); StoragePrefs.setFtpPort(context, port) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = path,
                                onValueChange = { path = it; StoragePrefs.setFtpPath(context, it) },
                                label = { Text("Dossier distant") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it; StoragePrefs.setFtpUser(context, it) },
                            label = { Text("Identifiant") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it; StoragePrefs.setFtpPassword(context, it) },
                            label = { Text("Mot de passe") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("FTPS (chiffré)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Recommandé : sans FTPS, identifiants et fichiers circulent en clair.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(checked = secure, onCheckedChange = {
                                secure = it
                                StoragePrefs.setFtpSecure(context, it)
                            })
                        }
                    }
                }
            }

            StoragePrefs.DEST_EMAIL -> {
                var mhost by remember { mutableStateOf(StoragePrefs.mailHost(context)) }
                var mport by remember { mutableStateOf(StoragePrefs.mailPort(context).toString()) }
                var muser by remember { mutableStateOf(StoragePrefs.mailUser(context)) }
                var mpass by remember { mutableStateOf(StoragePrefs.mailPassword(context)) }
                var mfrom by remember { mutableStateOf(StoragePrefs.mailFrom(context)) }
                var mto by remember { mutableStateOf(StoragePrefs.mailTo(context)) }
                var tls by remember { mutableStateOf(StoragePrefs.mailStartTls(context)) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "L'enregistrement part en pièce jointe dès la fin de l'enregistrement. " +
                                "Avec Gmail, crée un « mot de passe d'application » (la validation en deux " +
                                "étapes doit être active) : smtp.gmail.com, port 587, STARTTLS.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = mto,
                            onValueChange = { mto = it; StoragePrefs.setMailTo(context, it) },
                            label = { Text("Destinataire") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mhost,
                            onValueChange = { mhost = it; StoragePrefs.setMailHost(context, it) },
                            label = { Text("Serveur SMTP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = mport,
                                onValueChange = { mport = it.filter(Char::isDigit).take(5); StoragePrefs.setMailPort(context, mport) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = mfrom,
                                onValueChange = { mfrom = it; StoragePrefs.setMailFrom(context, it) },
                                label = { Text("Expéditeur") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        OutlinedTextField(
                            value = muser,
                            onValueChange = { muser = it; StoragePrefs.setMailUser(context, it) },
                            label = { Text("Identifiant SMTP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mpass,
                            onValueChange = { mpass = it; StoragePrefs.setMailPassword(context, it) },
                            label = { Text("Mot de passe (chiffré sur l'appareil)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("STARTTLS (port 587)", style = MaterialTheme.typography.titleMedium)
                                Text("Désactive pour du SSL direct (port 465).", style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = tls, onCheckedChange = {
                                tls = it
                                StoragePrefs.setMailStartTls(context, it)
                            })
                        }
                    }
                }
            }
        }

        if (destination != StoragePrefs.DEST_LOCAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Effacer du téléphone après export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Le fichier n'est supprimé que si l'export a réussi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(checked = deleteAfter, onCheckedChange = {
                    deleteAfter = it
                    StoragePrefs.setDeleteAfterExport(context, it)
                })
            }

            Button(
                onClick = {
                    testing = true
                    testResult = ""
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { RecordingExporter.test(context) }
                        testOk = res.ok
                        testResult = if (res.ok) "✅ ${res.message}" else "❌ ${res.message}"
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (testing) "Test en cours…" else "Tester la destination") }

            if (testResult.isNotEmpty()) {
                Text(
                    testResult,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (testOk) Success else Danger
                )
            }

            Text(
                "Les mots de passe sont chiffrés (AES-256) avec une clé stockée dans le coffre matériel " +
                    "du téléphone. Ils ne sont jamais envoyés ailleurs qu'au serveur que tu as configuré.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}
