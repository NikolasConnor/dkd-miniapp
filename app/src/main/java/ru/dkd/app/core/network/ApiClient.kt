package ru.dkd.app.core.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.dkd.app.BuildConfig
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal val API_BASE_URL: String = BuildConfig.DKD_API_BASE_URL.trimEnd('/')

internal fun buildApiUrl(path: String): String =
    API_BASE_URL + if (path.startsWith("/")) path else "/$path"

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

private val retrofitService: DkdApiService by lazy {
    Retrofit.Builder()
        .baseUrl("$API_BASE_URL/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DkdApiService::class.java)
}

internal data class ApiResult(val token: String? = null, val error: String? = null)
internal data class NetResult(val data: JSONObject? = null, val error: String? = null)
internal data class DtoResult<T>(val data: T? = null, val error: String? = null)

internal suspend fun loadElectionsDto(token: String): DtoResult<ElectionsEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getElections("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) { DtoResult(error = exceptionMessage(e)) }
}

internal suspend fun loadPartiesDto(token: String): DtoResult<PartiesEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getParties("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) { DtoResult(error = exceptionMessage(e)) }
}

internal suspend fun loadNewsDto(token: String): DtoResult<NewsEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getNews("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) { DtoResult(error = exceptionMessage(e)) }
}

internal suspend fun loadProfileDto(token: String): DtoResult<ProfileEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getProfile("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) {
        DtoResult(error = exceptionMessage(e))
    }
}

internal suspend fun loadNotificationSummaryDto(token: String): DtoResult<NotificationSummaryDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getNotificationSummary("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) {
        DtoResult(error = exceptionMessage(e))
    }
}

internal suspend fun loadPassportDto(token: String): DtoResult<PassportEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getPassport("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) {
        DtoResult(error = exceptionMessage(e))
    }
}

internal suspend fun loadDocumentsDto(token: String): DtoResult<DocumentsEnvelopeDto> = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.getDocuments("Bearer $token")
        if (response.isSuccessful) DtoResult(data = response.body())
        else DtoResult(error = errorFrom(response.code(), response.errorBody()?.string().orEmpty()))
    } catch (e: Exception) {
        DtoResult(error = exceptionMessage(e))
    }
}

internal fun exceptionMessage(error: Exception): String = when (error) {
    is UnknownHostException -> "Нет соединения с сервером ДКД. Проверьте интернет."
    is SocketTimeoutException -> "Сервер ДКД не ответил вовремя. Попробуйте ещё раз."
    else -> "Не удалось связаться с сервером ДКД. Проверьте подключение."
}

private fun responseJson(body: String): JSONObject? =
    runCatching { JSONObject(body) }.getOrNull()

internal fun errorFrom(status: Int, body: String): String {
    val json = responseJson(body)
    val detail = json?.optString("detail")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
    return detail ?: when (status) {
        401 -> "Сессия истекла. Войдите в приложение заново."
        403 -> "У вас недостаточно прав для этого действия."
        404 -> "Этот сервис ещё не опубликован на сервере ДКД."
        429 -> "Слишком много запросов. Подождите немного."
        in 500..599 -> "На сервере ДКД временная ошибка. Попробуйте позже."
        else -> "Ошибка сервера: HTTP $status."
    }
}

private fun Response<ResponseBody>.bodyText(): String = body()?.string().orEmpty()

private suspend fun executeApi(
    token: String?,
    method: String,
    path: String,
    payload: JSONObject? = null
): NetResult {
    val url = buildApiUrl(path)
    val auth = token?.let { "Bearer $it" }
    val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
    val response = when (method.uppercase()) {
        "GET", "HEAD" -> retrofitService.get(url, auth)
        "POST" -> retrofitService.post(
            url, auth, body ?: ByteArray(0).toRequestBody(null)
        )
        "PUT" -> retrofitService.put(
            url, auth ?: return NetResult(error = "Требуется авторизация."),
            body ?: ByteArray(0).toRequestBody(null)
        )
        "PATCH" -> retrofitService.patch(
            url, auth ?: return NetResult(error = "Требуется авторизация."),
            body ?: ByteArray(0).toRequestBody(null)
        )
        "DELETE" -> retrofitService.delete(
            url, auth ?: return NetResult(error = "Требуется авторизация."), body
        )
        else -> return NetResult(error = "Неподдерживаемый HTTP-метод: $method.")
    }
    val text = if (response.isSuccessful) response.bodyText() else response.errorBody()?.string().orEmpty()
    return if (response.isSuccessful) NetResult(responseJson(text))
    else NetResult(error = errorFrom(response.code(), text))
}

internal suspend fun redeemLoginCode(code: String): ApiResult = withContext(Dispatchers.IO) {
    try {
        val response = retrofitService.redeemLogin(RedeemLoginRequest(code.trim()))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string().orEmpty()
            ApiResult(error = errorFrom(response.code(), errorBody))
        } else {
            val token = response.body()?.session_token?.takeIf { it.isNotBlank() }
            if (token == null) ApiResult(error = "Сервер не вернул токен сессии.")
            else ApiResult(token = token)
        }
    } catch (e: Exception) {
        ApiResult(error = exceptionMessage(e))
    }
}

internal suspend fun revokeSession(token: String): Boolean = withContext(Dispatchers.IO) {
    try {
        executeApi(token, "POST", "/api/android/auth/logout") .error == null
    } catch (_: Exception) {
        false
    }
}

internal suspend fun apiCall(
    token: String,
    method: String,
    path: String,
    payload: JSONObject? = null
): NetResult = withContext(Dispatchers.IO) {
    try {
        executeApi(token, method, path, payload)
    } catch (e: Exception) {
        NetResult(error = exceptionMessage(e))
    }
}

internal suspend fun apiUpload(
    token: String,
    path: String,
    context: Context,
    uri: Uri,
    body: String = ""
): NetResult = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 50L * 1024 * 1024) {
                    return@withContext NetResult(error = "Файл больше 50 МБ.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: return@withContext NetResult(error = "Не удалось прочитать выбранный файл.")

        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val part = MultipartBody.Part.createFormData(
            "file", "upload", bytes.toRequestBody(mime.toMediaType())
        )
        val response = retrofitService.upload(
            buildApiUrl(path),
            "Bearer $token",
            body.toRequestBody("text/plain; charset=utf-8".toMediaType()),
            part
        )
        val text = if (response.isSuccessful) response.bodyText() else response.errorBody()?.string().orEmpty()
        if (response.isSuccessful) NetResult(responseJson(text))
        else NetResult(error = errorFrom(response.code(), text))
    } catch (e: Exception) {
        NetResult(error = exceptionMessage(e))
    }
}
