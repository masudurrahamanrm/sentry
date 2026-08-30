package com.example.kinetix.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

sealed interface KinetixUiState {
    object Loading : KinetixUiState
    data class Success(
        val controllerName: String,
        val companionCount: Int,
        val systemStatus: String,
        val logs: List<String>
    ) : KinetixUiState
    data class Error(val message: String) : KinetixUiState
}

class HomeScreenViewModel : ViewModel() {
    val uiState: StateFlow<KinetixUiState> = flow {
        emit(
            KinetixUiState.Success(
                controllerName = "Kinetix Primary",
                companionCount = 0,
                systemStatus = "Ready for Pairing",
                logs = listOf(
                    "System initialized",
                    "Awaiting Sentry agent connection..."
                )
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = KinetixUiState.Loading
    )
}
