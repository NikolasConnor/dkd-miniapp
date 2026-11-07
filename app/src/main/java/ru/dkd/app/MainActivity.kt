package ru.dkd.app

import ru.dkd.app.core.network.API_BASE_URL
import ru.dkd.app.core.design.Navy
import ru.dkd.app.core.design.Panel
import ru.dkd.app.core.design.Blue
import ru.dkd.app.core.design.Muted
import ru.dkd.app.core.design.White
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
import ru.dkd.app.feature.home.StubHomeScreen
import ru.dkd.app.feature.home.StubGovernmentScreen
import ru.dkd.app.feature.home.StubProfileScreen
import ru.dkd.app.feature.home.StubServiceScreen
import ru.dkd.app.feature.notifications.NotificationsScreen

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
