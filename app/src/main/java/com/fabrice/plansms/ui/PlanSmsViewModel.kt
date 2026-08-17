package com.fabrice.plansms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.plansms.data.ScheduledMessage
import com.fabrice.plansms.data.SendLog
import com.fabrice.plansms.data.SmsRepository
import com.fabrice.plansms.data.SmsStatus
import com.fabrice.plansms.data.Template
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
    val locked: Boolean = false,
    val pinEnabled: Boolean = false,
    val exportText: String = ""
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

    fun countByStatus(status: SmsStatus): Int = _state.value.messages.count { it.status == status }
}
