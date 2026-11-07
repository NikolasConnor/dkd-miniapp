package ru.dkd.app.data.repository

import org.json.JSONObject
import ru.dkd.app.core.network.apiCall

internal data class CitizenProfile(
    val fullName: String,
    val avatarEmoji: String,
    val role: String,
    val citizenship: String,
    val partyName: String?,
    val newsNotificationsEnabled: Boolean
)

internal data class CitizenProfileResult(
    val profile: CitizenProfile?,
    val error: String? = null
)

internal class CitizenProfileRepository {
    suspend fun load(token: String): CitizenProfileResult {
        val response = apiCall(token, "GET", "/api/profile")
        val json = response.data?.optJSONObject("profile") ?: response.data
        if (json == null) return CitizenProfileResult(null, response.error ?: "Профиль не получен.")
        return CitizenProfileResult(
            profile = CitizenProfile(
                fullName = json.optString("full_name").takeUnless { it.isBlank() || it == "null" } ?: "Гражданин ДКД",
                avatarEmoji = json.optString("avatar_emoji").takeUnless { it.isBlank() || it == "null" } ?: "👤",
                role = json.optString("role").takeUnless { it.isBlank() || it == "null" } ?: "Участник ДКД",
                citizenship = json.optString("citizenship").takeUnless { it.isBlank() || it == "null" } ?: "Статус не указан",
                partyName = json.optString("party_name").takeUnless { it.isBlank() || it == "null" },
                newsNotificationsEnabled = json.optBoolean("news_notifications_enabled", true)
            ),
            error = response.error
        )
    }

    suspend fun setNewsNotifications(token: String, enabled: Boolean): String? {
        val response = apiCall(
            token, "PATCH", "/api/profile/news-preferences",
            JSONObject().put("enabled", enabled)
        )
        return response.error
    }
}
