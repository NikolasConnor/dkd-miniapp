package ru.dkd.app.feature.parties

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.PartiesRepository

internal data class PartiesUiState(
    val loading: Boolean = true,
    val parties: List<JSONObject> = emptyList(),
    val isCitizen: Boolean = false,
    val myPartyId: Int? = null,
    val error: String? = null
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
                error = result.error
            )
        }
    }
}
