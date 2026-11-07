package ru.dkd.app.data.repository

import ru.dkd.app.core.network.loadNotificationSummaryDto
import ru.dkd.app.core.network.loadProfileDto

internal data class ProfileData(
    val name: String,
    val role: String,
    val unreadCount: Int
)

internal data class ProfileLoadResult(
    val profile: ProfileData,
    val error: String? = null
)

/** Returns the given name from the DKD "Фамилия Имя Отчество" format. */
internal fun greetingFirstName(fullName: String, fallback: String): String {
    val parts = fullName.takeIf { it.isNotBlank() && it != "null" }
        ?.trim()?.split(Regex("\\s+")).orEmpty()
    return when {
        parts.size >= 2 -> parts[1]
        parts.size == 1 -> parts[0]
        else -> fallback
    }
}

internal class ProfileRepository {
    suspend fun refresh(token: String, fallback: ProfileData): ProfileLoadResult {
        val profileResponse = loadProfileDto(token)
        val profile = profileResponse.data?.profile
        val notificationsResponse = loadNotificationSummaryDto(token)

        val name = greetingFirstName(profile?.fullName.orEmpty(), fallback.name)
        val role = profile?.role?.takeIf { it.isNotBlank() && it != "null" } ?: fallback.role
        val unread = notificationsResponse.data?.unreadCount ?: fallback.unreadCount

        return ProfileLoadResult(
            profile = ProfileData(name, role, unread.coerceAtLeast(0)),
            error = profileResponse.error ?: notificationsResponse.error
        )
    }
}
