package ru.dkd.app.core.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApiDtoTest {
    private val gson = Gson()

    @Test
    fun parsesPassportEnvelopeAndSnakeCaseFields() {
        val result = gson.fromJson(
            """{"has_passport":true,"passport":{"number":"DKD-001","full_name":"Иванов Иван","issued_at":"2026-01-01","status":"active","status_label":"Действителен"}}""",
            PassportEnvelopeDto::class.java
        )

        assertEquals(true, result.hasPassport)
        assertNotNull(result.passport)
        assertEquals("DKD-001", result.passport?.number)
        assertEquals("Иванов Иван", result.passport?.fullName)
        assertEquals("Действителен", result.passport?.statusLabel)
    }

    @Test
    fun parsesOnlyTypedDocumentCollections() {
        val result = gson.fromJson(
            """{"laws":[{"id":7,"title":"Закон №7","status":"signed"}],"decrees":[{"id":3,"title":"Указ №3"}],"mandates":[{"id":9,"title":"Мандат"}],"president_certificate":[{"id":1,"title":"Удостоверение"}]}""",
            DocumentsEnvelopeDto::class.java
        )

        assertEquals(7, result.laws.single().id)
        assertEquals("signed", result.laws.single().status)
        assertEquals(3, result.decrees.single().id)
        assertEquals(9, result.mandates.single().id)
        assertEquals("Удостоверение", result.presidentCertificates.single().title)
    }

    @Test
    fun absentDocumentCollectionsBecomeEmptyAndDoNotInventDocuments() {
        val result = gson.fromJson("{}", DocumentsEnvelopeDto::class.java)

        assertNotNull(result)
        assertFalse(result.laws.any())
        assertFalse(result.decrees.any())
        assertFalse(result.mandates.any())
        assertFalse(result.presidentCertificates.any())
    }
}
