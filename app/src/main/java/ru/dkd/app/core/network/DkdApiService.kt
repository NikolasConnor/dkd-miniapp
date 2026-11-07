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

internal data class DocumentsEnvelopeDto(
    @SerializedName("laws") val laws: List<DkdDocumentDto> = emptyList(),
    @SerializedName("decrees") val decrees: List<DkdDocumentDto> = emptyList(),
    @SerializedName("mandates") val mandates: List<DkdDocumentDto> = emptyList(),
    @SerializedName("president_certificate") val presidentCertificates: List<DkdDocumentDto> = emptyList()
)

/** Retrofit transport contract. Legacy feature endpoints retain raw JSON until DTO contracts are verified. */
internal interface DkdApiService {
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
