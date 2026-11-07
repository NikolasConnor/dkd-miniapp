package ru.dkd.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRepositoryTest {
    @Test
    fun usesGivenNameFromFullFio() {
        assertEquals("Никита", greetingFirstName("Горохов Никита Александрович", "Гражданин"))
    }

    @Test
    fun usesOnlyNameWhenFioHasOnePart() {
        assertEquals("Никита", greetingFirstName("Никита", "Гражданин"))
    }

    @Test
    fun fallsBackForBlankFio() {
        assertEquals("Гражданин", greetingFirstName(" ", "Гражданин"))
    }
}
