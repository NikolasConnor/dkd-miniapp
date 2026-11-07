package ru.dkd.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.dkd.app.data.repository.CitizenProfile
import ru.dkd.app.data.repository.CitizenProfileRepository
import ru.dkd.app.core.network.apiCall
import org.json.JSONObject

internal data class ProfileUiState(
    val loading: Boolean = true,
    val profile: CitizenProfile? = null,
    val error: String? = null,
    val savingPreference: Boolean = false,
    val savingProfile: Boolean = false
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

    fun updateIdentity(token: String, fullName: String, avatarEmoji: String) {
        viewModelScope.launch {
            val current = mutableState.value.profile ?: return@launch
            if (mutableState.value.savingProfile) return@launch
            val name = fullName.trim()
            if (name.length !in 2..120) {
                mutableState.value = mutableState.value.copy(error = "ФИО должно содержать от 2 до 120 символов.")
                return@launch
            }
            mutableState.value = mutableState.value.copy(savingProfile = true, error = null)
            val nameResult = apiCall(token, "PATCH", "/api/profile", JSONObject().put("full_name", name))
            if (nameResult.error != null) {
                mutableState.value = mutableState.value.copy(savingProfile = false, error = nameResult.error)
                return@launch
            }
            val avatarResult = apiCall(token, "PATCH", "/api/profile/avatar", JSONObject().put("avatar_emoji", avatarEmoji))
            if (avatarResult.error != null) {
                val refreshed = repository.load(token)
                mutableState.value = mutableState.value.copy(
                    savingProfile = false,
                    profile = refreshed.profile,
                    error = avatarResult.error
                )
                return@launch
            }
            val refreshed = repository.load(token)
            mutableState.value = mutableState.value.copy(
                savingProfile = false,
                profile = refreshed.profile ?: current.copy(fullName = name, avatarEmoji = avatarEmoji),
                error = refreshed.error
            )
        }
    }

    fun setNewsNotifications(token: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = mutableState.value.profile ?: return@launch
            if (mutableState.value.savingPreference) return@launch
            mutableState.value = mutableState.value.copy(savingPreference = true, error = null)
            val result = apiCall(
                token, "PATCH", "/api/profile/news-preferences",
                JSONObject().put("enabled", enabled)
            )
            if (result.error != null) {
                mutableState.value = mutableState.value.copy(savingPreference = false, error = result.error)
            } else {
                mutableState.value = mutableState.value.copy(
                    savingPreference = false,
                    profile = current.copy(newsNotificationsEnabled = enabled)
                )
            }
        }
    }
}
