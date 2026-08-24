package com.fabrice.plansms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.plansms.data.AutoReplyRule
import com.fabrice.plansms.data.CalendarInfo
import com.fabrice.plansms.data.CallEntry
import com.fabrice.plansms.data.CallLogRepository
import com.fabrice.plansms.data.ContactGroup
import com.fabrice.plansms.data.GroupMember
import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.SmsRepository
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.data.Template
import com.fabrice.plansms.scheduler.UpdateChecker
import com.fabrice.plansms.scheduler.UpdateDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanSmsUiState(
    val messages: List<ScheduledMessage> = emptyList(),
    val templates: List<Template> = emptyList(),
    val logs: List<SendLog> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val members: Map<Long, List<GroupMember>> = emptyMap(),
    val autoReply: AutoReplyRule? = null,
    val calendars: List<CalendarInfo>? = null,  // null = pas encore chargé, vide = aucun
    val calendarsLoaded: Boolean = false,
    val calendarCounts: Map<Long, Int> = emptyMap(),
    val callLog: List<CallEntry> = emptyList(),
    val callLogLoaded: Boolean = false,
    val smsScanned: Int = -1,        // SMS reçus analysés (-1 = permission SMS absente)
    val smsRepliesFound: Int = 0,
    val tomorrowRdv: List<com.fabrice.plansms.data.TomorrowRdv>? = null,  // null = pas encore chargé
    val tomorrowRdvNoEmail: Int = 0,
    val tomorrowRdvTarget: Long = 0,   // minuit du jour cible (demain, ou lundi si vendredi/week-end)
    val recordings: List<com.fabrice.plansms.data.VoiceRecording> = emptyList(),
    val bulkSending: Boolean = false,
    val bulkProgress: String = "",      // "2/5" pendant un envoi groupé
    val bulkReport: String = "",        // rapport final "4 envoyés · 1 échec"
    val locked: Boolean = false,
    val pinEnabled: Boolean = false,
    val exportText: String = "",
    val updateInfo: String = "",        // "dispo" / "a_jour" / "erreur" / "telechargement" / ""
    val updateVersion: String = "",
    val updateError: String = "",
)

class PlanSmsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SmsRepository(app)

    private val _state = MutableStateFlow(PlanSmsUiState())
    val state: StateFlow<PlanSmsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeMessages().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect {
                _state.value = _state.value.copy(messages = it)
            }
        }
        viewModelScope.launch {
            repo.observeTemplates().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect {
                _state.value = _state.value.copy(templates = it)
            }
        }
        viewModelScope.launch {
            repo.observeLogs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect {
                _state.value = _state.value.copy(logs = it)
            }
        }
        viewModelScope.launch {
            repo.observeGroups().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect { groups ->
                _state.value = _state.value.copy(groups = groups)
                groups.forEach { g ->
                    viewModelScope.launch {
                        repo.observeMembers(g.id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect { mem ->
                            _state.value = _state.value.copy(members = _state.value.members + (g.id to mem))
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            repo.observeAutoReply().stateIn(viewModelScope, SharingStarted.Eagerly, null).collect {
                _state.value = _state.value.copy(autoReply = it)
            }
        }
        viewModelScope.launch {
            repo.observeRecordings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList()).collect {
                _state.value = _state.value.copy(recordings = it)
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                pinEnabled = com.fabrice.plansms.security.PinManager.isEnabled(app),
                locked = com.fabrice.plansms.security.PinManager.isEnabled(app)
            )
        }
    }

    // --- Messages ---
    fun addMessage(msg: ScheduledMessage) = viewModelScope.launch { repo.addMessage(msg) }
    fun updateMessage(msg: ScheduledMessage) = viewModelScope.launch { repo.updateMessage(msg) }
    fun deleteMessage(msg: ScheduledMessage) = viewModelScope.launch { repo.deleteMessage(msg) }

    // --- Templates ---
    fun addTemplate(t: Template) = viewModelScope.launch { repo.addTemplate(t) }
    fun updateTemplate(t: Template) = viewModelScope.launch { repo.updateTemplate(t) }
    fun deleteTemplate(t: Template) = viewModelScope.launch { repo.deleteTemplate(t) }

    fun clearLogs() = viewModelScope.launch { repo.clearLogs() }

    // --- Journal d'appels → SMS groupé ---
    fun loadCallLog() {
        viewModelScope.launch {
            val scan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val raw = CallLogRepository.readRecentCalls(getApplication())
                CallLogRepository.markSmsReplies(getApplication(), raw)
            }
            _state.value = _state.value.copy(
                callLog = scan.calls,
                callLogLoaded = true,
                smsScanned = scan.smsScanned,
                smsRepliesFound = scan.repliesFound
            )
        }
    }

    /** Envoi groupé depuis le journal d'appels. */
    fun sendBulkSms(recipients: List<CallEntry>, text: String) =
        sendBulk(recipients.map { Triple(it.number, it.name, System.currentTimeMillis()) }, text)

    // --- RDV de demain → SMS de confirmation ---
    fun loadTomorrowRdv() {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fabrice.plansms.data.CalendarRepository.tomorrowMeetings(getApplication())
            }
            _state.value = _state.value.copy(
                tomorrowRdv = result.withEmail,
                tomorrowRdvNoEmail = result.withoutEmailCount,
                tomorrowRdvTarget = result.targetStart
            )
        }
    }

    fun rdvConfirmMessage(): String =
        com.fabrice.plansms.data.CalendarPrefs.confirmMessage(getApplication())

    /**
     * Envoie la confirmation à chaque destinataire (numéro, nom, heure du RDV pour {{date}}/{{heure}})
     * et mémorise le texte comme message par défaut.
     */
    fun sendRdvConfirmations(recipients: List<Triple<String, String, Long>>, text: String) {
        com.fabrice.plansms.data.CalendarPrefs.setConfirmMessage(getApplication(), text)
        sendBulk(recipients, text)
    }

    /** Fusion d'infos : ajoute l'email au contact rapproché puis recharge les RDV. */
    fun attachEmailToContact(contactId: Long, email: String) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fabrice.plansms.data.ContactsHelper.addEmailToContact(getApplication(), contactId, email)
            }
            loadTomorrowRdv()
        }
    }

    /** Envoi groupé générique : (numéro, nom, date pour les variables), 3 s entre envois. */
    private fun sendBulk(recipients: List<Triple<String, String, Long>>, text: String) {
        if (recipients.isEmpty() || text.isBlank() || _state.value.bulkSending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(bulkSending = true, bulkReport = "", bulkProgress = "0/${recipients.size}")
            var ok = 0
            var ko = 0
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recipients.forEachIndexed { i, (number, name, dateMillis) ->
                    // {{prenom}}/{{nom}} → nom du destinataire, {{date}}/{{heure}} → date fournie
                    val resolved = com.fabrice.plansms.logic.SmsRules.resolveTemplate(text, name, dateMillis)
                    val err = com.fabrice.plansms.scheduler.SmsSender.send(getApplication(), number, resolved)
                    repo.addLog(
                        com.fabrice.plansms.data.SendLog(
                            scheduledId = 0,
                            phone = if (name.isBlank()) number else "$name ($number)",
                            textPreview = resolved.take(80),
                            status = if (err == null) "SENT" else "FAILED",
                            error = err ?: ""
                        )
                    )
                    if (err == null) ok++ else ko++
                    _state.value = _state.value.copy(bulkProgress = "${i + 1}/${recipients.size}")
                    if (i < recipients.size - 1) kotlinx.coroutines.delay(3000)
                }
            }
            val report = buildString {
                append("$ok envoyé${if (ok > 1) "s" else ""}")
                if (ko > 0) append(" · $ko échec${if (ko > 1) "s" else ""}")
            }
            _state.value = _state.value.copy(bulkSending = false, bulkReport = report, bulkProgress = "")
        }
    }

    // --- Enregistrements vocaux ---
    fun renameRecording(r: com.fabrice.plansms.data.VoiceRecording, label: String) =
        viewModelScope.launch { repo.renameRecording(r, label.trim()) }

    fun deleteRecording(r: com.fabrice.plansms.data.VoiceRecording) =
        viewModelScope.launch { repo.deleteRecording(r) }

    fun exportRecording(r: com.fabrice.plansms.data.VoiceRecording) =
        viewModelScope.launch { repo.exportRecording(r) }

    fun clearBulkReport() {
        _state.value = _state.value.copy(bulkReport = "", bulkProgress = "")
    }

    // --- Groupes ---
    fun addGroup(name: String) = viewModelScope.launch { repo.addGroup(name) }
    fun deleteGroup(id: Long) = viewModelScope.launch { repo.deleteGroup(id) }
    fun addMembers(groupId: Long, members: List<GroupMember>) = viewModelScope.launch {
        repo.addMembers(groupId, members)
    }
    fun removeMember(groupId: Long, phone: String) = viewModelScope.launch {
        repo.removeMember(groupId, phone)
    }
    fun groupMembers(groupId: Long): List<GroupMember> = _state.value.members[groupId] ?: emptyList()

    // --- Auto-réponse ---
    fun saveAutoReply(rule: AutoReplyRule) = viewModelScope.launch { repo.saveAutoReply(rule) }

    // --- Calendrier (diagnostic) ---
    fun loadCalendars() {
        viewModelScope.launch {
            val (list, counts) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val cals = com.fabrice.plansms.data.CalendarRepository.readCalendars(getApplication())
                val cnts = com.fabrice.plansms.data.CalendarRepository
                    .eventCountsByCalendar(getApplication(), 30)
                    .associate { it.first.id to it.second }
                cals to cnts
            }
            _state.value = _state.value.copy(calendars = list, calendarsLoaded = true, calendarCounts = counts)
        }
    }

    // --- PIN ---
    fun enablePin(pin: String) {
        com.fabrice.plansms.security.PinManager.enable(getApplication(), pin)
        _state.value = _state.value.copy(pinEnabled = true, locked = false)
    }

    fun disablePin() {
        com.fabrice.plansms.security.PinManager.disable(getApplication())
        _state.value = _state.value.copy(pinEnabled = false, locked = false)
    }

    fun verifyPin(pin: String): Boolean {
        val ok = com.fabrice.plansms.security.PinManager.verify(getApplication(), pin)
        if (ok) _state.value = _state.value.copy(locked = false)
        return ok
    }

    /** Déverrouillage réussi par empreinte / visage. */
    fun unlockBiometric() {
        _state.value = _state.value.copy(locked = false)
    }

    fun lock() {
        if (_state.value.pinEnabled) _state.value = _state.value.copy(locked = true)
    }

    // --- Export / Import ---
    fun export() {
        val json = com.fabrice.plansms.logic.JsonBackup.export(_state.value.messages, _state.value.templates)
        _state.value = _state.value.copy(exportText = json)
    }

    fun import(json: String): Boolean {
        val backup = com.fabrice.plansms.logic.JsonBackup.parse(json) ?: return false
        viewModelScope.launch {
            repo.importBackup(backup)
        }
        return true
    }

    // --- Mise à jour ---
    fun checkForUpdate(doDownloadIfAvailable: Boolean = false) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                UpdateChecker.checkResult(getApplication(), UpdateChecker.versionName(getApplication()))
            }
            when (result) {
                is UpdateChecker.CheckResult.Update -> {
                    _state.value = _state.value.copy(
                        updateInfo = "dispo",
                        updateVersion = result.info.version,
                        updateError = ""
                    )
                    if (doDownloadIfAvailable) {
                        val ok = UpdateDownloader.start(getApplication(), result.info.apkUrl)
                        if (ok) _state.value = _state.value.copy(updateInfo = "telechargement")
                    }
                }
                is UpdateChecker.CheckResult.Current -> {
                    _state.value = _state.value.copy(updateInfo = "a_jour", updateVersion = "", updateError = "")
                }
                is UpdateChecker.CheckResult.Error -> {
                    _state.value = _state.value.copy(updateInfo = "erreur", updateVersion = "", updateError = result.message)
                }
            }
        }
    }

    fun downloadUpdate() {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                UpdateChecker.checkResult(getApplication(), UpdateChecker.versionName(getApplication()))
            }
            when (result) {
                is UpdateChecker.CheckResult.Update -> {
                    val ok = UpdateDownloader.start(getApplication(), result.info.apkUrl)
                    _state.value = if (ok) {
                        _state.value.copy(updateInfo = "telechargement", updateError = "")
                    } else {
                        _state.value.copy(updateInfo = "erreur", updateError = "Permission d'installation refusée — voir la notification")
                    }
                }
                is UpdateChecker.CheckResult.Current -> {
                    _state.value = _state.value.copy(updateInfo = "a_jour", updateVersion = "", updateError = "")
                }
                is UpdateChecker.CheckResult.Error -> {
                    _state.value = _state.value.copy(updateInfo = "erreur", updateVersion = "", updateError = result.message)
                }
            }
        }
    }

    fun isAutoUpdateEnabled(): Boolean = UpdateChecker.isAutoUpdateEnabled(getApplication())
    fun setAutoUpdateEnabled(on: Boolean) = UpdateChecker.setAutoUpdateEnabled(getApplication(), on)

    fun canInstallUnknownApps(): Boolean = com.fabrice.plansms.scheduler.UpdateDownloader.canRequestInstalls(getApplication())

    fun countByStatus(status: SmsStatus): Int = _state.value.messages.count { it.status == status }
}
