package ru.dkd.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF090A0D)
private val Panel = Color(0xFF171A20)
private val Blue = Color(0xFF9ABEFF)
private val Muted = Color(0xFFAAB2C2)
private val White = Color(0xFFF5F5F2)

@Composable
internal fun LoginScreen(
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
