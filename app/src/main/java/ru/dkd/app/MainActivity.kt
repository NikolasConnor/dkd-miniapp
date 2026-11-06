package ru.dkd.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val API_BASE_URL = "https://dkd-miniapp-1.onrender.com"
private const val APP_HOST = "dkd-miniapp-1.onrender.com"
private val Navy = Color(0xFF090A0D)
private val Panel = Color(0xFF171A20)
private val Blue = Color(0xFF9ABEFF)
private val Muted = Color(0xFFAAB2C2)
private val White = Color(0xFFF5F5F2)
private val Green = Color(0xFF9BE2B5)
private val Amber = Color(0xFFFFD28A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DKDApp() }
    }
}

private class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dkd_android_session", Context.MODE_PRIVATE)
    private val alias = "dkd_android_session_key"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? {
        return try {
            val iv = prefs.getString("iv", null) ?: return null
            val value = prefs.getString("value", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

private data class ApiResult(val token: String? = null, val error: String? = null)

private suspend fun redeemLoginCode(code: String): ApiResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("$API_BASE_URL/api/android/auth/redeem").openConnection() as HttpURLConnection).apply {
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

private suspend fun revokeSession(token: String): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("$API_BASE_URL/api/android/auth/logout").openConnection() as HttpURLConnection).apply {
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



private data class NetResult(val data: JSONObject? = null, val error: String? = null)
private const val MAX_TEXT = 5000

private suspend fun apiCall(token: String, method: String, path: String, payload: JSONObject? = null): NetResult =
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(API_BASE_URL + path).openConnection() as HttpURLConnection).apply {
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
                ?: "Ошибка сервера: HTTP " + status)
        } catch (e: Exception) {
            NetResult(error = when (e) {
                is java.net.UnknownHostException -> "Нет соединения с сервером ДКД."
                is java.net.SocketTimeoutException -> "Сервер не ответил вовремя."
                else -> "Не удалось выполнить запрос. Проверьте интернет."
            })
        } finally { connection?.disconnect() }
    }

private suspend fun apiUpload(
    token: String, path: String, context: Context, uri: Uri, body: String = ""
): NetResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext NetResult(error = "Не удалось прочитать выбранный файл.")
        if (bytes.size > 50 * 1024 * 1024) return@withContext NetResult(error = "Файл больше 50 МБ.")
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val boundary = "DKD-" + java.util.UUID.randomUUID().toString()
        connection = (URL(API_BASE_URL + path).openConnection() as HttpURLConnection).apply {
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

private fun JSONObject.string(key: String, fallback: String = ""): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" } ?: fallback
private fun JSONObject.int(key: String): Int = optInt(key, 0)
private fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)
private fun JSONObject.array(key: String): List<JSONObject> {
    val value = optJSONArray(key) ?: return emptyList()
    return (0 until value.length()).mapNotNull { value.optJSONObject(it) }
}
private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
private fun safePathId(value: Int): String = value.toString()

