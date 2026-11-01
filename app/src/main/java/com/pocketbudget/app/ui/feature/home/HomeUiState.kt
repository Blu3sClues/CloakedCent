package com.pocketbudget.app.ui.feature.home

import com.pocketbudget.app.domain.model.StorageStatus
import com.pocketbudget.app.domain.repository.StorageUnavailableException

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val storage: StorageStatus) : HomeUiState
    data class Error(val reason: StorageUnavailableException.Reason) : HomeUiState
}
