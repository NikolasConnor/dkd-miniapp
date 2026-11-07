package ru.dkd.app.feature.elections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.ElectionsRepository

internal data class ElectionsUiState(
    val loading: Boolean = true,
    val presidential: List<JSONObject> = emptyList(),
    val duma: List<JSONObject> = emptyList(),
    val error: String? = null
)

internal class ElectionsViewModel : ViewModel() {
    private val repository = ElectionsRepository()
    private val mutableState = MutableStateFlow(ElectionsUiState())
    val state: StateFlow<ElectionsUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = ElectionsUiState(
                loading = false,
                presidential = result.presidential,
                duma = result.duma,
                error = result.error
            )
        }
    }
}
