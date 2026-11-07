package ru.dkd.app.core.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        .retryOnConnectionFailure(true)
        .build()
}

internal data class ApiResult(val token: String? = null, val error: String? = null)
internal data class NetResult(val data: JSONObject? = null, val error: String? = null)

private fun exceptionMessage(error: Exception): String = when (error) {
    is UnknownHostException -> "Нет соединения с сервером ДКД. Проверьте интернет."
    is SocketTimeoutException -> "Сервер ДКД не ответил вовремя. Попробуйте ещё раз."
    else -> "Не удалось связаться с сервером ДКД. Проверьте подключение."
}

private fun responseJson(body: String): JSONObject? =
    runCatching { JSONObject(body) }.getOrNull()

private fun errorFrom(status: Int, body: String): String {
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

internal suspend fun redeemLoginCode(code: String): ApiResult = withContext(Dispatchers.IO) {
    try {
        val payload = JSONObject().put("code", code.trim()).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(buildApiUrl("/api/android/auth/redeem"))
            .header("Accept", "application/json")
            .post(payload)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext ApiResult(error = errorFrom(response.code, body))
            val token = responseJson(body)?.optString("session_token")?.takeIf { it.isNotBlank() && it != "null" }
            if (token == null) ApiResult(error = "Сервер не вернул токен сессии.")
            else ApiResult(token = token)
        }
    } catch (e: Exception) {
        ApiResult(error = exceptionMessage(e))
    }
}

internal suspend fun revokeSession(token: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(buildApiUrl("/api/android/auth/logout"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        httpClient.newCall(request).execute().use { it.isSuccessful }
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
        val body = payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val verb = method.uppercase()
        val requestBody = when (verb) {
            "GET", "HEAD" -> null
            "POST", "PUT", "PATCH" -> body ?: ByteArray(0).toRequestBody(null)
            "DELETE" -> body
            else -> return@withContext NetResult(error = "Неподдерживаемый HTTP-метод: $verb.")
        }
        val request = Request.Builder()
            .url(buildApiUrl(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { method(verb, requestBody) }
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) NetResult(responseJson(responseBody), null)
            else NetResult(error = errorFrom(response.code, responseBody))
        }
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
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("body", body)
            .addFormDataPart(
                "file", "upload",
                bytes.toRequestBody(mime.toMediaType())
            )
            .build()
        val request = Request.Builder()
            .url(buildApiUrl(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(multipart)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) NetResult(responseJson(responseBody))
            else NetResult(error = errorFrom(response.code, responseBody))
        }
    } catch (e: Exception) {
        NetResult(error = exceptionMessage(e))
    }
}
