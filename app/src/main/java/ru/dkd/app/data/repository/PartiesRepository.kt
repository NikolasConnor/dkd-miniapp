package ru.dkd.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.network.loadPartiesDto

internal data class PartiesData(
    val parties: List<JSONObject>,
    val isCitizen: Boolean,
    val myPartyId: Int?,
    val error: String? = null
)

internal class PartiesRepository {
    suspend fun load(token: String): PartiesData {
        val response = loadPartiesDto(token)
        val envelope = response.data
        val me = envelope?.me
        val parties = envelope?.parties.orEmpty().map { party ->
            JSONObject()
                .put("id", party.id).put("name", party.name).put("emoji", party.emoji)
                .put("description", party.description).put("color", party.color)
                .put("leader_username", party.leaderUsername).put("status", party.status)
                .put("members_count", party.membersCount).put("is_member", party.isMember)
                .put("can_manage", party.canManage)
                .put("members", JSONArray().apply {
                    party.members.forEach { member ->
                        put(JSONObject().put("member_id", member.memberId).put("name", member.name)
                            .put("username", member.username).put("is_leader", member.isLeader))
                    }
                })
        }
        return PartiesData(
            parties = parties,
            isCitizen = me?.isCitizen ?: false,
            myPartyId = me?.partyId,
            error = response.error
        )
    }
}
