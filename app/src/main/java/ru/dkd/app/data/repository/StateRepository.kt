package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

internal data class StateData(
    val parliament: JSONObject?,
    val parties: List<JSONObject>,
    val error: String? = null
)

internal class StateRepository {
    suspend fun load(token: String): StateData {
        val parliamentResponse = apiCall(token, "GET", "/api/parliament-roster")
        val partiesResponse = apiCall(token, "GET", "/api/parties")
        return StateData(
            parliament = parliamentResponse.data,
            parties = partiesResponse.data?.array("parties") ?: emptyList(),
            error = parliamentResponse.error ?: partiesResponse.error
        )
    }
}
