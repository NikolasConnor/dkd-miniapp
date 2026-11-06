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

@Composable
private fun AuthenticatedApp(token: String, logoutError: String?, onLogout: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    val webView = remember(token) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(AndroidSessionBridge { token }, "AndroidDKD")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                    if (newProgress >= 100) loadError = false
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    if (uri.scheme == "https" && uri.host == APP_HOST) return false
                    if (request.isForMainFrame && uri.scheme in listOf("https", "http", "mailto", "tel")) {
                        return try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) loadError = true
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    handler?.cancel()
                    loadError = true
                }
            }
        }.also { view ->
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            CookieManager.getInstance().setCookie(
                API_BASE_URL,
                "dkd_session=$token; Path=/; Secure; HttpOnly; SameSite=Lax"
            ) {
                CookieManager.getInstance().flush()
                view.post { view.loadUrl(API_BASE_URL) }
            }
        }
    }

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    Column(Modifier.fillMaxSize().background(Navy)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ДКД", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Самостоятельное приложение", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = { webView.reload() }) { Text("Обновить") }
            TextButton(onClick = onLogout) { Text("Выйти", color = Amber) }
        }
        if (progress in 1..99) LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = Blue,
            trackColor = Panel
        )
        if (logoutError != null) {
            Surface(color = Color(0xFF4A2B35), modifier = Modifier.fillMaxWidth()) {
                Text(logoutError, Modifier.padding(12.dp), color = White, fontSize = 12.sp)
            }
        }
        if (loadError) {
            Surface(color = Color(0xFF4A2B35), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Не удалось загрузить ДКД. Проверьте Wi-Fi или мобильный интернет и нажмите «Обновить».",
                    Modifier.padding(12.dp), color = White, fontSize = 12.sp
                )
            }
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { }
        )
    }
}
