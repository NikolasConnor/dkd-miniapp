package ru.dkd.app

import ru.dkd.app.core.network.API_BASE_URL
import ru.dkd.app.core.common.string
import ru.dkd.app.core.common.int
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.obj
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.core.network.apiUpload
import ru.dkd.app.core.network.redeemLoginCode
import ru.dkd.app.core.network.revokeSession
import ru.dkd.app.core.security.SessionStore
import ru.dkd.app.feature.auth.LoginScreen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import coil.compose.AsyncImage
import coil.request.ImageRequest

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
                            store.clear()
                            token = null
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
private fun AuthenticatedApp(token: String, logoutError: String?, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf(logoutError) }
    var name by remember { mutableStateOf("Гражданин") }
    var role by remember { mutableStateOf("Участник ДКД") }
    LaunchedEffect(token) {
        val result = apiCall(token, "GET", "/api/profile")
        val p = result.data?.optJSONObject("profile") ?: result.data
        val full = p?.optString("full_name").orEmpty()
        if (full.isNotBlank() && full != "null") name = full.trim().split(Regex("\\s+")).first()
        role = p?.optString("role")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Участник ДКД"
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, onPrimary = Navy, background = Navy,
        surface = Panel, onSurface = White, secondary = Muted
    )) {
        Scaffold(containerColor = Navy, bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0D1420))
                    .navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val labels = listOf("Главная", "Государство", "Документы", "Выборы", "Профиль")
                val icons = listOf("⌂", "⌘", "▤", "☑", "◉")
                labels.forEachIndexed { i, label ->
                    val active = tab == i && detail == null
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                            .background(if (active) Color(0xFF1B2D49) else Color.Transparent)
                            .clickable { tab = i; detail = null }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(icons[i], color = if (active) Blue else Muted, fontSize = 20.sp)
                        Text(label, color = if (active) Blue else Muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0D1420))
                        .padding(horizontal = 19.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(38.dp).background(Color(0xFF1C2B45), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center) {
                        Text("N", color = Blue, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("ДКД", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("NEXORA GROUP", color = Muted, fontSize = 8.sp, letterSpacing = 2.sp)
                    }
                    TextButton(onClick = onLogout) { Text("Выйти", color = Muted, fontSize = 11.sp) }
                }
                if (toast != null) {
                    Surface(color = Color(0xFF332632), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(toast!!, color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { toast = null }) { Text("Закрыть", color = Blue) }
                        }
                    }
                }
                if (detail != null) {
                    StubServiceScreen(detail!!, onBack = { detail = null }, onInfo = { toast = it })
                } else when (tab) {
                    0 -> StubHomeScreen(name, role, onOpen = { detail = it }, onState = { tab = 1 })
                    1 -> StubGovernmentScreen(onOpen = { detail = it })
                    2 -> StubServiceScreen("Документы", onBack = { detail = null }, onInfo = { toast = it })
                    3 -> StubServiceScreen("Выборы", onBack = { detail = null }, onInfo = { toast = it })
                    else -> StubProfileScreen(name, role, onLogout = onLogout, onOpen = { detail = it })
                }
            }
        }
    }
}

private val CardColor = Color(0xFF141D2B)
private val CardAlt = Color(0xFF19263A)
private val LineColor = Color(0xFF26364E)

