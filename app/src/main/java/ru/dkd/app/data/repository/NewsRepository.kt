package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall

internal data class NewsData(
    val articles: List<JSONObject>,
    val error: String? = null
)

internal class NewsRepository {
    suspend fun load(token: String): NewsData {
        val response = apiCall(token, "GET", "/api/news")
        return NewsData(
            articles = response.data?.array("news") ?: emptyList(),
            error = response.error
        )
    }
}
