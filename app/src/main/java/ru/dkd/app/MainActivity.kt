package ru.dkd.app

import android.net.Uri
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
import ru.dkd.app.core.network.redeemLoginCode
import ru.dkd.app.core.network.revokeSession
import ru.dkd.app.core.security.ProfileCache
import ru.dkd.app.core.security.SessionStore
import ru.dkd.app.feature.auth.LoginScreen
import ru.dkd.app.feature.documents.DocumentsScreen
import ru.dkd.app.feature.elections.ElectionsScreen
import ru.dkd.app.feature.home.StubGovernmentScreen
import ru.dkd.app.feature.home.StubHomeScreen
import ru.dkd.app.feature.home.StubProfileScreen
import ru.dkd.app.feature.home.StubServiceScreen
import ru.dkd.app.feature.notifications.NotificationsScreen
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
    val navController = rememberNavController()
    val appViewModel: DkdViewModel = viewModel()
    val uiState by appViewModel.state.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(token) { appViewModel.refresh(token) }

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
                        .padding(start = 7.dp, end = 7.dp, top = 8.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        Triple("Главная", "⌂", "home"),
                        Triple("Государство", "⌘", "government"),
                        Triple("Документы", "▤", "documents"),
                        Triple("Выборы", "☑", "elections"),
                        Triple("Профиль", "◉", "profile")
                    )
                    tabs.forEach { (label, icon, route) ->
                        val active = currentRoute == route
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

                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("home") {
                        StubHomeScreen(
                            name = uiState.name,
                            role = uiState.role,
                            onOpen = { navController.navigate("detail/\${Uri.encode(it)}") },
                            onState = { navController.navigate("government") }
                        )
                    }
                    composable("government") {
                        StubGovernmentScreen(onOpen = { navController.navigate("detail/\${Uri.encode(it)}") })
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
                        StubProfileScreen(
                            name = uiState.name,
                            role = uiState.role,
                            onLogout = onLogout,
                            onOpen = { navController.navigate("detail/\${Uri.encode(it)}") }
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