@Composable
private fun AuthenticatedApp(token: String, logoutError: String?, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var profileId by remember { mutableIntStateOf(0) }
    var postId by remember { mutableIntStateOf(0) }
    var chatId by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    var notificationsOpen by remember { mutableStateOf(false) }
    var currentUserId by remember { mutableIntStateOf(0) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(logoutError) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        val result = apiCall(token, "GET", "/api/profile")
        currentUserId = result.data?.optJSONObject("profile")?.int("user_id")
            ?: result.data?.optJSONObject("profile")?.int("id") ?: 0
        val notes = apiCall(token, "GET", "/api/notifications")
        unreadCount = notes.data?.optInt("unread_count", 0) ?: 0
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, onPrimary = Navy, background = Navy,
        surface = Panel, onSurface = White, secondary = Muted
    )) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0D111B))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).background(Color(0xFF202B43), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("N", color = Blue, fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ДКД", color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Виртуальное государство", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { notificationsOpen = true }) {
                        Text("♧" + if (unreadCount > 0) " $unreadCount" else "", color = Blue)
                    }
                    TextButton(onClick = onLogout) { Text("Выйти", color = Muted) }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D111B), contentColor = White) {
                    val labels = listOf("Главная", "Лента", "Чаты", "Государство", "Профиль")
                    val icons = listOf("⌂", "◉", "✉", "▥", "◌")
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index && detail == null && !notificationsOpen,
                            onClick = {
                                tab = index; detail = null; profileId = 0; postId = 0; chatId = 0
                                notificationsOpen = false
                            },
                            icon = { Text(icons[index], fontSize = 21.sp) },
                            label = { Text(label, fontSize = 9.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue, selectedTextColor = Blue,
                                indicatorColor = Color(0xFF1C2D4B),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (toast != null) {
                    Surface(color = Color(0xFF422A34), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(toast!!, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { toast = null }) { Text("Закрыть") }
                        }
                    }
                }
                when {
                    notificationsOpen -> NotificationsScreen(token, onBack = { notificationsOpen = false },
                        onUnreadChanged = { unreadCount = it }, onError = { toast = it })
                    detail != null -> PortalDetailScreen(detail!!, onBack = { detail = null })
                    postId > 0 -> CommentsScreen(token, postId, currentUserId,
                        onBack = { postId = 0 }, onError = { toast = it })
                    profileId > 0 -> SocialProfileScreen(token, profileId, currentUserId,
                        onBack = { profileId = 0 },
                        onOpenPost = { postId = it },
                        onOpenChat = { chatId = it; tab = 2; profileId = 0 },
                        onError = { toast = it })
                    chatId > 0 -> ChatConversationScreen(token, chatId, currentUserId,
                        onBack = { chatId = 0 }, onError = { toast = it })
                    tab == 0 -> PortalHomeScreen(
                        onOpen = { detail = it },
                        onFeed = { tab = 1 },
                        onGovernment = { tab = 3 }
                    )
                    tab == 1 -> SocialFeedScreen(token, currentUserId,
                        onOpenProfile = { profileId = it },
                        onOpenComments = { postId = it },
                        onError = { toast = it })
                    tab == 2 -> ChatsScreen(token,
                        onOpenChat = { chatId = it },
                        onError = { toast = it })
                    tab == 3 -> GovernmentScreen(onOpen = { detail = it })
                    else -> AccountScreen(token, currentUserId, onError = { toast = it })
                }
            }
        }
    }
}

