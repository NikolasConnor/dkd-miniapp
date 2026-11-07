package ru.dkd.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.network.loadElectionsDto

internal data class ElectionsData(
    val presidential: List<JSONObject>,
    val duma: List<JSONObject>,
    val error: String? = null
)

internal class ElectionsRepository {
    suspend fun load(token: String): ElectionsData {
        val response = loadElectionsDto(token)
        val envelope = response.data
        fun electionJson(item: ru.dkd.app.core.network.ElectionDto) = JSONObject()
            .put("id", item.id).put("kind", item.kind).put("title", item.title)
            .put("options", JSONArray(item.options)).put("starts_at", item.startsAt)
            .put("ends_at", item.endsAt).put("created_at", item.createdAt)
            .put("status", item.status).put("is_open", item.isOpen)
            .put("has_voted", item.hasVoted).put("my_option", item.myOption)
            .put("vote_counts", item.voteCounts?.let { JSONObject(it) })
            .put("votes_total", item.votesTotal)
            .put("candidate_details", JSONArray().apply {
                item.candidateDetails.forEach { candidate ->
                    put(JSONObject().put("name", candidate.name).put("full_name", candidate.fullName)
                        .put("avatar_emoji", candidate.avatarEmoji))
                }
            })
            .put("party_lists", JSONArray().apply {
                item.partyLists.forEach { party ->
                    put(JSONObject().put("party_name", party.partyName).put("party_emoji", party.partyEmoji)
                        .put("color", party.color).put("candidates", JSONArray(party.candidates)))
                }
            })
        return ElectionsData(
            presidential = envelope?.presidential.orEmpty().map(::electionJson),
            duma = envelope?.duma.orEmpty().map(::electionJson),
            error = response.error
        )
    }
}
