package ru.dkd.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

private data class Destination(val title: String, val emoji: String, val subtitle: String)
private data class ServerStatus(val online: Boolean, val message: String, val apiVersion: String? = null)

private suspend fun checkDkdServer(): ServerStatus = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/config").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            ServerStatus(false, "Сервер ответил с ошибкой HTTP $code")
        } else {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val version = runCatching { JSONObject(body).optString("api_version").takeIf { it.isNotBlank() } }.getOrNull()
            ServerStatus(true, "Сервер ДКД доступен", version)
        }
    } catch (e: Exception) {
        ServerStatus(false, when (e) {
            is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Возможно, он просыпается."
            is java.net.UnknownHostException -> "Не удалось найти адрес сервера. Проверьте интернет."
            else -> "Нет соединения с сервером ДКД."
        })
    } finally {
        connection?.disconnect()
    }
}

private fun networkType(context: Context): String {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return "Нет подключения к сети"
    val capabilities = manager.getNetworkCapabilities(network) ?: return "Сеть недоступна"
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильный интернет"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Проводная сеть"
        else -> "Другое подключение"
    }
}

@Composable
private fun DKDApp() {
    var page by remember { mutableStateOf("Главная") }
    var notice by remember { mutableStateOf<String?>(null) }
    var serverStatus by remember { mutableStateOf(ServerStatus(false, "Проверяем соединение…")) }
    var checking by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun refreshServer() {
        checking = true
        serverStatus = checkDkdServer()
        checking = false
    }

    LaunchedEffect(Unit) { refreshServer() }

    val destinations = listOf(
        Destination("Мой профиль", "👤", "Личные сведения и статус"),
        Destination("Государственная Дума", "🏛️", "Парламент и законопроекты"),
        Destination("Правительство", "⚖️", "Государственное управление"),
        Destination("Документы", "📄", "Удостоверения и решения"),
        Destination("Новости", "📰", "События ДКД"),
        Destination("Настройки", "⚙️", "Параметры приложения")
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, onPrimary = Navy, background = Navy,
            surface = Panel, onSurface = White, secondary = Muted
        )
    ) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = White) {
                    listOf("Главная", "Документы", "Новости", "Профиль").forEach { item ->
                        NavigationBarItem(
                            selected = page == item || (item == "Профиль" && page == "Мой профиль"),
                            onClick = { page = item },
                            icon = { Text(when (item) {
                                "Главная" -> "⌂"
                                "Документы" -> "▤"
                                "Новости" -> "▣"
                                else -> "●"
                            }, fontSize = 19.sp) },
                            label = { Text(item, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue,
                                selectedTextColor = Blue,
                                indicatorColor = Color(0xFF293D58),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).background(Color(0xFF263B59), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("🏛️", fontSize = 24.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ДКД", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = White)
                        Text("Цифровое пространство государства", fontSize = 11.sp, color = Muted)
                    }
                    Surface(color = Color(0xFF254B3A), shape = RoundedCornerShape(50)) {
                        Text("ANDROID", Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                when (page) {
                    "Главная" -> {
                        Text("Добро пожаловать", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = White)
                        Text("Демократическая Конфедерация Дебилов", color = Muted, fontSize = 13.sp)
                        ServerConnectionCard(
                            network = networkType(context),
                            status = serverStatus,
                            checking = checking,
                            onRefresh = { scope.launch { refreshServer() } }
                        )
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Цифровое пространство ДКД", color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("Самостоятельное приложение ДКД. Подключение проверяется напрямую, без Telegram.", color = Muted, fontSize = 12.sp)
                                Text("Состояние сервера не означает, что пользователь уже авторизован.", color = Blue, fontSize = 11.sp)
                            }
                        }
                        Text("Государственные разделы", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        destinations.forEach { item ->
                            DestinationCard(item) {
                                page = item.title
                                notice = null
                            }
                        }
                    }
                    "Мой профиль", "Профиль" -> ProfilePage()
                    "Государственная Дума" -> InfoPage("🏛️", "Государственная Дума",
                        "Парламент ДКД", listOf("Состав парламента", "Законопроекты", "Заседания", "Голосования"),
                        onAction = { notice = "Подключение к реальным парламентским данным появится после реализации авторизации Android." })
                    "Правительство" -> InfoPage("⚖️", "Правительство",
                        "Исполнительная власть ДКД", listOf("Президент", "Премьер-министр", "Министерства", "Государственные решения"),
                        onAction = { notice = "Подключение к реальным государственным данным появится после реализации авторизации Android." })
                    "Документы" -> InfoPage("📄", "Мои документы",
                        "Электронные документы ДКД", listOf("Удостоверение Президента", "Мандат депутата", "Паспорт гражданина", "Подписанные указы"),
                        onAction = { notice = "Для загрузки личных документов требуется безопасный вход в аккаунт ДКД." })
                    "Новости" -> InfoPage("📰", "Новости ДКД",
                        "Лента государственных событий", listOf("Официальные сообщения", "Решения Правительства", "Работа парламента"),
                        onAction = { notice = "Для загрузки персонализированной ленты требуется авторизация." })
                    "Настройки" -> {
                        Text("Настройки", fontSize = 26.sp, color = White, fontWeight = FontWeight.Bold)
                        ServerConnectionCard(networkType(context), serverStatus, checking) {
                            scope.launch { refreshServer() }
                        }
                        InfoPage("⚙️", "Приложение", "Сведения о версии",
                            listOf("Версия: 0.2.0", "Подключение: прямая проверка API", "Авторизация: ещё не подключена"),
                            onAction = { scope.launch { refreshServer() } })
                    }
                    else -> ProfilePage()
                }
                notice?.let {
                    Surface(color = Color(0xFF293D58), shape = RoundedCornerShape(12.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(13.dp), color = White, fontSize = 12.sp)
                    }
                }
                Text("ДКД · Android 0.2.0", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ServerConnectionCard(
    network: String,
    status: ServerStatus,
    checking: Boolean,
    onRefresh: () -> Unit
) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Связь с ДКД", color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                Surface(color = if (status.online) Color(0xFF254B3A) else Color(0xFF57432A), shape = RoundedCornerShape(50)) {
                    Text(if (checking) "ПРОВЕРКА" else if (status.online) "НА СВЯЗИ" else "НЕТ СВЯЗИ",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (status.online) Green else Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Сеть телефона: $network", color = Muted, fontSize = 12.sp)
            Text(status.message, color = if (status.online) Green else Amber, fontSize = 12.sp)
            status.apiVersion?.let { Text("API: $it", color = Muted, fontSize = 10.sp) }
            OutlinedButton(onClick = onRefresh, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text(if (checking) "Проверяем…" else "Проверить соединение")
            }
        }
    }
}

@Composable
private fun DestinationCard(item: Destination, onClick: () -> Unit) {
    Surface(
        color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(Color(0xFF263B59), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) { Text(item.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(item.subtitle, color = Muted, fontSize = 11.sp)
            }
            Text("›", color = Blue, fontSize = 27.sp)
        }
    }
}

@Composable
private fun ProfilePage() {
    Text("Мой профиль", fontSize = 26.sp, color = White, fontWeight = FontWeight.Bold)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("👤", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text("Вход не выполнен", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Личные сведения появятся после авторизации", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun InfoPage(
    emoji: String, title: String, description: String,
    items: List<String>, onAction: () -> Unit
) {
    Text(title, fontSize = 26.sp, color = White, fontWeight = FontWeight.Bold)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(emoji, fontSize = 35.sp)
            Text(description, color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Раздел подготовлен. Реальные данные будут подключены через защищённый API ДКД.",
                color = Muted, fontSize = 12.sp)
        }
    }
    items.forEach { item ->
        Surface(color = Panel, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAction)) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("›", color = Blue, fontSize = 24.sp)
            }
        }
    }
}