@Composable
private fun PortalHomeScreen(
    onOpen: (String) -> Unit,
    onFeed: () -> Unit,
    onGovernment: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("ДОБРО ПОЖАЛОВАТЬ", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Привет!", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Ваше цифровое пространство ДКД", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Surface(
            color = Color(0xFF17243B), shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpen("Статус гражданина") }
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ВАШ СТАТУС", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Гражданин ДКД", color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Посмотреть профиль и документы", color = Muted, fontSize = 11.sp)
                }
                Text("→", color = Blue, fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Государственные услуги", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onGovernment) { Text("Все →", color = Blue) }
        }
        val services = listOf(
            Triple("Выборы", "☑", "Выборы и голосования"),
            Triple("Документы", "▤", "Цифровые документы"),
            Triple("Государство", "▥", "Органы власти и должности"),
            Triple("Партии", "♟", "Политические объединения"),
            Triple("Обращения", "✉", "Заявления и обращения"),
            Triple("Законы", "§", "Законодательство ДКД")
        )
        services.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { item ->
                    Surface(
                        color = Color(0xFF151C2A), shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).padding(bottom = 10.dp)
                            .clickable { onOpen(item.first) }
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(item.second, color = Blue, fontSize = 27.sp)
                            Text(item.first, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.third, color = Muted, fontSize = 10.sp, minLines = 2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = Color(0xFF121A29), shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().clickable { onFeed() }
        ) {
            Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сообщество ДКД", color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Публикации, обсуждения и общение", color = Muted, fontSize = 11.sp)
                }
                Text("→", color = Blue, fontSize = 25.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF66758F), fontSize = 9.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GovernmentScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("ГОСУДАРСТВО ДКД", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Государственные разделы", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Основные институты и сервисы виртуального государства", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
        listOf(
            Triple("Президент", "Глава государства, полномочия и указы", "Президент"),
            Triple("Правительство", "Премьер-министр и министерства", "Правительство"),
            Triple("Государственная Дума", "Депутаты, состав и заседания", "Государственная Дума"),
            Triple("Выборы", "Избирательные кампании и результаты", "Выборы"),
            Triple("Политические партии", "Партии, руководители и списки", "Партии"),
            Triple("Документы", "Удостоверения, мандаты и указы", "Документы"),
            Triple("Законы", "Законопроекты и принятые законы", "Законы"),
            Triple("Новости", "Официальные события и публикации", "Новости")
        ).forEach { item ->
            Surface(
                color = Color(0xFF151C2A), shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onOpen(item.third) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.first, color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(item.second, color = Muted, fontSize = 11.sp)
                    }
                    Text("›", color = Blue, fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun PortalDetailScreen(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        TextButton(onClick = onBack) { Text("← Назад", color = Blue) }
        Spacer(Modifier.height(12.dp))
        Text("СЕРВИС ДКД", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Surface(color = Color(0xFF151C2A), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Раздел подготовлен к интеграции", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Это отдельный экран Android-приложения. Чтобы показывать здесь реальные сведения и выполнять действия, раздел необходимо подключить к соответствующим API ДКД. Сейчас данные и операции этого сервиса ещё не подключены в Android-клиенте.",
                    color = Muted, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(title, color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SocialFeedScreen(
    token: String, currentUserId: Int,
    onOpenProfile: (Int) -> Unit, onOpenComments: (Int) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var posts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var refreshing by remember { mutableIntStateOf(0) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { selectedUri = it }

    fun reload() {
        scope.launch {
            loading = true
            var path = "/api/social/feed?limit=40"
            if (search.isNotBlank()) path += "&search=" + Uri.encode(search.trim())
            if (filter == 1) path += "&following_only=true"
            if (filter == 2) path += "&saved_only=true"
            val result = apiCall(token, "GET", path)
            if (result.error != null) onError(result.error)
            posts = result.data?.array("posts") ?: emptyList()
            loading = false
        }
    }
    LaunchedEffect(token, filter, refreshing) { reload() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Лента", "Публикации граждан ДКД")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Что нового?") },
                minLines = 2, maxLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (text.isNotBlank() || selectedUri != null) scope.launch {
                        loading = true
                        val result = if (selectedUri != null) apiUpload(token, "/api/social/posts/media", context, selectedUri!!, text)
                        else apiCall(token, "POST", "/api/social/posts", JSONObject().put("body", text.trim()))
                        loading = false
                        if (result.error != null) onError(result.error)
                        else { text = ""; selectedUri = null; refreshing++ }
                    } else onError("Напишите текст или прикрепите файл.")
                }, enabled = !loading && (text.isNotBlank() || selectedUri != null)) { Text("Опубликовать") }
                OutlinedButton(onClick = { picker.launch("*/*") }) { Text(if (selectedUri == null) "＋ Медиа" else "Файл выбран") }
                if (selectedUri != null) TextButton(onClick = { selectedUri = null }) { Text("Убрать") }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Поиск по публикациям") },
                trailingIcon = { TextButton(onClick = { reload() }) { Text("Найти") } }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("Все") })
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Подписки") })
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Сохранённые") })
            }
        }
        if (loading && posts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        } else if (posts.isEmpty()) {
            EmptyState("Публикаций пока нет", "Создайте первую запись или измените фильтр.")
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(posts, key = { it.int("id") }) { post ->
                    SocialPostCard(token, post, currentUserId,
                        onAuthor = { onOpenProfile(it) },
                        onComments = { onOpenComments(post.int("id")) },
                        onRefresh = { refreshing++ }, onError = onError)
                }
            }
        }
    }
}

