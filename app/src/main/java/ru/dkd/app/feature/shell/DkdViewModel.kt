package ru.dkd.app.feature.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.dkd.app.core.network.apiCall

internal data class DkdUiState(
    val name: String = "Гражданин",
    val role: String = "Участник ДКД",
    val unreadCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

internal class DkdViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(DkdUiState())
    val state: StateFlow<DkdUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val profileResult = apiCall(token, "GET", "/api/profile")
            val profile = profileResult.data?.optJSONObject("profile") ?: profileResult.data
            val fullName = profile?.optString("full_name").orEmpty()
            val displayName = fullName
                .takeIf { it.isNotBlank() && it != "null" }
                ?.trim()?.split(Regex("\\s+"))?.first()
                ?: mutableState.value.name
            val role = profile?.optString("role")
                ?.takeIf { !it.isNullOrBlank() && it != "null" }
                ?: mutableState.value.role

            val notifications = apiCall(token, "GET", "/api/notifications")
            mutableState.value = mutableState.value.copy(
                name = displayName,
                role = role,
                unreadCount = notifications.data?.optInt("unread_count", 0)
                    ?: mutableState.value.unreadCount,
                loading = false,
                error = profileResult.error ?: notifications.error
            )
        }
    }

    fun setUnreadCount(value: Int) {
        mutableState.value = mutableState.value.copy(unreadCount = value.coerceAtLeast(0))
    }

    fun setError(value: String?) {
        mutableState.value = mutableState.value.copy(error = value)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }
}
