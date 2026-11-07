package ru.dkd.app.data.repository

import ru.dkd.app.core.network.apiCall

internal data class ProfileData(
    val name: String,
    val role: String,
    val unreadCount: Int
)

internal data class ProfileLoadResult(
    val profile: ProfileData,
    val error: String? = null
)

/** Maps legacy JSON endpoints into a small typed model consumed by the app ViewModel. */
internal class ProfileRepository {
    suspend fun refresh(token: String, fallback: ProfileData): ProfileLoadResult {
        val profileResponse = apiCall(token, "GET", "/api/profile")
        val profileJson = profileResponse.data?.optJSONObject("profile") ?: profileResponse.data

        val fullName = profileJson?.optString("full_name").orEmpty()
        val name = fullName
            .takeIf { it.isNotBlank() && it != "null" }
            ?.trim()?.split(Regex("\\s+"))?.first()
            ?: fallback.name

        val role = profileJson?.optString("role")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: fallback.role

        val notificationsResponse = apiCall(token, "GET", "/api/notifications")
        val unread = notificationsResponse.data?.optInt("unread_count", fallback.unreadCount)
            ?: fallback.unreadCount

        return ProfileLoadResult(
            profile = ProfileData(name, role, unread.coerceAtLeast(0)),
            error = profileResponse.error ?: notificationsResponse.error
        )
    }
}