@Composable
private fun SocialPostCard(
    token: String, post: JSONObject, currentUserId: Int,
    onAuthor: (Int) -> Unit, onComments: () -> Unit,
    onRefresh: () -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val author = post.obj("author")
    val postId = post.int("id")
    val authorId = author.int("user_id")
    val original = post.obj("repost_of")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(author.string("avatar_emoji", "👤"), fontSize = 27.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(author.string("full_name", "Гражданин ДКД"), color = White,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { if (authorId > 0) onAuthor(authorId) })
                    Text("@" + author.string("username", "dkd_user") + " · " + post.string("created_at").take(16).replace("T", " "),
                        color = Muted, fontSize = 10.sp)
                }
                if (post.int("repost_of_post_id") > 0) Text("РЕПОСТ", color = Blue, fontSize = 9.sp)
            }
            if (post.string("body").isNotBlank()) Text(post.string("body"), color = White, fontSize = 14.sp, lineHeight = 21.sp)
            post.array("media").forEach { media ->
                val mediaUrl = media.string("url")
                val mime = media.string("mime_type")
                if (mime.startsWith("image/") && mediaUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(API_BASE_URL + mediaUrl)
                            .addHeader("Authorization", "Bearer " + token)
                            .crossfade(true).build(),
                        contentDescription = "Медиа публикации",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    )
                } else {
                    Surface(color = Color(0xFF24344B), shape = RoundedCornerShape(10.dp)) {
                        Text(if (mime.startsWith("video/")) "▶ Видеовложение" else "📎 Файл: " + media.string("media_type", "вложение"),
                            color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(11.dp))
                    }
                }
            }
            if (original.length() > 0) {
                Surface(color = Color(0xFF111C2C), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Исходная публикация · " + original.obj("author").string("full_name", "Автор"),
                            color = Blue, fontSize = 10.sp)
                        Text(original.string("body", "Исходная запись недоступна"), color = White, fontSize = 12.sp)
                    }
                }
            }
            Divider(color = Color(0xFF34445A))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val r = apiCall(token, "POST", "/api/social/posts/" + postId + "/reaction")
                        if (r.error != null) onError(r.error) else onRefresh()
                    }
                }) { Text((if (post.string("my_reaction").isNotBlank()) "♥ " else "♡ ") + post.int("reaction_count"), color = if (post.string("my_reaction").isNotBlank()) Amber else Muted) }
                TextButton(onClick = onComments) { Text("Комментарии " + post.int("comment_count"), color = Muted) }
                TextButton(onClick = {
                    scope.launch {
                        val r = apiCall(token, "POST", "/api/social/posts/" + postId + "/save")
                        if (r.error != null) onError(r.error) else onRefresh()
                    }
                }) { Text(if (post.bool("is_saved")) "★" else "☆", color = Blue) }
                TextButton(onClick = {
                    scope.launch {
                        val r = apiCall(token, "POST", "/api/social/posts/" + postId + "/repost", JSONObject().put("caption", ""))
                        if (r.error != null) onError(r.error) else { onError("Репост опубликован."); onRefresh() }
                    }
                }) { Text("↗", color = Blue) }
                if (authorId == currentUserId) {
                    TextButton(onClick = {
                        scope.launch {
                            val r = apiCall(token, "DELETE", "/api/social/posts/" + postId)
                            if (r.error != null) onError(r.error) else onRefresh()
                        }
                    }) { Text("Удалить", color = Amber, fontSize = 10.sp) }
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            val r = apiCall(token, "POST", "/api/social/posts/" + postId + "/report",
                                JSONObject().put("reason", "other").put("details", ""))
                            if (r.error != null) onError(r.error) else onError("Жалоба отправлена модераторам.")
                        }
                    }) { Text("⚑", color = Muted) }
                }
            }
        }
    }
}

