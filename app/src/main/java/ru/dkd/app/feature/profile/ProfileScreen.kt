package ru.dkd.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import ru.dkd.app.core.design.*

@Composable
internal fun ProfileScreen(token: String, onLogout: () -> Unit, onError: (String) -> Unit) {
    val viewModel: ProfileViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    val profile = state.profile
    Column(
        Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ЛИЧНЫЙ КАБИНЕТ", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Профиль", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)

        if (state.loading && profile == null) {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                    Spacer(Modifier.width(12.dp))
                    Text("Загружаем профиль…", color = Muted, fontSize = 11.sp)
                }
            }
        } else if (profile == null) {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Профиль недоступен", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Не удалось получить актуальные сведения с сервера.", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = { refresh++ }) { Text("Повторить", color = Purple) }
                }
            }
        } else {
            Surface(
                color = Panel2,
                shape = RoundedCornerShape(23.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(23.dp))
            ) {
                Box(Modifier.background(Brush.linearGradient(listOf(Panel2, CardAlt)))) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(66.dp).background(Brush.linearGradient(listOf(Blue, Purple)), RoundedCornerShape(21.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text(profile.avatarEmoji, fontSize = 32.sp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(profile.fullName, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(profile.role, color = Muted, fontSize = 11.sp)
                            Text("УЧЁТНАЯ ЗАПИСЬ ДКД", color = Pink, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            ProfileInfoRow("ГРАЖДАНСТВО", profile.citizenship)
            ProfileInfoRow("ПОЛИТИЧЕСКАЯ ПАРТИЯ", profile.partyName ?: "Не состоит в партии")
            Surface(color = CardColor, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(17.dp))) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Уведомления о новостях", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Получать индивидуальные уведомления о публикациях ДКД", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        if (state.savingPreference) Text("Сохраняем настройку…", color = Purple, fontSize = 9.sp)
                    }
                    Switch(
                        checked = profile.newsNotificationsEnabled,
                        onCheckedChange = { viewModel.setNewsNotifications(token, it) },
                        enabled = !state.savingPreference
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)
        ) { Text("Выйти из аккаунта") }
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String) {
    Surface(color = CardColor, shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(15.dp))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
