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
}
