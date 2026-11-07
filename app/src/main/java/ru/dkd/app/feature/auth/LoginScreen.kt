package ru.dkd.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dkd.app.core.design.*

@Composable
internal fun LoginScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onLogin: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF292257), Navy, Navy))
        ).padding(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(78.dp).background(
                    Brush.linearGradient(listOf(Blue, Purple)),
                    RoundedCornerShape(24.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("🏛️", fontSize = 38.sp)
            }
            Text("ДКД", color = White, fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Цифровое пространство государства", color = Muted, fontSize = 13.sp)
            Surface(
                color = Panel.copy(alpha = .96f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(24.dp))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(21.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Вход в аккаунт", color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Введите одноразовый код привязки, выданный администратором ДКД. Код действует 15 минут и используется один раз.",
                        color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Код привязки") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple,
                            unfocusedBorderColor = LineColor,
                            focusedLabelColor = Purple,
                            cursorColor = Purple,
                            focusedTextColor = White,
                            unfocusedTextColor = White
                        )
                    )
                    error?.let {
                        Text(it, color = Bad, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth().height(51.dp),
                        enabled = !busy && code.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = White,
                            disabledContainerColor = Purple.copy(alpha = .35f)
                        )
                    ) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.size(19.dp), strokeWidth = 2.dp, color = White
                        ) else Text("Войти в ДКД", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                "После привязки отдельное приложение работает без Telegram.",
                color = Muted, fontSize = 11.sp
            )
            Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF737CA8), fontSize = 9.sp, letterSpacing = 1.1.sp)
        }
    }
}