@Composable
private fun StubSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) {
            Text(action, color = Blue, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StubHomeScreen(name: String, role: String, onOpen: (String) -> Unit, onState: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(21.dp))
        Text("ВИРТУАЛЬНОЕ ГОСУДАРСТВО  ·  NEXORA", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        Text("Привет, $name", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Добро пожаловать домой", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen("Статус гражданина") }) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ВАШ ЦИФРОВОЙ СТАТУС", color = Blue, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Text("Гражданин ДКД", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(role, color = Muted, fontSize = 11.sp)
                    }
                    Box(Modifier.size(54.dp).background(Color(0xFF243B5D), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Text("✦", color = Blue, fontSize = 29.sp)
                    }
                }
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Открыть цифровой профиль", color = Blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("↗", color = Blue, fontSize = 19.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        StubSectionTitle("Государственные услуги", "Все услуги", onState)
        Spacer(Modifier.height(10.dp))
        val services = listOf(
            Triple("Выборы", "☑", "Голосования и итоги"),
            Triple("Документы", "▤", "Ваши удостоверения"),
            Triple("Государство", "⌘", "Органы власти"),
            Triple("Партии", "♟", "Политические объединения"),
            Triple("Законы", "§", "Законодательство"),
            Triple("Обращения", "✉", "Заявления граждан")
        )
        services.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    Surface(color = CardColor, shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.weight(1f).padding(bottom = 10.dp).clickable { onOpen(item.first) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.second, color = Blue, fontSize = 25.sp)
                            Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.third, color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StubSectionTitle("Новости государства", "Все новости") { onOpen("Новости") }
        Spacer(Modifier.height(9.dp))
        Surface(color = CardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen("Новости") }) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2B43)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ДКД", color = Color(0xFFBBD2F7), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                        Text("DIGITAL STATE", color = Muted, fontSize = 8.sp, letterSpacing = 3.sp)
                    }
                }
                Text("Главные события ДКД", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Официальные сообщения, решения и обновления государства", color = Muted, fontSize = 11.sp)
                Text("ОТКРЫТЬ НОВОСТИ  →", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(25.dp))
        Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF61718B), fontSize = 8.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun StubGovernmentScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("NEXORA  /  STATE", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
        Text("Государство", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Институты и управление ДКД", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ДКД", color = Blue, fontSize = 10.sp, letterSpacing = 3.sp)
                Text("Система государственного управления", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Президентская власть · Парламент · Гражданское общество", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf(
            Triple("Президент", "Глава государства и гарант Конституции", "Президент"),
            Triple("Правительство", "Кабинет министров и государственные ведомства", "Правительство"),
            Triple("Государственная Дума", "Депутаты, заседания и законопроекты", "Государственная Дума"),
            Triple("Политические партии", "Объединения и партийные списки", "Партии"),
            Triple("Выборы", "Избирательные кампании и результаты", "Выборы"),
            Triple("Законодательство", "Законы и президентские указы", "Законы")
        ).forEach { item ->
            Surface(color = CardColor, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp).clickable { onOpen(item.third) }) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.first, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = Muted, fontSize = 10.sp)
                    }
                    Text("↗", color = Blue, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun StubProfileScreen(name: String, role: String, onLogout: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("NEXORA  /  ACCOUNT", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
        Text("Профиль", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(19.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(66.dp).background(Color(0xFF263B59), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = Blue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(role, color = Muted, fontSize = 11.sp)
                    Text("УЧЁТНАЯ ЗАПИСЬ ДКД", color = Blue, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf("Мои документы", "Настройки приложения", "О приложении Nexora Group").forEach { label ->
            Surface(color = CardColor, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpen(label) }) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("→", color = Blue)
                }
            }
        }
        TextButton(onClick = onLogout) { Text("Выйти из аккаунта", color = Color(0xFFE6A5AF)) }
    }
}

@Composable
private fun StubServiceScreen(title: String, onBack: () -> Unit, onInfo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Blue) }
        Spacer(Modifier.height(12.dp))
        Text("ГОСУДАРСТВЕННЫЙ СЕРВИС  /  ДКД", color = Blue, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цифровые услуги ДКД", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Единое пространство государственных сервисов виртуального государства.", color = Muted, fontSize = 11.sp)
                Text("NEXORA GROUP", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        StubEmptyState("✧", "Раздел готовится к запуску", "Это демонстрационная заглушка нового интерфейса. Реальные данные и действия подключаются следующим этапом.")
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onInfo("Это пока демонстрационный экран. Действия появятся после подключения сервиса.") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Понятно")
        }
    }
}

@Composable
private fun StubEmptyState(icon: String, title: String, subtitle: String) {
    Surface(color = CardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(54.dp).background(Color(0xFF203452), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Blue, fontSize = 25.sp)
            }
            Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
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
