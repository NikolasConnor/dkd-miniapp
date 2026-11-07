package ru.dkd.app.core.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.dkd.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

internal val API_BASE_URL: String = BuildConfig.DKD_API_BASE_URL.trimEnd('/')

internal fun buildApiUrl(path: String): String =
    API_BASE_URL + if (path.startsWith("/")) path else "/$path"

internal data class ApiResult(val token: String? = null, val error: String? = null)

internal suspend fun redeemLoginCode(code: String): ApiResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(buildApiUrl("/api/android/auth/redeem")).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use {
            it.write(JSONObject().put("code", code.trim()).toString().toByteArray(Charsets.UTF_8))
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status in 200..299) {
            val token = json?.optString("session_token")?.takeIf { it.isNotBlank() }
            if (token == null) ApiResult(error = "Сервер не вернул токен сессии.")
            else ApiResult(token = token)
        } else {
            ApiResult(error = json?.optString("detail")?.takeIf { it.isNotBlank() }
                ?: "Не удалось войти. Код ответа: HTTP $status.")
        }
    } catch (e: Exception) {
        ApiResult(error = when (e) {
            is java.net.UnknownHostException -> "Не удалось найти сервер ДКД. Проверьте интернет."
            is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Попробуйте ещё раз."
            else -> "Не удалось связаться с сервером ДКД. Проверьте подключение."
        })
    } finally {
        connection?.disconnect()
    }
}

internal suspend fun revokeSession(token: String): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(buildApiUrl("/api/android/auth/logout")).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        connection.responseCode in 200..299
    } catch (_: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

private class AndroidSessionBridge(private val tokenProvider: () -> String?) {
    @JavascriptInterface fun hasSession(): Boolean = !tokenProvider().isNullOrBlank()
}

@Composable
private fun DKDApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SessionStore(context) }
    var token by remember { mutableStateOf(store.read()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var logoutError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, onPrimary = Navy, background = Navy,
            surface = Panel, onSurface = White, secondary = Muted
        )
    ) {
        if (token.isNullOrBlank()) {
            LoginScreen(
                code = code,
                onCodeChange = { code = it; error = null },
                busy = busy,
                error = error,
                onLogin = {
                    if (code.trim().length !in 8..64) {
                        error = "Введите одноразовый код длиной от 8 до 64 символов."
                    } else {
                        scope.launch {
                            busy = true
                            error = null
                            val result = redeemLoginCode(code)
                            busy = false
                            if (result.token != null) {
                                store.save(result.token)
                                token = result.token
                                code = ""
                            } else error = result.error ?: "Не удалось войти."
                        }
                    }
                }
            )
        } else {
            AuthenticatedApp(
                token = token!!,
                logoutError = logoutError,
                onLogout = {
                    val old = token!!
                    scope.launch {
                        logoutError = null
                        if (revokeSession(old)) {
                            CookieManager.getInstance().setCookie(
                                API_BASE_URL,
                                "dkd_session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax"
                            ) {
                                CookieManager.getInstance().flush()
                                store.clear()
                                token = null
                            }
                        } else {
                            logoutError = "Не удалось связаться с сервером и отозвать сессию. Проверьте интернет и повторите выход."
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun LoginScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onLogin: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Navy).padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(76.dp).background(Color(0xFF263B59), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) { Text("🏛️", fontSize = 38.sp) }
            Text("ДКД", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Самостоятельное приложение государства", color = Muted, fontSize = 13.sp)
            Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Text("Вход в аккаунт", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Введите одноразовый код привязки, который выдал администратор ДКД. Код действует 15 минут и используется один раз.",
                        color = Muted, fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Код привязки") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        enabled = !busy
                    )
                    error?.let {
                        Text(it, color = Color(0xFFFFA6B5), fontSize = 12.sp)
                    }
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !busy && code.isNotBlank()
                    ) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.size(19.dp), strokeWidth = 2.dp,
                            color = Navy
                        ) else Text("Войти в ДКД")
                    }
                }
            }
            Text(
                "Для первого входа администратор создаёт код в панели управления Mini App. Telegram после привязки не требуется.",
                color = Muted, fontSize = 11.sp
            )
        }
    }
}



internal data class NetResult(val data: JSONObject? = null, val error: String? = null)


internal suspend fun apiCall(token: String, method: String, path: String, payload: JSONObject? = null): NetResult =
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(buildApiUrl(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Authorization", "Bearer " + token)
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (payload != null) connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status in 200..299) NetResult(json, null)
            else NetResult(error = json?.optString("detail")?.takeIf { it.isNotBlank() && it != "null" }
                ?: if (status == 404) "Этот сервис ещё не опубликован на сервере ДКД." else "Ошибка сервера: HTTP " + status)
        } catch (e: Exception) {
            NetResult(error = when (e) {
                is java.net.UnknownHostException -> "Нет соединения с сервером ДКД."
                is java.net.SocketTimeoutException -> "Сервер не ответил вовремя."
                else -> "Не удалось выполнить запрос. Проверьте интернет."
            })
        } finally { connection?.disconnect() }
    }

internal suspend fun apiUpload(
    token: String, path: String, context: Context, uri: Uri, body: String = ""
): NetResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext NetResult(error = "Не удалось прочитать выбранный файл.")
        if (bytes.size > 50 * 1024 * 1024) return@withContext NetResult(error = "Файл больше 50 МБ.")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val boundary = "DKD-" + java.util.UUID.randomUUID().toString()
        connection = (URL(buildApiUrl(path)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        }
        connection.outputStream.use { raw ->
            val out = raw.buffered()
            fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"body\"\r\n\r\n" + body + "\r\n")
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\n")
            write("Content-Type: " + mime + "\r\n\r\n")
            out.write(bytes)
            write("\r\n--" + boundary + "--\r\n")
            out.flush()
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (status in 200..299) NetResult(json)
        else NetResult(error = json?.optString("detail")?.takeIf { it.isNotBlank() } ?: "Ошибка загрузки: HTTP " + status)
    } catch (_: Exception) {
        NetResult(error = "Не удалось загрузить файл. Проверьте соединение и разрешение на доступ к нему.")
    } finally { connection?.disconnect() }
}
