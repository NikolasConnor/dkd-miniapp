package ru.dkd.app.data.repository

import org.json.JSONArray
import org.json.JSONObject

internal fun publicParliamentSnapshot(source: JSONObject): JSONObject {
    val members = JSONArray().apply {
        val input = source.optJSONArray("members") ?: JSONArray()
        for (index in 0 until input.length()) {
            val member = input.optJSONObject(index) ?: continue
            put(
                JSONObject()
                    .put("full_name", member.optString("full_name"))
                    .put("party_id", member.opt("party_id"))
                    .put("party_name", member.optString("party_name"))
                    .put("seats", member.optInt("seats"))
            )
        }
    }
    val parties = JSONArray().apply {
        val input = source.optJSONArray("parties") ?: JSONArray()
        for (index in 0 until input.length()) {
            val party = input.optJSONObject(index) ?: continue
            put(
                JSONObject()
                    .put("id", party.optInt("id"))
                    .put("name", party.optString("name"))
                    .put("emoji", party.optString("emoji"))
                    .put("color", party.optString("color"))
                    .put("seats", party.optInt("seats"))
                    .put("votes", party.optInt("votes"))
            )
        }
    }
    return JSONObject()
        .put("formed", source.optBoolean("formed"))
        .put("seats_total", source.optInt("seats_total"))
        .put("seats_occupied", source.optInt("seats_occupied"))
        .put("term_start", source.optString("term_start"))
        .put("term_end", source.optString("term_end"))
        .put("election_completed_at", source.optString("election_completed_at"))
        .put("dissolved", source.optBoolean("dissolved"))
        .put("members", members)
        .put("parties", parties)
}

