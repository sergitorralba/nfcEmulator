package com.sergitorralba.nfcemulator.domain.model

sealed interface ScenarioResult {
    data class Success(val message: String, val details: Map<String, String> = emptyMap()) : ScenarioResult
    data class TerminalDecline(val reason: String) : ScenarioResult
    data class CardDecline(val reason: String) : ScenarioResult
    data class RfTorn(val message: String) : ScenarioResult
    data class Timeout(val message: String) : ScenarioResult
    data class Error(val error: String, val throwable: Throwable? = null) : ScenarioResult
}
