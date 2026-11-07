package ru.dkd.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Blue, background = Navy, surface = Panel, onSurface = White
            )) {
                var token by remember { mutableStateOf(getPreferences(Context.MODE_PRIVATE).getString("session_token", null)) }
                if (token.isNullOrBlank()) {
                    LoginScreen(
                        onLogin = { code -> redeemCode(code) },
                        onLoggedIn = { newToken ->
                            getPreferences(Context.MODE_PRIVATE).edit().putString("session_token", newToken).apply()
                            getSharedPreferences("dkd_session", Context.MODE_PRIVATE).edit().putString("session_token", newToken).apply()
                            token = newToken
                        }
                    )
                } else {
                    ConnectedApp(
                        token = token!!,
                        onLogout = {
                            revokeSession(token!!)
                            getPreferences(Context.MODE_PRIVATE).edit().remove("session_token").apply()
                            getSharedPreferences("dkd_session", Context.MODE_PRIVATE).edit().remove("session_token").apply()
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            token = null
                        }
                    )
                }
            }
        }
    }

    private suspend fun redeemCode(code: String): String = withContext(Dispatchers.IO) {
        val connection = (URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/android/auth/redeem").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("code", code.trim()).toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw IllegalStateException(json.optString("detail").ifBlank { "Не удалось войти (HTTP $status)." })
            json.optString("session_token").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Сервер не выдал сессию приложения.")
        } finally {
            connection.disconnect()
        }
    }

    private fun revokeSession(token: String) {
        Thread {
            runCatching {
                val connection = (URL("${BuildConfig.DKD_API_BASE_URL.trimEnd('/')}/api/android/auth/logout").openConnection() as HttpURLConnection)
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.responseCode
                connection.disconnect()
            }
        }.start()
    }
}

@Composable
private fun LoginScreen(
    onLogin: suspend (String) -> String,
    onLoggedIn: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🏛️", fontSize = 54.sp)
            Spacer(Modifier.height(12.dp))
            Text("ДКД", color = White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Самостоятельное приложение государства", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(28.dp))
            Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("Вход в ДКД", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Попросите администратора выдать вам одноразовый код привязки в разделе управления участниками Mini App. Код действует 15 минут.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }.take(64); error = null },
                        label = { Text("Код входа") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    error?.let { Text(it, color = Color(0xFFFF9AA9), fontSize = 12.sp) }
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                try { onLoggedIn(onLogin(code)) }
                                catch (e: Exception) { error = e.message ?: "Не удалось подключиться к серверу ДКД." }
                                finally { loading = false }
                            }
                        },
                        enabled = code.length >= 8 && !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loading) "Подключаемся…" else "Войти в ДКД") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Для входа нужен интернет. После входа сессия сохранится на устройстве.", color = Muted, fontSize = 11.sp)
        }
    }
}

private class AndroidBridge(private val activeSession: Boolean) {
    @JavascriptInterface
    fun hasSession(): Boolean = activeSession
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConnectedApp(token: String, onLogout: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val baseUrl = BuildConfig.DKD_API_BASE_URL.trimEnd('/')
    LaunchedEffect(token) {
        context.getSharedPreferences("dkd_session", Context.MODE_PRIVATE).edit().putString("session_token", token).apply()
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcmToken -> PushTokenRegistrar.register(context, token, fcmToken) }
    }
    Column(Modifier.fillMaxSize().background(Navy)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ДКД", color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Подключено", color = Green, fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onLogout) { Text("Выйти", color = Muted) }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    addJavascriptInterface(AndroidBridge(token.isNotBlank()), "AndroidDKD")
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setCookie(baseUrl, "dkd_session=$token; Path=/; Secure; HttpOnly; SameSite=Lax") {
                        cookies.flush()
                        loadUrl(baseUrl)
                    }
                }
            },
            update = { }
        )
    }
}
