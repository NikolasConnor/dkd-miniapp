package ru.dkd.app.feature.elections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.ElectionsRepository
import ru.dkd.app.core.network.apiCall

internal data class ElectionsUiState(
    val loading: Boolean = true,
    val presidential: List<JSONObject> = emptyList(),
    val duma: List<JSONObject> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

internal class ElectionsViewModel : ViewModel() {
    private val repository = ElectionsRepository()
    private val mutableState = MutableStateFlow(ElectionsUiState())
    val state: StateFlow<ElectionsUiState> = mutableState.asStateFlow()

    fun castPresidentialVote(token: String, electionId: Int, option: String) {
        viewModelScope.launch {
            if (mutableState.value.busy) return@launch
            mutableState.value = mutableState.value.copy(busy = true, error = null, message = null)
            val result = apiCall(
                token, "POST", "/api/votes/presidential/$electionId/cast",
                JSONObject().put("option", option)
            )
            if (result.error != null) {
                mutableState.value = mutableState.value.copy(busy = false, error = result.error)
            } else {
                mutableState.value = mutableState.value.copy(busy = false, message = "Ваш голос принят сервером.")
                refresh(token)
            }
        }
    }

    fun refresh(token: String) {
        viewModelScope.launch {
            val previousMessage = mutableState.value.message
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = ElectionsUiState(
                loading = false,
                presidential = result.presidential,
                duma = result.duma,
                error = result.error,
                message = previousMessage
            )
        }
    }
}
