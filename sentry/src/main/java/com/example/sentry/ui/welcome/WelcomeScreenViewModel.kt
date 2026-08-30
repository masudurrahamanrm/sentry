package com.example.sentry.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

sealed interface SentryUiState {
    object Loading : SentryUiState
    data class Success(
        val deviceName: String,
        val agentStatus: String,
        val connectionKey: String,
        val diagnosticLogs: List<String>
    ) : SentryUiState
    data class Error(val message: String) : SentryUiState
}

class WelcomeScreenViewModel : ViewModel() {
    val uiState: StateFlow<SentryUiState> = flow {
        emit(
            SentryUiState.Success(
                deviceName = "Secondary Agent Device",
                agentStatus = "Not Connected",
                connectionKey = "SRY-982-105",
                diagnosticLogs = listOf(
                    "Sentry daemon active",
                    "Awaiting secure pairing token from controller..."
                )
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SentryUiState.Loading
    )
}
