package ru.dkd.app.feature.parties

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

internal data class PartyCandidatesState(
    val loading: Boolean = true,
    val party: JSONObject? = null,
    val members: List<JSONObject> = emptyList(),
    val selectedIds: List<Long> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

internal class PartyCandidatesViewModel : ViewModel() {
    private val mutable = MutableStateFlow(PartyCandidatesState())
    val state = mutable.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            val previousMessage = mutable.value.message
            mutable.value = mutable.value.copy(loading = true, error = null)
            val result = apiCall(token, "GET", "/api/elections/duma/party-template")
            val data = result.data
            val selected = data?.optJSONArray("selected_user_ids") ?: JSONArray()
            val ids = buildList {
                for (index in 0 until selected.length()) {
                    val id = selected.optLong(index)
                    if (id > 0 && id !in this) add(id)
                }
            }
            mutable.value = PartyCandidatesState(
                loading = false,
                party = data?.optJSONObject("party"),
                members = data?.array("members") ?: emptyList(),
                selectedIds = ids,
                error = result.error,
                message = previousMessage
            )
        }
    }

    fun toggle(userId: Long, checked: Boolean) {
        val selected = mutable.value.selectedIds.toMutableList()
        if (checked && userId !in selected) selected.add(userId)
        else if (!checked) selected.remove(userId)
        mutable.value = mutable.value.copy(selectedIds = selected, error = null)
    }

    fun move(userId: Long, offset: Int) {
        val selected = mutable.value.selectedIds.toMutableList()
        val from = selected.indexOf(userId)
        val to = from + offset
        if (from < 0 || to !in selected.indices) return
        val value = selected.removeAt(from)
        selected.add(to, value)
        mutable.value = mutable.value.copy(selectedIds = selected)
    }

    fun save(token: String) {
        viewModelScope.launch {
            val current = mutable.value
            if (current.busy) return@launch
            if (current.selectedIds.isEmpty()) {
                mutable.value = current.copy(error = "Выберите хотя бы одного кандидата.")
                return@launch
            }
            val payload = JSONObject().put("candidate_user_ids", JSONArray().apply {
                current.selectedIds.forEach { put(it) }
            })
            mutable.value = current.copy(busy = true, error = null, message = null)
            val result = apiCall(token, "PUT", "/api/elections/duma/party-template", payload)
            if (result.error != null) {
                mutable.value = mutable.value.copy(busy = false, error = result.error)
            } else {
                val synced = result.data?.optInt("synced_elections", 0) ?: 0
                mutable.value = mutable.value.copy(
                    busy = false,
                    message = "Список сохранён. Синхронизировано открытых кампаний: $synced."
                )
                refresh(token)
            }
        }
    }
}
