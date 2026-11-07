package ru.dkd.app.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.DocumentsRepository

internal data class DocumentsUiState(
    val loading: Boolean = true,
    val hasPassport: Boolean = false,
    val passport: JSONObject? = null,
    val mandates: List<JSONObject> = emptyList(),
    val presidentCertificates: List<JSONObject> = emptyList(),
    val decrees: List<JSONObject> = emptyList(),
    val laws: List<JSONObject> = emptyList(),
    val passportLoadFailed: Boolean = false,
    val documentsLoadFailed: Boolean = false,
    val error: String? = null
)

internal class DocumentsViewModel : ViewModel() {
    private val repository = DocumentsRepository()
    private val mutableState = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = DocumentsUiState(
                loading = false,
                hasPassport = result.hasPassport,
                passport = result.passport,
                mandates = result.mandates,
                presidentCertificates = result.presidentCertificates,
                decrees = result.decrees,
                laws = result.laws,
                passportLoadFailed = result.passportLoadFailed,
                documentsLoadFailed = result.documentsLoadFailed,
                error = result.error
            )
        }
    }
}
