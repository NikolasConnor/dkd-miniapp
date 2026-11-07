package ru.dkd.app.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.data.local.DkdDatabase
import ru.dkd.app.data.local.PublicCacheEntity

internal data class NewsData(
    val articles: List<JSONObject>,
    val error: String? = null,
    val fromCache: Boolean = false,
    val cachedAtEpochMs: Long? = null
)

internal class NewsRepository(context: Context) {
    private val cache = DkdDatabase.get(context).publicCacheDao()

    suspend fun load(token: String): NewsData {
        val response = apiCall(token, "GET", "/api/news")
        val remote = response.data?.array("news")
        if (remote != null) {
            runCatching {
                cache.put(
                    PublicCacheEntity(
                        cacheKey = NEWS_KEY,
                        payload = JSONArray(remote).toString(),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
            return NewsData(articles = remote)
        }

        val saved = runCatching { cache.get(NEWS_KEY) }.getOrNull()
        val cached = saved?.let {
            runCatching {
                val array = JSONArray(it.payload)
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            }.getOrNull()
        }
        return NewsData(
            articles = cached.orEmpty(),
            error = if (cached != null) null else response.error,
            fromCache = cached != null,
            cachedAtEpochMs = saved?.updatedAtEpochMs
        )
    }

    private companion object {
        const val NEWS_KEY = "public_news_feed"
    }
}
