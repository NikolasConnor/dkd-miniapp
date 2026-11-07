package ru.dkd.app.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.dkd.app.core.security.CachedProfile
import ru.dkd.app.core.security.ProfileCache
import ru.dkd.app.data.repository.ProfileData
import ru.dkd.app.data.repository.ProfileRepository

internal data class DkdUiState(
    val name: String = "Гражданин",
    val role: String = "Участник ДКД",
    val unreadCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

internal class DkdViewModel(application: Application) : AndroidViewModel(application) {
    private val cache = ProfileCache(application)
    private val repository = ProfileRepository()
    private val mutableState = MutableStateFlow(
        cache.read()?.let {
            DkdUiState(name = it.name, role = it.role, unreadCount = it.unreadCount)
        } ?: DkdUiState()
    )
    val state: StateFlow<DkdUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val current = mutableState.value
            val result = repository.refresh(
                token,
                ProfileData(current.name, current.role, current.unreadCount)
            )
            mutableState.value = mutableState.value.copy(
                name = result.profile.name,
                role = result.profile.role,
                unreadCount = result.profile.unreadCount,
                loading = false,
                error = result.error
            )
            cache.write(
                CachedProfile(
                    result.profile.name,
                    result.profile.role,
                    result.profile.unreadCount
                )
            )
        }
    }

    fun setUnreadCount(value: Int) {
        val unread = value.coerceAtLeast(0)
        mutableState.value = mutableState.value.copy(unreadCount = unread)
        cache.write(CachedProfile(mutableState.value.name, mutableState.value.role, unread))
    }

    fun setError(value: String?) {
        mutableState.value = mutableState.value.copy(error = value)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun clearCachedProfile() {
        cache.clear()
        mutableState.value = DkdUiState()
    }
}
