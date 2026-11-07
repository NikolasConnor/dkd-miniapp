package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

internal data class ElectionsData(
    val presidential: List<JSONObject>,
    val duma: List<JSONObject>,
    val error: String? = null
)

internal class ElectionsRepository {
    suspend fun load(token: String): ElectionsData {
        val response = apiCall(token, "GET", "/api/votes")
        val data = response.data
        return ElectionsData(
            presidential = data?.array("presidential") ?: emptyList(),
            duma = data?.array("duma") ?: emptyList(),
            error = response.error
        )
    }
}
