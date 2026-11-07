package ru.dkd.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PublicSnapshotSanitizerTest {
    @Test
    fun removesTelegramIdentifiersAndPrivateMemberFields() {
        val memberInput = JSONObject()
            .put("full_name", "Citizen One")
            .put("party_id", 2)
            .put("party_name", "Party")
            .put("seats", 1)
            .put("telegram_user_id", "7934244888")
            .put("username", "private")
        val partyInput = JSONObject()
            .put("id", 2)
            .put("name", "Party")
            .put("emoji", "P")
            .put("color", "#8B7CFF")
            .put("seats", 1)
            .put("votes", 1)
            .put("leader_user_id", "7934244888")
        val input = JSONObject()
            .put("formed", true)
            .put("seats_total", 3)
            .put("seats_occupied", 1)
            .put("term_start", "2026-11-01")
            .put("term_end", "2027-02-01")
            .put("telegram_user_id", "7934244888")
            .put("members", JSONArray().put(memberInput))
            .put("parties", JSONArray().put(partyInput))

        val sanitized = publicParliamentSnapshot(input)
        val member = sanitized.getJSONArray("members").getJSONObject(0)
        val party = sanitized.getJSONArray("parties").getJSONObject(0)

        assertEquals("Citizen One", member.getString("full_name"))
        assertNull(member.opt("party_id"))
        assertNull(member.opt("telegram_user_id"))
        assertNull(member.opt("username"))
        assertNull(sanitized.opt("telegram_user_id"))
        assertNull(party.opt("leader_user_id"))
        assertFalse(sanitized.has("telegram_user_id"))
    }
}
