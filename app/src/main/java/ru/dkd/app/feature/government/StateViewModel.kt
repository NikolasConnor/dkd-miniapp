package ru.dkd.app.feature.government

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.StateRepository

internal data class StateUiState(
    val loading: Boolean = true,
    val parliament: JSONObject? = null,
    val parties: List<JSONObject> = emptyList(),
    val error: String? = null,
    val fromCache: Boolean = false,
    val cachedAtEpochMs: Long? = null
)

internal class StateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StateRepository(application)
    private val mutableState = MutableStateFlow(StateUiState())
    val state: StateFlow<StateUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = StateUiState(
                loading = false,
                parliament = result.parliament,
                parties = result.parties,
                error = result.error,
                fromCache = result.fromCache,
                cachedAtEpochMs = result.cachedAtEpochMs
            )
        }
    }
}