@Composable
private fun CommentsScreen(
    token: String, postId: Int, currentUserId: Int,
    onBack: () -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    var parentId by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    fun load() {
        scope.launch {
            busy = true
            val r = apiCall(token, "GET", "/api/social/posts/" + postId + "/comments")
            if (r.error != null) onError(r.error)
            comments = r.data?.array("comments") ?: emptyList()
            busy = false
        }
    }
    LaunchedEffect(postId, refresh) { load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = Blue) }
            Text("Комментарии", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(comments, key = { it.int("id") }) { comment ->
                val author = comment.obj("author")
                val nested = comment.optInt("parent_comment_id", 0) > 0
                Surface(color = if (nested) Color(0xFF111C2C) else Panel,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = if (nested) 22.dp else 0.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(author.string("avatar_emoji", "👤") + "  " + author.string("full_name", "Гражданин"),
                            color = Blue, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(comment.string("body"), color = White, fontSize = 13.sp)
                        Row {
                            TextButton(onClick = { parentId = comment.int("id") }) { Text("Ответить", color = Muted, fontSize = 10.sp) }
                            if (comment.int("author_user_id") == currentUserId) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val r = apiCall(token, "DELETE", "/api/social/comments/" + comment.int("id"))
                                        if (r.error != null) onError(r.error) else refresh++
                                    }
                                }) { Text("Удалить", color = Amber, fontSize = 10.sp) }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            if (parentId > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ответ на комментарий #" + parentId, color = Blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { parentId = 0 }) { Text("Отмена") }
            }
            OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Написать комментарий…") }, maxLines = 3)
            Button(onClick = {
                if (body.isNotBlank()) scope.launch {
                    val payload = JSONObject().put("body", body.trim())
                    if (parentId > 0) payload.put("parent_comment_id", parentId)
                    val r = apiCall(token, "POST", "/api/social/posts/" + postId + "/comments", payload)
                    if (r.error != null) onError(r.error) else { body = ""; parentId = 0; refresh++ }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = body.isNotBlank()) { Text("Отправить") }
        }
    }
}

@Composable
private fun PeopleScreen(
    token: String, onOpenProfile: (Int) -> Unit,
    onOpenChat: (Int) -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        busy = true
        val r = apiCall(token, "GET", "/api/chats/people?search=" + Uri.encode(query))
        if (r.error != null) onError(r.error)
        people = r.data?.array("people") ?: emptyList()
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Люди", "Найти граждан и открыть профиль")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                singleLine = true, placeholder = { Text("Имя или @username") })
            TextButton(onClick = { refresh++ }) { Text("Найти") }
        }
        if (busy && people.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Blue)
        } else androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(people, key = { it.int("user_id") }) { person ->
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(person.string("avatar_emoji", "👤"), fontSize = 27.sp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(person.string("full_name", "Гражданин"), color = White, fontWeight = FontWeight.SemiBold)
                            Text("@" + person.string("username", "user"), color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onOpenProfile(person.int("user_id")) }) { Text("Профиль") }
                        TextButton(onClick = {
                            scope.launch {
                                val r = apiCall(token, "POST", "/api/chats/direct",
                                    JSONObject().put("target_user_id", person.int("user_id")))
                                val id = r.data?.optJSONObject("conversation")?.optInt("id", 0)
                                    ?: r.data?.optInt("conversation_id", 0) ?: r.data?.optInt("id", 0) ?: 0
                                if (r.error != null) onError(r.error)
                                else if (id > 0) onOpenChat(id) else onError("Чат создан, но сервер не вернул его номер.")
                            }
                        }) { Text("Чат", color = Blue) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatsScreen(token: String, onOpenChat: (Int) -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var createGroup by remember { mutableStateOf(false) }
    var groupTitle by remember { mutableStateOf("") }
    var memberIds by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        val r = apiCall(token, "GET", "/api/chats")
        if (r.error != null) onError(r.error)
        chats = r.data?.array("chats") ?: emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чаты", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Личные и групповые переписки", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { createGroup = true }) { Text("＋ Группа", color = Blue) }
        }
        if (chats.isEmpty()) EmptyState("Пока нет переписок", "Найдите граждан во вкладке «Люди», чтобы начать общение.")
        else androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chats, key = { it.int("id") }) { chat ->
                val participants = chat.array("participants")
                val names = participants.mapNotNull { it.obj("profile").string("full_name").takeIf { n -> n.isNotBlank() } }
                val last = chat.obj("last_message")
                Surface(color = Panel, shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenChat(chat.int("id")) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (chat.string("type") == "group") "👥" else "✉️", fontSize = 26.sp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(chat.string("title").ifBlank { names.filter { it.isNotBlank() }.take(2).joinToString(", ").ifBlank { "Личный чат" } },
                                color = White, fontWeight = FontWeight.SemiBold)
                            Text(last.string("body", names.joinToString(", ")), color = Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        if (chat.int("unread_count") > 0) Text(chat.int("unread_count").toString(),
                            color = Navy, fontSize = 11.sp, modifier = Modifier.background(Blue, RoundedCornerShape(20.dp)).padding(7.dp))
                    }
                }
            }
        }
    }
    if (createGroup) AlertDialog(
        onDismissRequest = { createGroup = false },
        title = { Text("Новая группа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(groupTitle, { groupTitle = it }, label = { Text("Название группы") })
                OutlinedTextField(memberIds, { memberIds = it }, label = { Text("ID участников через запятую") })
                Text("Добавьте ID граждан, которых хотите пригласить. Вы сами будете создателем группы.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ids = memberIds.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
                scope.launch {
                    val r = apiCall(token, "POST", "/api/chats/groups",
                        JSONObject().put("title", groupTitle.trim()).put("member_ids", org.json.JSONArray(ids)))
                    if (r.error != null) onError(r.error)
                    else { refresh++; createGroup = false; groupTitle = ""; memberIds = "" }
                }
            }, enabled = groupTitle.trim().length >= 2) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = { createGroup = false }) { Text("Отмена") } }
    )
}

