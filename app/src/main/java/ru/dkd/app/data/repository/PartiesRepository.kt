package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

internal data class PartiesData(
    val parties: List<JSONObject>,
    val isCitizen: Boolean,
    val myPartyId: Int?,
    val error: String? = null
)

internal class PartiesRepository {
    suspend fun load(token: String): PartiesData {
        val response = apiCall(token, "GET", "/api/parties")
        val data = response.data
        val me = data?.optJSONObject("me")
        return PartiesData(
            parties = data?.array("parties") ?: emptyList(),
            isCitizen = me?.optBoolean("is_citizen", false) ?: false,
            myPartyId = me?.takeIf { it.has("party_id") && !it.isNull("party_id") }?.optInt("party_id"),
            error = response.error
        )
    }
}
