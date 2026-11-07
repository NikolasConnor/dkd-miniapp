package ru.dkd.app.data.repository

import ru.dkd.app.core.network.loadProfileDto

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
        val response = loadProfileDto(token)
        val dto = response.data?.profile
            ?: return CitizenProfileResult(null, response.error ?: "Профиль не получен.")
        return CitizenProfileResult(
            profile = CitizenProfile(
                fullName = dto.fullName.takeUnless { it.isBlank() || it == "null" } ?: "Гражданин ДКД",
                avatarEmoji = dto.avatarEmoji.takeUnless { it.isBlank() || it == "null" } ?: "👤",
                role = dto.role.takeUnless { it.isBlank() || it == "null" } ?: "Участник ДКД",
                citizenship = dto.citizenship.takeUnless { it.isBlank() || it == "null" } ?: "Статус не указан",
                partyName = dto.partyName?.takeUnless { it.isBlank() || it == "null" },
                newsNotificationsEnabled = dto.newsNotificationsEnabled
            ),
            error = response.error
        )
    }
}
