package ru.dkd.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.core.network.redeemLoginCode
import ru.dkd.app.core.network.revokeSession
import ru.dkd.app.core.security.SessionStore
import ru.dkd.app.feature.auth.LoginScreen
import ru.dkd.app.feature.home.StubGovernmentScreen
import ru.dkd.app.feature.home.StubHomeScreen
import ru.dkd.app.feature.home.StubProfileScreen
import ru.dkd.app.feature.home.StubServiceScreen
import ru.dkd.app.feature.notifications.NotificationsScreen

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
            primary = Purple, onPrimary = White, background = Navy,
            surface = Panel, onSurface = White, secondary = Blue,
            error = Bad
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
    var unreadCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = detail != null) { detail = null }

    LaunchedEffect(token) {
        val profileResult = apiCall(token, "GET", "/api/profile")
        val profile = profileResult.data?.optJSONObject("profile") ?: profileResult.data
        val full = profile?.optString("full_name").orEmpty()
        if (full.isNotBlank() && full != "null") {
            name = full.trim().split(Regex("\\s+")).first()
        }
        role = profile?.optString("role")
            ?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Участник ДКД"

        val notifications = apiCall(token, "GET", "/api/notifications")
        unreadCount = notifications.data?.optInt("unread_count", 0) ?: 0
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, onPrimary = White, background = Navy,
            surface = Panel, onSurface = White, secondary = Blue,
            error = Bad
        )
    ) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Panel, Navy)))
                        .navigationBarsPadding()
                        .padding(start = 7.dp, end = 7.dp, top = 8.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val labels = listOf("Главная", "Государство", "Документы", "Выборы", "Профиль")
                    val icons = listOf("⌂", "⌘", "▤", "☑", "◉")
                    labels.forEachIndexed { index, label ->
                        val active = tab == index && detail == null
                        Column(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color(0x338B7CFF) else Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { tab = index; detail = null }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                icons[index],
                                color = if (active) Pink else Muted,
                                fontSize = 20.sp
                            )
                            Text(
                                label,
                                color = if (active) White else Muted,
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF171638), Navy, Navy)))
                    .padding(padding)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Panel.copy(alpha = .94f))
                        .padding(horizontal = 17.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(39.dp)
                            .background(
                                Brush.linearGradient(listOf(Blue, Purple)),
                                RoundedCornerShape(13.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Д", color = White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text("ДКД", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("NEXORA GROUP", color = Muted, fontSize = 8.sp, letterSpacing = 2.sp)
                    }
                    Box {
                        TextButton(onClick = { detail = "Уведомления" }) {
                            Text("♧", color = White, fontSize = 21.sp)
                        }
                        if (unreadCount > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                    .background(Bad, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    TextButton(onClick = onLogout) {
                        Text("Выйти", color = Muted, fontSize = 10.sp)
                    }
                }

                if (toast != null) {
                    Surface(
                        color = Color(0xFF3A263F),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(toast!!, color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { toast = null }) {
                                Text("Закрыть", color = Pink)
                            }
                        }
                    }
                }

                when {
                    detail == "Уведомления" -> NotificationsScreen(
                        token = token,
                        onBack = { detail = null },
                        onUnreadChanged = { unreadCount = it },
                        onError = { toast = it }
                    )
                    detail != null -> StubServiceScreen(
                        title = detail!!,
                        onBack = { detail = null },
                        onInfo = { toast = it }
                    )
                    tab == 0 -> StubHomeScreen(
                        name = name,
                        role = role,
                        onOpen = { detail = it },
                        onState = { tab = 1 }
                    )
                    tab == 1 -> StubGovernmentScreen(onOpen = { detail = it })
                    tab == 2 -> StubServiceScreen(
                        "Документы",
                        onBack = { detail = null },
                        onInfo = { toast = it }
                    )
                    tab == 3 -> StubServiceScreen(
                        "Выборы",
                        onBack = { detail = null },
                        onInfo = { toast = it }
                    )
                    else -> StubProfileScreen(
                        name = name,
                        role = role,
                        onLogout = onLogout,
                        onOpen = { detail = it }
                    )
                }
            }
        }
    }
}
