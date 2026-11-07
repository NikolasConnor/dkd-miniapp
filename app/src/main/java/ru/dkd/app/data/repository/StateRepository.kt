package ru.dkd.app.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.data.local.DkdDatabase
import ru.dkd.app.data.local.PublicCacheEntity

internal data class StateData(
    val parliament: JSONObject?,
    val parties: List<JSONObject>,
    val error: String? = null,
    val fromCache: Boolean = false,
    val cachedAtEpochMs: Long? = null
)

internal class StateRepository(context: Context) {
    private val cache = DkdDatabase.get(context).publicCacheDao()

    suspend fun load(token: String): StateData {
        val parliamentResponse = apiCall(token, "GET", "/api/parliament-roster")
        val partiesResponse = apiCall(token, "GET", "/api/parties")
        val remoteParliament = parliamentResponse.data
        val remoteParties = partiesResponse.data?.array("parties")
        val remoteFailed = remoteParliament == null || partiesResponse.data == null

        if (!remoteFailed) {
            val now = System.currentTimeMillis()
            runCatching {
                cache.put(
                    PublicCacheEntity(
                        cacheKey = PARLIAMENT_KEY,
                        payload = remoteParliament.toString(),
                        updatedAtEpochMs = now
                    )
                )
                val publicParties = JSONArray().apply {
                    remoteParties.orEmpty().forEach { party ->
                        put(
                            JSONObject()
                                .put("id", party.optInt("id"))
                                .put("name", party.optString("name"))
                                .put("emoji", party.optString("emoji"))
                                .put("description", party.optString("description"))
                                .put("color", party.optString("color"))
                                .put("members_count", party.optInt("members_count"))
                        )
                    }
                }
                cache.put(
                    PublicCacheEntity(
                        cacheKey = PARTIES_KEY,
                        payload = publicParties.toString(),
                        updatedAtEpochMs = now
                    )
                )
            }
            return StateData(
                parliament = remoteParliament,
                parties = remoteParties.orEmpty(),
                error = null
            )
        }

        val savedParliament = runCatching { cache.get(PARLIAMENT_KEY) }.getOrNull()
        val savedParties = runCatching { cache.get(PARTIES_KEY) }.getOrNull()
        val cachedParliament = savedParliament?.let {
            runCatching { JSONObject(it.payload) }.getOrNull()
        }
        val cachedParties = savedParties?.let {
            runCatching {
                val array = JSONArray(it.payload)
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            }.getOrNull()
        }.orEmpty()

        val hasCache = cachedParliament != null || savedParties != null
        return StateData(
            parliament = remoteParliament ?: cachedParliament,
            parties = remoteParties ?: cachedParties,
            error = if (hasCache) null else parliamentResponse.error ?: partiesResponse.error
                ?: "Не удалось загрузить сведения о государстве.",
            fromCache = hasCache,
            cachedAtEpochMs = listOfNotNull(savedParliament?.updatedAtEpochMs, savedParties?.updatedAtEpochMs).minOrNull()
        )
    }

    private companion object {
        const val PARLIAMENT_KEY = "public_parliament_roster"
        const val PARTIES_KEY = "public_parties"
    }
}
