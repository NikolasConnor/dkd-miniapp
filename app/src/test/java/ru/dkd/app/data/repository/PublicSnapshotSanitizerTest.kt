package ru.dkd.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PublicSnapshotSanitizerTest {
    @Test
    fun removesTelegramIdentifiersAndPrivateMemberFields() {
        val input = JSONObject(
            """{"formed":true,"seats_total":3,"seats_occupied":1,"term_start":"2026-11-01","term_end":"2027-02-01","telegram_user_id":7934244888,"members":[{"full_name":"Citizen One","party_id":2,"party_name":"Party","seats":1,"telegram_user_id":7934244888,"username":"private"}],"parties":[{"id":2,"name":"Party","emoji":"P","color":"#8B7CFF","seats":1,"votes":1,"leader_user_id":7934244888}]}"""
        )

        val sanitized = publicParliamentSnapshot(input)
        val member = sanitized.getJSONArray("members").getJSONObject(0)
        val party = sanitized.getJSONArray("parties").getJSONObject(0)

        assertEquals("Citizen One", member.getString("full_name"))
        assertNull(member.opt("telegram_user_id"))
        assertNull(member.opt("username"))
        assertNull(sanitized.opt("telegram_user_id"))
        assertNull(party.opt("leader_user_id"))
        assertFalse(sanitized.has("telegram_user_id"))
    }
}
