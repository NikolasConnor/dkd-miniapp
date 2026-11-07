package ru.dkd.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientUrlTest {
    @Test
    fun buildsUrlFromLeadingSlashPath() {
        assertEquals(
            "https://dkd-miniapp-1.onrender.com/api/profile",
            buildApiUrl("/api/profile")
        )
    }

    @Test
    fun buildsUrlFromPathWithoutLeadingSlash() {
        assertEquals(
            "https://dkd-miniapp-1.onrender.com/api/profile",
            buildApiUrl("api/profile")
        )
    }

    @Test
    fun mapsUnauthorizedResponseToSessionMessage() {
        assertEquals(
            "Сессия истекла. Войдите в приложение заново.",
            errorFrom(401, "{}")
        )
    }

    @Test
    fun mapsForbiddenResponseToPermissionMessage() {
        assertEquals(
            "У вас недостаточно прав для этого действия.",
            errorFrom(403, "{}")
        )
    }

    @Test
    fun preservesBackendDetailMessage() {
        assertEquals(
            "Недостаточно прав",
            errorFrom(403, """{"detail":"Недостаточно прав"}""")
        )
    }

    @Test
    fun mapsMissingServiceResponse() {
        assertEquals(
            "Этот сервис ещё не опубликован на сервере ДКД.",
            errorFrom(404, "{}")
        )
    }
}
