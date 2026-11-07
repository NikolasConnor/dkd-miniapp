package ru.dkd.app.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Headers
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Url
import com.google.gson.annotations.SerializedName

internal data class RedeemLoginRequest(val code: String)
internal data class RedeemLoginResponse(val session_token: String? = null)

internal data class PassportEnvelopeDto(
    @SerializedName("has_passport") val hasPassport: Boolean = false,
    @SerializedName("passport") val passport: PassportDto? = null
)

internal data class PassportDto(
    @SerializedName("number") val number: String = "",
    @SerializedName("full_name") val fullName: String = "",
    @SerializedName("issued_at") val issuedAt: String = "",
    @SerializedName("issued_by") val issuedBy: String = "",
    @SerializedName("division_code") val divisionCode: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("status_label") val statusLabel: String = "",
    @SerializedName("country") val country: String = "",
    @SerializedName("citizenship") val citizenship: String = "",
    @SerializedName("citizen_id") val citizenId: String = ""
)

internal data class DkdDocumentDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("text") val text: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("created_at") val createdAt: String = ""
)

internal data class DkdProfileDto(
    @SerializedName("full_name") val fullName: String = "",
    @SerializedName("avatar_emoji") val avatarEmoji: String = "",
    @SerializedName("role") val role: String = "",
    @SerializedName("citizenship") val citizenship: String = "",
    @SerializedName("party_name") val partyName: String? = null,
    @SerializedName("news_notifications_enabled") val newsNotificationsEnabled: Boolean = true
)

internal data class ProfileEnvelopeDto(
    @SerializedName("profile") val profile: DkdProfileDto? = null
)

internal data class NotificationSummaryDto(
    @SerializedName("unread_count") val unreadCount: Int = 0
)

internal data class DocumentsEnvelopeDto(
    @SerializedName("laws") val laws: List<DkdDocumentDto> = emptyList(),
    @SerializedName("decrees") val decrees: List<DkdDocumentDto> = emptyList(),
    @SerializedName("mandates") val mandates: List<DkdDocumentDto> = emptyList(),
    @SerializedName("president_certificate") val presidentCertificates: List<DkdDocumentDto> = emptyList()
)

internal data class PartyMemberDto(
    @SerializedName("member_id") val memberId: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("username") val username: String? = null,
    @SerializedName("is_leader") val isLeader: Boolean = false
)

internal data class PartyDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("emoji") val emoji: String = "🎭",
    @SerializedName("description") val description: String? = null,
    @SerializedName("color") val color: String = "#7188FF",
    @SerializedName("leader_username") val leaderUsername: String? = null,
    @SerializedName("status") val status: String = "",
    @SerializedName("members_count") val membersCount: Int = 0,
    @SerializedName("members") val members: List<PartyMemberDto> = emptyList(),
    @SerializedName("is_member") val isMember: Boolean = false,
    @SerializedName("can_manage") val canManage: Boolean = false
)

internal data class PartiesMeDto(
    @SerializedName("is_citizen") val isCitizen: Boolean = false,
    @SerializedName("party_id") val partyId: Int? = null,
    @SerializedName("is_admin") val isAdmin: Boolean = false
)

internal data class PartiesEnvelopeDto(
    @SerializedName("parties") val parties: List<PartyDto> = emptyList(),
    @SerializedName("me") val me: PartiesMeDto? = null
)

internal data class ElectionCandidateDto(
    @SerializedName("name") val name: String = "",
    @SerializedName("full_name") val fullName: String = "",
    @SerializedName("avatar_emoji") val avatarEmoji: String = "👤"
)

internal data class DumaPartyListDto(
    @SerializedName("party_name") val partyName: String = "",
    @SerializedName("party_emoji") val partyEmoji: String = "🎭",
    @SerializedName("color") val color: String = "#7188FF",
    @SerializedName("candidates") val candidates: List<String> = emptyList()
)

internal data class ElectionDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("kind") val kind: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("options") val options: List<String> = emptyList(),
    @SerializedName("starts_at") val startsAt: String? = null,
    @SerializedName("ends_at") val endsAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("status") val status: String = "",
    @SerializedName("is_open") val isOpen: Boolean = false,
    @SerializedName("has_voted") val hasVoted: Boolean = false,
    @SerializedName("my_option") val myOption: String? = null,
    @SerializedName("vote_counts") val voteCounts: Map<String, Int>? = null,
    @SerializedName("votes_total") val votesTotal: Int? = null,
    @SerializedName("candidate_details") val candidateDetails: List<ElectionCandidateDto> = emptyList(),
    @SerializedName("party_lists") val partyLists: List<DumaPartyListDto> = emptyList()
)

internal data class ElectionsEnvelopeDto(
    @SerializedName("presidential") val presidential: List<ElectionDto> = emptyList(),
    @SerializedName("duma") val duma: List<ElectionDto> = emptyList()
)

internal data class NewsArticleDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("text") val text: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("category") val category: String = "other",
    @SerializedName("importance") val importance: String = "normal",
    @SerializedName("media_type") val mediaType: String? = null
)

internal data class NewsEnvelopeDto(
    @SerializedName("news") val news: List<NewsArticleDto> = emptyList()
)

/** Retrofit transport contract. Legacy feature endpoints retain raw JSON until DTO contracts are verified. */
internal interface DkdApiService {
    @Headers("Accept: application/json")
    @GET("/api/votes")
    suspend fun getElections(@Header("Authorization") authorization: String): Response<ElectionsEnvelopeDto>

    @Headers("Accept: application/json")
    @GET("/api/parties")
    suspend fun getParties(@Header("Authorization") authorization: String): Response<PartiesEnvelopeDto>

    @Headers("Accept: application/json")
    @GET("/api/news")
    suspend fun getNews(@Header("Authorization") authorization: String): Response<NewsEnvelopeDto>

    @Headers("Accept: application/json")
    @GET("/api/profile")
    suspend fun getProfile(@Header("Authorization") authorization: String): Response<ProfileEnvelopeDto>

    @Headers("Accept: application/json")
    @GET("/api/notifications")
    suspend fun getNotificationSummary(@Header("Authorization") authorization: String): Response<NotificationSummaryDto>

    @Headers("Accept: application/json")
    @GET("/api/passport")
    suspend fun getPassport(@Header("Authorization") authorization: String): Response<PassportEnvelopeDto>

    @Headers("Accept: application/json")
    @GET("/api/documents")
    suspend fun getDocuments(@Header("Authorization") authorization: String): Response<DocumentsEnvelopeDto>

    @Headers("Accept: application/json")
    @POST("/api/android/auth/redeem")
    suspend fun redeemLogin(@Body request: RedeemLoginRequest): Response<RedeemLoginResponse>

    @GET
    suspend fun get(
        @Url url: String,
        @Header("Authorization") authorization: String? = null
    ): Response<ResponseBody>

    @POST
    suspend fun post(
        @Url url: String,
        @Header("Authorization") authorization: String? = null,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @PUT
    suspend fun put(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @PATCH
    suspend fun patch(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", hasBody = true)
    suspend fun delete(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody?
    ): Response<ResponseBody>

    @Multipart
    @POST
    suspend fun upload(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Part("body") body: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>
}
