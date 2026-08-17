package com.fabrice.plansms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.plansms.data.AutoReplyRule
import com.fabrice.plansms.data.CalendarInfo
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
    val locked: Boolean = false,
    val pinEnabled: Boolean = false,
    val exportText: String = "",
    val updateInfo: String = "",        // "dispo" / "a_jour" / "erreur" / ""
    val updateVersion: String = ""
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
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fabrice.plansms.data.CalendarRepository.readCalendars(getApplication())
            }
            _state.value = _state.value.copy(calendars = list, calendarsLoaded = true)
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
    fun checkForUpdate() {
        viewModelScope.launch {
            val info = UpdateChecker.check(getApplication(), UpdateChecker.versionName(getApplication()))
            _state.value = if (info != null) {
                _state.value.copy(updateInfo = "dispo", updateVersion = info.version)
            } else {
                _state.value.copy(updateInfo = "a_jour")
            }
        }
    }

    fun downloadUpdate() {
        val info = runCatching {
            UpdateChecker.check(getApplication(), UpdateChecker.versionName(getApplication()))
        }.getOrNull()
        if (info != null) {
            UpdateDownloader.start(getApplication(), info.apkUrl)
            _state.value = _state.value.copy(updateInfo = "telechargement")
        }
    }

    fun isAutoUpdateEnabled(): Boolean = UpdateChecker.isAutoUpdateEnabled(getApplication())
    fun setAutoUpdateEnabled(on: Boolean) = UpdateChecker.setAutoUpdateEnabled(getApplication(), on)

    fun canInstallUnknownApps(): Boolean = com.fabrice.plansms.scheduler.UpdateDownloader.canRequestInstalls(getApplication())

    fun countByStatus(status: SmsStatus): Int = _state.value.messages.count { it.status == status }
}
