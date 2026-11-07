package ru.dkd.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.dkd.app.data.repository.CitizenProfile
import ru.dkd.app.data.repository.CitizenProfileRepository

internal data class ProfileUiState(
    val loading: Boolean = true,
    val savingPreference: Boolean = false,
    val profile: CitizenProfile? = null,
    val error: String? = null
)

internal class ProfileViewModel : ViewModel() {
    private val repository = CitizenProfileRepository()
    private val mutableState = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = ProfileUiState(loading = false, profile = result.profile, error = result.error)
        }
    }

    fun setNewsNotifications(token: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = mutableState.value
            val profile = current.profile ?: return@launch
            mutableState.value = current.copy(savingPreference = true, error = null)
            val error = repository.setNewsNotifications(token, enabled)
            mutableState.value = mutableState.value.copy(
                savingPreference = false,
                profile = if (error == null) profile.copy(newsNotificationsEnabled = enabled) else profile,
                error = error
            )
        }
    }
}
