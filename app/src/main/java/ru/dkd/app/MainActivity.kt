package ru.dkd.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val API_BASE_URL = "https://dkd-miniapp-1.onrender.com"
private const val APP_HOST = "dkd-miniapp-1.onrender.com"
private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1A2638)
private val Blue = Color(0xFF8DBBFA)
private val Muted = Color(0xFFA8B5C7)
private val White = Color(0xFFF4F7FC)
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


private data class NativeSection(val title: String, val subtitle: String, val endpoint: String, val icon: String)
private val NativeSections = listOf(
    NativeSection("Главная", "Обзор вашего государства", "/api/profile", "⌂"),
    NativeSection("Лента", "Публикации и обсуждения", "/api/social/feed?limit=30", "◉"),
    NativeSection("Чаты", "Личные и групповые переписки", "/api/chats", "✉"),
    NativeSection("Государство", "Новости, партии и парламент", "/api/news", "⌘"),
    NativeSection("Профиль", "Ваш аккаунт и документы", "/api/profile", "◌")
)
private data class NativeCard(val title: String, val detail: String)

private suspend fun loadNativeSection(token: String, endpoint: String): Pair<List<NativeCard>, String?> =
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("\${API_BASE_URL}\${endpoint}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                return@withContext Pair(emptyList(), message?.takeIf { it.isNotBlank() }
                    ?: "Сервер вернул ошибку HTTP $status.")
            }
            val json = JSONObject(body)
            val cards = mutableListOf<NativeCard>()
            fun addObject(obj: JSONObject, fallback: String) {
                val title = sequenceOf("title", "full_name", "name", "body", "text", "username")
                    .map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }
                    ?: fallback
                val detail = sequenceOf("description", "subtitle", "position", "status", "created_at")
                    .map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != "null" }
                    ?: sequenceOf("body", "text", "content")
                        .map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() && it != title }
                    ?: "Откройте раздел, чтобы посмотреть подробности."
                cards += NativeCard(title.take(100), detail.take(220))
            }
            for (key in listOf("posts", "chats", "news", "parties", "members", "items", "notifications")) {
                val array = json.optJSONArray(key) ?: continue
                for (i in 0 until minOf(array.length(), 30)) {
                    array.optJSONObject(i)?.let { addObject(it, "$key · \${i + 1}") }
                }
                if (cards.isNotEmpty()) break
            }
            if (cards.isEmpty()) {
                var nested = false
                for (key in listOf("profile", "user", "parliament", "data")) {
                    val obj = json.optJSONObject(key) ?: continue
                    nested = true
                    addObject(obj, "Данные аккаунта")
                }
                if (!nested && json.length() > 0) {
                    val labels = mapOf(
                        "full_name" to "ФИО", "username" to "Имя пользователя",
                        "position" to "Должность", "reputation" to "Репутация",
                        "is_citizen" to "Гражданство", "unread_count" to "Непрочитано"
                    )
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.opt(key)
                        if (value !is JSONObject && value !is org.json.JSONArray && value != JSONObject.NULL) {
                            cards += NativeCard(labels[key] ?: key.replace('_', ' '), value.toString())
                        }
                    }
                }
            }
            Pair(cards, null)
        } catch (e: Exception) {
            Pair(emptyList(), when (e) {
                is java.net.UnknownHostException -> "Нет соединения с сервером ДКД."
                is java.net.SocketTimeoutException -> "Сервер долго не отвечает. Попробуйте ещё раз."
                else -> "Не удалось загрузить раздел. Проверьте интернет."
            })
        } finally { connection?.disconnect() }
    }

@Composable
private fun AuthenticatedApp(token: String, logoutError: String?, onLogout: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<List<NativeCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshedAt by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val section = NativeSections[selected]
    fun refresh() {
        scope.launch {
            loading = true
            error = null
            val result = loadNativeSection(token, section.endpoint)
            cards = result.first
            error = result.second
            refreshedAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            loading = false
        }
    }
    LaunchedEffect(selected, token) { refresh() }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, onPrimary = Navy, background = Navy,
        surface = Panel, onSurface = White, secondary = Muted
    )) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ДКД", color = White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text("Цифровое государство", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { refresh() }, enabled = !loading) {
                        Text(if (loading) "Загрузка…" else "Обновить", color = Blue)
                    }
                    TextButton(onClick = onLogout) { Text("Выйти", color = Amber) }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = White) {
                    NativeSections.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selected == index, onClick = { selected = index },
                            icon = { Text(item.icon, fontSize = 20.sp) },
                            label = { Text(item.title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue, selectedTextColor = Blue,
                                indicatorColor = Color(0xFF263B59),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Navy)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Color(0xFF17243A), shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(section.title.uppercase(), color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(section.subtitle, color = White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        Text("Отдельное приложение ДКД · Android", color = Muted, fontSize = 12.sp)
                    }
                }
                if (selected == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Социальная сеть", "Государство").forEachIndexed { index, label ->
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(if (index == 0) "◉" else "⌘", color = Blue, fontSize = 23.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(label, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Выберите раздел ниже", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
                if (logoutError != null) NativeNotice(logoutError)
                if (error != null) {
                    NativeNotice(error!!)
                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Повторить загрузку")
                    }
                } else if (loading && cards.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Blue)
                    }
                } else if (cards.isEmpty()) {
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Пока здесь пусто", color = White, fontWeight = FontWeight.SemiBold)
                            Text("Когда в этом разделе появятся данные, они отобразятся здесь.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text("СОДЕРЖИМОЕ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    cards.forEach { card ->
                        Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(card.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(card.detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
                Text(if (refreshedAt.isBlank()) "Данные загружаются с сервера ДКД" else "Обновлено в $refreshedAt",
                    color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NativeNotice(message: String) {
    Surface(color = Color(0xFF422A34), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, color = White, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
    }
}
