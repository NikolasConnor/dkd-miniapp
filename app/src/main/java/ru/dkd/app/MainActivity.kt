package ru.dkd.app

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import ru.dkd.app.core.design.*
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.core.network.redeemLoginCode
import ru.dkd.app.core.network.revokeSession
import ru.dkd.app.core.security.ProfileCache
import ru.dkd.app.core.security.SessionStore
import ru.dkd.app.feature.auth.LoginScreen
import ru.dkd.app.feature.admin.AdminScreen
import ru.dkd.app.feature.applications.ApplicationsScreen
import ru.dkd.app.feature.documents.DocumentsScreen
import ru.dkd.app.feature.duma.DumaScreen
import ru.dkd.app.feature.elections.ElectionsScreen
import ru.dkd.app.feature.government.StateScreen
import ru.dkd.app.feature.home.HomeScreen
import ru.dkd.app.feature.home.MenuScreen
import ru.dkd.app.feature.home.StubServiceScreen
import ru.dkd.app.feature.notifications.NotificationsScreen
import ru.dkd.app.feature.news.NewsScreen
import ru.dkd.app.feature.parties.PartiesScreen
import ru.dkd.app.feature.parties.PartyCandidatesScreen
import ru.dkd.app.feature.profile.ProfileScreen
import ru.dkd.app.feature.president.PresidentScreen
import ru.dkd.app.feature.shell.DkdViewModel

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
            surface = Panel, onSurface = White, secondary = Blue, error = Bad
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
                            ProfileCache(context).clear()
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val appViewModel: DkdViewModel = viewModel()
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf("https://github.com/NikolasConnor/dkd-miniapp/releases/latest") }
    var updateVersion by remember { mutableStateOf("") }
    var updateDismissed by remember { mutableStateOf(false) }
    val uiState by appViewModel.state.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(token) {
        appViewModel.refresh(token)
        val config = apiCall(token, "GET", "/api/config")
        val data = config.data
        if (data != null && data.optInt("android_version_code", 0) > BuildConfig.VERSION_CODE) {
            updateVersion = data.optString("android_version_name", "новая версия")
            updateMessage = data.optString("android_update_message", "Доступно обновление приложения ДКД.")
            updateUrl = data.optString("android_release_url").ifBlank { updateUrl }
            updateDismissed = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, onPrimary = White, background = Navy,
            surface = Panel, onSurface = White, secondary = Blue, error = Bad
        )
    ) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Panel, Navy)))
                        .navigationBarsPadding()
                        .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 7.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        Triple("Главная", "⌂", "home"),
                        Triple("Документы", "▤", "documents"),
                        Triple("Уведомления", "◉", "notifications"),
                        Triple("Меню", "☰", "menu")
                    )
                    tabs.forEach { (label, icon, route) ->
                        val active = currentRoute == route ||
                            (route == "menu" && currentRoute in setOf("government", "elections", "profile", "news", "duma", "admin", "president", "applications", "party-candidates", "detail/{title}"))
                        Column(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color(0x338B7CFF) else Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    navController.navigate(route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(icon, color = if (active) Pink else Muted, fontSize = 20.sp)
                            Text(label, color = if (active) White else Muted, fontSize = 9.sp, maxLines = 1)
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
                    Modifier.fillMaxWidth().background(Panel.copy(alpha = .94f))
                        .padding(horizontal = 17.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(39.dp).background(
                            Brush.linearGradient(listOf(Blue, Purple)),
                            RoundedCornerShape(13.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) { Text("Д", color = White, fontSize = 21.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("ДКД", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("NEXORA GROUP", color = Muted, fontSize = 8.sp, letterSpacing = 2.sp)
                    }
                    Box {
                        TextButton(onClick = { navController.navigate("notifications") }) {
                            Text("🔔", color = White, fontSize = 19.sp)
                        }
                        if (uiState.unreadCount > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                    .background(Bad, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (uiState.unreadCount > 99) "99+" else uiState.unreadCount.toString(),
                                    color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    TextButton(onClick = onLogout) { Text("Выйти", color = Muted, fontSize = 10.sp) }
                }

                val visibleError = uiState.error ?: logoutError
                if (visibleError != null) {
                    Surface(color = Color(0xFF3A263F), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(visibleError, color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { appViewModel.clearError() }) {
                                Text("Закрыть", color = Pink)
                            }
                        }
                    }
                }

                if (updateMessage != null && !updateDismissed) {
                    AlertDialog(
                        onDismissRequest = { updateDismissed = true },
                        containerColor = Panel,
                        title = { Text("Доступно обновление ДКД", color = White, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Версия $updateVersion", color = Purple, fontWeight = FontWeight.SemiBold)
                                Text(updateMessage.orEmpty(), color = Muted, fontSize = 12.sp)
                                Text("Устанавливайте поверх текущей версии. Не удаляйте старое приложение и не очищайте его данные.",
                                    color = White, fontSize = 11.sp)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                                }
                                updateDismissed = true
                            }) { Text("Открыть обновление", color = Purple) }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateDismissed = true }) { Text("Позже", color = Muted) }
                        }
                    )
                }

                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("home") {
                        HomeScreen(
                            name = uiState.name,
                            role = uiState.role,
                            onOpen = {
                                when (it) {
                                    "Новости" -> navController.navigate("news")
                                    "Меню" -> navController.navigate("menu")
                                    "Профиль" -> navController.navigate("profile")
                                    "Документы" -> navController.navigate("documents")
                                    "Выборы" -> navController.navigate("elections")
                                    "Государство" -> navController.navigate("government")
                                    "Президентская администрация" -> navController.navigate("president")
                                    "Панель администратора" -> navController.navigate("admin")
                                    "Заявления" -> navController.navigate("applications")
                                    "Государственная Дума" -> navController.navigate("duma")
                                    else -> navController.navigate("detail/${Uri.encode(it)}")
                                }
                            },
                            onState = { navController.navigate("government") }
                        )
                    }
                    composable("menu") {
                        MenuScreen(
                            name = uiState.name,
                            role = uiState.role,
                            unreadCount = uiState.unreadCount,
                            onOpen = {
                                when (it) {
                                    "Государство" -> navController.navigate("government")
                                    "Выборы" -> navController.navigate("elections")
                                    "Профиль" -> navController.navigate("profile")
                                    "Новости" -> navController.navigate("news")
                                    "Государственная Дума" -> navController.navigate("duma")
                                    "Президентская администрация" -> navController.navigate("president")
                                    "Панель администратора" -> navController.navigate("admin")
                                    "Заявления" -> navController.navigate("applications")
                                    "Партии" -> navController.navigate("detail/Партии")
                                    "Уведомления" -> navController.navigate("notifications")
                                    else -> navController.navigate("detail/${Uri.encode(it)}")
                                }
                            }
                        )
                    }
                    composable("duma") {
                        DumaScreen(
                            token = token,
                            onBack = { navController.popBackStack() },
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("applications") {
                        ApplicationsScreen(
                            token = token,
                            onBack = { navController.popBackStack() },
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("admin") {
                        AdminScreen(token = token, onBack = { navController.popBackStack() }, onError = { appViewModel.setError(it) })
                    }
                    composable("president") {
                        PresidentScreen(
                            token = token,
                            onBack = { navController.popBackStack() },
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("government") {
                        StateScreen(
                            token = token,
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("documents") {
                        DocumentsScreen(
                            token = token,
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("elections") {
                        ElectionsScreen(
                            token = token,
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("profile") {
                        ProfileScreen(
                            token = token,
                            onLogout = onLogout,
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("party-candidates") {
                        PartyCandidatesScreen(
                            token = token,
                            onBack = { navController.popBackStack() },
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("news") {
                        NewsScreen(
                            token = token,
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable("notifications") {
                        NotificationsScreen(
                            token = token,
                            onBack = { navController.popBackStack() },
                            onUnreadChanged = { appViewModel.setUnreadCount(it) },
                            onError = { appViewModel.setError(it) }
                        )
                    }
                    composable(
                        route = "detail/{title}",
                        arguments = listOf(navArgument("title") { type = NavType.StringType })
                    ) { entry ->
                        val title = entry.arguments?.getString("title").orEmpty()
                        if (title == "Партии") {
                            PartiesScreen(
                                token = token,
                                onError = { appViewModel.setError(it) },
                                onManageCandidates = { navController.navigate("party-candidates") }
                            )
                        } else {
                            StubServiceScreen(
                                title = title,
                                onBack = { navController.popBackStack() },
                                onInfo = { appViewModel.setError(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