@Composable
private fun ChatConversationScreen(
    token: String, chatId: Int, currentUserId: Int,
    onBack: () -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var replyId by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    fun load() {
        scope.launch {
            busy = true
            val r = apiCall(token, "GET", "/api/chats/" + chatId + "/messages?limit=80" +
                if (search.isNotBlank()) "&search=" + Uri.encode(search) else "")
            if (r.error != null) onError(r.error)
            messages = r.data?.array("messages") ?: emptyList()
            apiCall(token, "POST", "/api/chats/" + chatId + "/read")
            busy = false
        }
    }
    LaunchedEffect(chatId, refresh) { load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = Blue) }
            Text("Переписка", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true, placeholder = { Text("Поиск по сообщениям") },
            trailingIcon = { TextButton(onClick = { refresh++ }) { Text("Найти") } })
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.int("id") }) { message ->
                val mine = message.int("sender_user_id") == currentUserId
                Surface(color = if (mine) Color(0xFF263B59) else Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = if (mine) 36.dp else 0.dp, end = if (mine) 0.dp else 36.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message.obj("author").string("full_name", "Участник"), color = Blue, fontSize = 10.sp)
                        if (message.obj("reply_to").length() > 0) Text("↳ " + message.obj("reply_to").string("body"), color = Muted, fontSize = 10.sp)
                        Text(message.string("body"), color = White, fontSize = 13.sp)
                        Row {
                            TextButton(onClick = { replyId = message.int("id") }) { Text("Ответить", color = Muted, fontSize = 10.sp) }
                            if (message.bool("can_delete")) TextButton(onClick = {
                                scope.launch {
                                    val r = apiCall(token, "DELETE", "/api/chats/" + chatId + "/messages/" + message.int("id"))
                                    if (r.error != null) onError(r.error) else refresh++
                                }
                            }) { Text("Удалить", color = Amber, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            if (replyId > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ответ на сообщение #" + replyId, color = Blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { replyId = 0 }) { Text("Отмена") }
            }
            OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Сообщение…") }, maxLines = 4)
            Button(onClick = {
                if (body.isNotBlank()) scope.launch {
                    val payload = JSONObject().put("body", body.trim())
                    if (replyId > 0) payload.put("reply_to_message_id", replyId)
                    val r = apiCall(token, "POST", "/api/chats/" + chatId + "/messages", payload)
                    if (r.error != null) onError(r.error) else { body = ""; replyId = 0; refresh++ }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = body.isNotBlank()) { Text("Отправить") }
        }
    }
}

@Composable
private fun SocialProfileScreen(
    token: String, targetId: Int, currentUserId: Int,
    onBack: () -> Unit, onOpenPost: (Int) -> Unit,
    onOpenChat: (Int) -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(targetId, refresh) {
        busy = true
        val r = apiCall(token, "GET", "/api/social/profiles/" + targetId)
        if (r.error != null) onError(r.error)
        profile = r.data?.optJSONObject("profile")
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = Blue) }
            Text("Профиль", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        val p = profile
        if (p == null) {
            if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
            else EmptyState("Профиль недоступен", "Возможно, пользователь закрыл доступ или удалил аккаунт.")
        } else {
            val subject = p
            val isOwn = targetId == currentUserId
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(subject.string("avatar_emoji", "👤"), fontSize = 42.sp)
                            Text(subject.string("full_name", "Гражданин ДКД"), color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("@" + subject.string("username", "user"), color = Muted, fontSize = 12.sp)
                            Text(p.string("bio"), color = White, fontSize = 13.sp)
                            Text("Подписчики: " + p.optInt("followers_count", p.array("followers").size) + " · Подписки: " + p.optInt("following_count", p.array("following").size),
                                color = Muted, fontSize = 11.sp)
                            if (!isOwn) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        val r = apiCall(token, "POST", "/api/social/profiles/" + targetId + "/follow")
                                        if (r.error != null) onError(r.error) else refresh++
                                    }
                                }) { Text(if (p.string("follow_status") == "following") "Отписаться" else if (p.string("follow_status") == "requested") "Запрос отправлен" else "Подписаться") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val r = apiCall(token, "POST", "/api/chats/direct", JSONObject().put("target_user_id", targetId))
                                        val id = r.data?.optJSONObject("conversation")?.optInt("id", 0) ?: r.data?.optInt("conversation_id", 0) ?: r.data?.optInt("id", 0) ?: 0
                                        if (r.error != null) onError(r.error) else if (id > 0) onOpenChat(id)
                                    }
                                }) { Text("Написать") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val r = apiCall(token, "POST", "/api/social/profiles/" + targetId + "/block")
                                        if (r.error != null) onError(r.error) else { onError("Настройка блокировки обновлена."); onBack() }
                                    }
                                }) { Text("Блок", color = Amber) }
                            }
                            if (isOwn) Text("Это ваш профиль", color = Blue, fontSize = 11.sp)
                        }
                    }
                }
                if (p.bool("posts_hidden") && !isOwn) item { EmptyState("Профиль закрыт", "Подпишитесь, чтобы отправить запрос на просмотр публикаций.") }
                items(p.array("posts"), key = { it.int("id") }) { post ->
                    SocialPostCard(token, post, currentUserId,
                        onAuthor = {}, onComments = { onOpenPost(post.int("id")) },
                        onRefresh = { refresh++ }, onError = onError)
                }
            }
        }
    }
}

