package ru.dkd.app.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
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

/** Retrofit transport contract. JSON remains decoded at the repository boundary for legacy API compatibility. */
internal interface DkdApiService {
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
