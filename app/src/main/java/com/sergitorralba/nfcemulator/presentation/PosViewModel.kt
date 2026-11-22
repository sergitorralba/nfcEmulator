package com.sergitorralba.nfcemulator.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sergitorralba.nfcemulator.data.nfc.NfcTerminalManager
import com.sergitorralba.nfcemulator.data.nfc.TerminalEvent
import com.sergitorralba.nfcemulator.domain.model.ApduLogEntry
import com.sergitorralba.nfcemulator.domain.model.PosScenario
import com.sergitorralba.nfcemulator.domain.model.ScenarioResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PosUiState {
    data object ScenarioSelection : PosUiState
    data class WaitingForTap(val scenario: PosScenario) : PosUiState
    data class Processing(val scenario: PosScenario, val progressMessage: String) : PosUiState
    data class Completed(
        val scenario: PosScenario,
        val result: ScenarioResult,
        val logs: List<ApduLogEntry>
    ) : PosUiState
}

class PosViewModel : ViewModel() {

    private var terminalManager: NfcTerminalManager? = null

    private val _uiState = MutableStateFlow<PosUiState>(PosUiState.ScenarioSelection)
    val uiState: StateFlow<PosUiState> = _uiState.asStateFlow()

    private val _apduLogs = MutableStateFlow<List<ApduLogEntry>>(emptyList())
    val apduLogs: StateFlow<List<ApduLogEntry>> = _apduLogs.asStateFlow()

    fun bindTerminalManager(manager: NfcTerminalManager) {
        if (this.terminalManager == null) {
            this.terminalManager = manager
            viewModelScope.launch {
                manager.events.collect { event ->
                    handleTerminalEvent(event)
                }
            }
        }
    }

    fun selectScenario(scenario: PosScenario) {
        _apduLogs.value = emptyList()
        _uiState.value = PosUiState.WaitingForTap(scenario)
        terminalManager?.startReaderMode(scenario)
    }

    fun restartCurrentScenario() {
        val currentScenario = when (val state = _uiState.value) {
            is PosUiState.WaitingForTap -> state.scenario
            is PosUiState.Processing -> state.scenario
            is PosUiState.Completed -> state.scenario
            else -> null
        } ?: return

        _apduLogs.value = emptyList()
        _uiState.value = PosUiState.WaitingForTap(currentScenario)
        terminalManager?.stopReaderMode()
        terminalManager?.startReaderMode(currentScenario)
    }

    fun cancelAndReturnToMenu() {
        terminalManager?.stopReaderMode()
        _uiState.value = PosUiState.ScenarioSelection
    }

    private fun handleTerminalEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Processing -> {
                val currentScenario = when (val state = _uiState.value) {
                    is PosUiState.WaitingForTap -> state.scenario
                    is PosUiState.Processing -> state.scenario
                    else -> null
                }
                if (currentScenario != null) {
                    _uiState.value = PosUiState.Processing(currentScenario, event.progressText)
                }
            }
            is TerminalEvent.Completed -> {
                val currentScenario = when (val state = _uiState.value) {
                    is PosUiState.WaitingForTap -> state.scenario
                    is PosUiState.Processing -> state.scenario
                    is PosUiState.Completed -> state.scenario
                    else -> null
                }
                if (currentScenario != null) {
                    terminalManager?.stopReaderMode()
                    _uiState.value = PosUiState.Completed(
                        scenario = currentScenario,
                        result = event.result,
                        logs = _apduLogs.value
                    )
                }
            }
            is TerminalEvent.LogReceived -> {
                _apduLogs.value = _apduLogs.value + event.log
            }
        }
    }

    fun onPause() {
        terminalManager?.stopReaderMode()
    }

    fun onResume() {
        val state = _uiState.value
        if (state is PosUiState.WaitingForTap) {
            terminalManager?.startReaderMode(state.scenario)
        }
    }

    override fun onCleared() {
        super.onCleared()
        terminalManager?.stopReaderMode()
    }
}