@Composable
private fun AccountScreen(token: String, currentUserId: Int, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var social by remember { mutableStateOf<JSONObject?>(null) }
    var fullName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("👤") }
    var privateProfile by remember { mutableStateOf(false) }
    var showConnections by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var followRequests by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(refresh) {
        val requests = apiCall(token, "GET", "/api/social/follow-requests")
        followRequests = requests.data?.array("requests") ?: emptyList()
        val r = apiCall(token, "GET", "/api/profile")
        profile = r.data?.optJSONObject("profile")
        fullName = profile?.string("full_name") ?: ""
        avatar = profile?.string("avatar_emoji", "👤") ?: "👤"
        val sid = currentUserId.takeIf { it > 0 } ?: profile?.int("user_id") ?: 0
        if (sid > 0) {
            val s = apiCall(token, "GET", "/api/social/profiles/" + sid)
            social = s.data?.optJSONObject("profile")
            bio = social?.string("bio") ?: ""
            privateProfile = social?.bool("is_private") ?: false
            showConnections = social?.optBoolean("show_connections", true) ?: true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Мой профиль", "Личные данные и настройки приватности")
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(avatar, fontSize = 38.sp)
                OutlinedTextField(fullName, { fullName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ФИО") })
                OutlinedTextField(avatar, { avatar = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Эмодзи аватара") }, singleLine = true)
                OutlinedTextField(bio, { bio = it }, modifier = Modifier.fillMaxWidth(), label = { Text("О себе") }, maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = privateProfile, onCheckedChange = { privateProfile = it })
                    Text("Закрытый профиль", color = White, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showConnections, onCheckedChange = { showConnections = it })
                    Text("Показывать подписчиков и подписки", color = White, fontSize = 13.sp)
                }
                Button(onClick = {
                    scope.launch {
                        val nameResult = apiCall(token, "PATCH", "/api/profile", JSONObject().put("full_name", fullName.trim()))
                        if (nameResult.error != null) onError(nameResult.error)
                        val avatarResult = apiCall(token, "PATCH", "/api/profile/avatar", JSONObject().put("avatar_emoji", avatar))
                        if (avatarResult.error != null) onError(avatarResult.error)
                        val socialResult = apiCall(token, "PATCH", "/api/social/profile",
                            JSONObject().put("bio", bio).put("is_private", privateProfile).put("show_connections", showConnections))
                        if (socialResult.error != null) onError(socialResult.error) else onError("Профиль сохранён.")
                        refresh++
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить изменения") }
            }
        }
        if (followRequests.isNotEmpty()) {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("ЗАПРОСЫ НА ПОДПИСКУ · " + followRequests.size, color = Blue, fontWeight = FontWeight.Bold)
                    followRequests.forEach { request ->
                        val requester = request.obj("requester")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(requester.string("avatar_emoji", "👤"), fontSize = 24.sp)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(requester.string("full_name", "Гражданин"), color = White, fontSize = 12.sp)
                                Text("@" + requester.string("username"), color = Muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val result = apiCall(token, "POST", "/api/social/follow-requests/" + request.int("requester_user_id") + "/approve")
                                    if (result.error != null) onError(result.error) else refresh++
                                }
                            }) { Text("Принять", color = Blue) }
                            TextButton(onClick = {
                                scope.launch {
                                    val result = apiCall(token, "POST", "/api/social/follow-requests/" + request.int("requester_user_id") + "/reject")
                                    if (result.error != null) onError(result.error) else refresh++
                                }
                            }) { Text("Отклонить", color = Amber, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
        profile?.let {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ГОСУДАРСТВО ДКД", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Должность: " + it.string("role", "Участник"), color = White)
                    Text("Партия: " + it.string("party_name", "Не состоит"), color = White)
                    Text("Статус: " + it.string("citizenship", "Не указан"), color = Muted, fontSize = 12.sp)
                }
            }
        }
        Text("Настройки уведомлений и документы доступны в соответствующих разделах государства.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun NotificationsScreen(
    token: String, onBack: () -> Unit,
    onUnreadChanged: (Int) -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        val r = apiCall(token, "GET", "/api/notifications")
        if (r.error != null) onError(r.error)
        notes = r.data?.array("notifications") ?: emptyList()
        onUnreadChanged(r.data?.optInt("unread_count", 0) ?: 0)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = Blue) }
            Text("Уведомления", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val r = apiCall(token, "POST", "/api/notifications/read-all")
                    if (r.error != null) onError(r.error) else refresh++
                }
            }) { Text("Прочитать всё", color = Blue, fontSize = 11.sp) }
        }
        if (notes.isEmpty()) EmptyState("Уведомлений нет", "Здесь появятся важные события и сообщения.")
        else androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes, key = { it.int("id") }) { item ->
                Surface(color = if (item.bool("is_read")) Panel else Color(0xFF243B57),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val r = apiCall(token, "POST", "/api/notifications/read", JSONObject().put("notification_id", item.int("id")))
                            if (r.error != null) onError(r.error) else refresh++
                        }
                    }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.string("title", "Уведомление"), color = White, fontWeight = FontWeight.SemiBold)
                        Text(item.string("message"), color = Muted, fontSize = 12.sp)
                        Text(item.string("created_at").take(16).replace("T", " "), color = Blue, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("◌", color = Blue, fontSize = 34.sp)
        Text(title, color = White, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, fontSize = 12.sp)
    }
}
