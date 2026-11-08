package ru.dkd.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Navy = Color(0xFF0C1024)
private val Panel = Color(0xFF171E38)
private val Panel2 = Color(0xFF242B50)
private val Accent = Color(0xFF777BFF)
private val Muted = Color(0xFFAAB2D1)
private val White = Color(0xFFF5F6FF)
private val Green = Color(0xFF91DDB1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Navy, surface = Panel, onSurface = White)) {
                var token by remember { mutableStateOf(getSharedPreferences("dkd_session", Context.MODE_PRIVATE).getString("session_token", null)) }
                if (token.isNullOrBlank()) {
                    LoginScreen(
                        onLogin = { redeemCode(it) },
                        onLoggedIn = {
                            getSharedPreferences("dkd_session", Context.MODE_PRIVATE).edit().putString("session_token", it).apply()
                            token = it
                        }
                    )
                } else {
                    NativeDkdApp(token!!, onLogout = {
                        revokeSession(token!!)
                        getSharedPreferences("dkd_session", Context.MODE_PRIVATE).edit().remove("session_token").apply()
                        token = null
                    })
                }
            }
        }
    }

    private suspend fun redeemCode(code: String): String = withContext(Dispatchers.IO) {
        val c = (URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/android/auth/redeem").openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 20000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("code", code.trim()).toString().toByteArray()) }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (status !in 200..299) error(json.optString("detail", "Ошибка входа: HTTP $status"))
            json.optString("session_token").takeIf { it.isNotBlank() } ?: error("Сервер не вернул токен.")
        } finally { c.disconnect() }
    }

    private fun revokeSession(token: String) {
        Thread {
            runCatching {
                val c = URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/android/auth/logout").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.setRequestProperty("Authorization", "Bearer $token"); c.responseCode; c.disconnect()
            }
        }.start()
    }
}

private suspend fun apiGet(token: String, path: String): String = withContext(Dispatchers.IO) {
    val c = URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}$path").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        val status = c.responseCode
        val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("Ошибка сервера HTTP $status: $body")
        runCatching { JSONObject(body).toString(2) }.getOrElse { body }
    } finally { c.disconnect() }
}

@Composable
private fun LoginScreen(onLogin: suspend (String) -> String, onLoggedIn: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("🏛️", fontSize = 58.sp)
            Text("ДКД", color = White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Самостоятельное приложение", color = Muted)
            Spacer(Modifier.height(28.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Вход в ДКД", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Введите одноразовый код привязки, выданный администратором.", color = Muted)
                    OutlinedTextField(value = code, onValueChange = { code = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }.take(64) }, label = { Text("Код входа") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = Color(0xFFFF9AA9)) }
                    Button(onClick = { scope.launch { loading = true; error = null; try { onLoggedIn(onLogin(code)) } catch (e: Exception) { error = e.message ?: "Ошибка входа" }; loading = false } }, enabled = code.length >= 8 && !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Входим…" else "Войти") }
                }
            }
        }
    }
}

private data class Destination(val title: String, val subtitle: String, val emoji: String, val path: String)
private val destinations = listOf(
    Destination("Профиль", "Личные данные и статус", "👤", "/api/profile"),
    Destination("Документы", "Паспорт, заявления и законы", "📚", "/api/documents"),
    Destination("Партии", "Участие и состав", "🎭", "/api/parties"),
    Destination("Выборы", "Голосование и результаты", "🗳️", "/api/votes"),
    Destination("Новости", "Государственная лента", "📰", "/api/news"),
    Destination("Уведомления", "Ваши сообщения", "🔔", "/api/notifications"),
    Destination("Государственная дума", "Парламент и заседания", "🏛️", "/api/duma"),
    Destination("Заявления", "Обращения и гражданство", "📝", "/api/applications"),
    Destination("Президент", "Указы и государственные решения", "⭐", "/api/president/status")
)

@Composable
private fun NativeDkdApp(token: String, onLogout: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf<Destination?>(null) }
    var tab by remember { mutableStateOf("Главная") }
    var payload by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(token) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { PushTokenRegistrar.register(context, token, it) }
    }
    fun load(path: String) {
        scope.launch {
            loading = true; error = null
            try { payload = apiGet(token, path) } catch (e: Exception) { error = e.message ?: "Не удалось загрузить данные"; payload = "" }
            loading = false
        }
    }
    fun open(destination: Destination) {
        selected = destination
        tab = destination.title
        payload = ""
        load(destination.path)
    }
    BackHandler(enabled = selected != null) { selected = null; tab = "Главная"; payload = "" }
    Column(Modifier.fillMaxSize().background(Navy)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ДКД", color = White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Независимое приложение", color = Muted, fontSize = 11.sp)
            }
            Text("● Подключено", color = Green, fontSize = 12.sp)
            TextButton(onClick = onLogout) { Text("Выйти", color = Muted) }
        }
        if (selected == null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(26.dp)) {
                        Column(Modifier.padding(22.dp)) {
                            Text("Добро пожаловать в ДКД", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Документы, гражданство и государственные сервисы — в одном приложении.", color = Muted, fontSize = 15.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { open(destinations[1]) }) { Text("Открыть документы") }
                        }
                    }
                }
                item { Text("Сервисы", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                items(destinations.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { item ->
                            Card(Modifier.weight(1f).height(148.dp).clickable { open(item) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                                Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Text(item.emoji, fontSize = 29.sp)
                                    Column {
                                        Text(item.title, color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(item.subtitle, color = Muted, fontSize = 11.sp, maxLines = 2)
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                TextButton(onClick = { selected = null; tab = "Главная"; payload = "" }) { Text("‹  На главную", color = Accent) }
                Text(selected!!.emoji + "  " + selected!!.title, color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(selected!!.subtitle, color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load(selected!!.path) }, enabled = !loading) { Text("Обновить") }
                    if (selected!!.title == "Профиль") OutlinedButton(onClick = { load("/api/passport") }) { Text("Паспорт") }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Text(error!!, color = Color(0xFFFF9AA9))
                    payload.isNotBlank() -> Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        LazyColumn(Modifier.fillMaxSize().padding(15.dp)) { item { Text(payload, color = White, fontSize = 13.sp) } }
                    }
                    else -> Text("Загрузка данных…", color = Muted)
                }
            }
        }
        if (selected == null) {
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("Главная", "Документы", "Выборы", "Профиль").forEach { label ->
                    TextButton(onClick = {
                        if (label == "Главная") { selected = null; tab = label }
                        else open(destinations.first { it.title == label })
                    }) { Text(label, color = if (tab == label) White else Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}
