package ru.dkd.app.feature.parties

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.PartiesRepository
import ru.dkd.app.core.network.apiCall

internal data class PartiesUiState(
    val loading: Boolean = true,
    val parties: List<JSONObject> = emptyList(),
    val isCitizen: Boolean = false,
    val myPartyId: Int? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

internal class PartiesViewModel : ViewModel() {
    private val repository = PartiesRepository()
    private val mutableState = MutableStateFlow(PartiesUiState())
    val state: StateFlow<PartiesUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = PartiesUiState(
                loading = false,
                parties = result.parties,
                isCitizen = result.isCitizen,
                myPartyId = result.myPartyId,
                error = result.error,
                busy = mutableState.value.busy,
                message = mutableState.value.message
            )
        }
    }

    fun join(token: String, partyId: Int) {
        mutate(token, "/api/parties/$partyId/join", "POST", "Вы вступили в партию.")
    }

    fun leave(token: String) {
        mutate(token, "/api/parties/leave", "POST", "Вы вышли из партии.")
    }

    private fun mutate(token: String, path: String, method: String, success: String) {
        viewModelScope.launch {
            if (mutableState.value.busy) return@launch
            mutableState.value = mutableState.value.copy(busy = true, error = null, message = null)
            val result = apiCall(token, method, path)
            if (result.error != null) {
                mutableState.value = mutableState.value.copy(busy = false, error = result.error)
            } else {
                mutableState.value = mutableState.value.copy(busy = false, message = success)
                val refreshed = repository.load(token)
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    parties = refreshed.parties,
                    isCitizen = refreshed.isCitizen,
                    myPartyId = refreshed.myPartyId,
                    error = refreshed.error
                )
            }
        }
    }
}
