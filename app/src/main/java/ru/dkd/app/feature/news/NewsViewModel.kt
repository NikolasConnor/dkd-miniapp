package ru.dkd.app.feature.news

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.data.repository.NewsRepository

internal data class NewsUiState(
    val loading: Boolean = true,
    val articles: List<JSONObject> = emptyList(),
    val error: String? = null,
    val fromCache: Boolean = false,
    val cachedAtEpochMs: Long? = null
)

internal class NewsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NewsRepository(application)
    private val mutableState = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = mutableState.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            val result = repository.load(token)
            mutableState.value = NewsUiState(
                loading = false,
                articles = result.articles,
                error = result.error,
                fromCache = result.fromCache,
                cachedAtEpochMs = result.cachedAtEpochMs
            )
        }
    }
}
