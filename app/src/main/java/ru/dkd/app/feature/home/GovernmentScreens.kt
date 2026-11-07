package ru.dkd.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dkd.app.core.design.*

@Composable
internal fun StubSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) {
            Text(action, color = Purple, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun HomeScreen(name: String, role: String, onOpen: (String) -> Unit, onState: () -> Unit) {
    val services = listOf(
        Triple("Выборы", "Голосования и официальные итоги", "☑"),
        Triple("Документы", "Паспорт и действующие удостоверения", "▤"),
        Triple("Государство", "Парламент и органы власти", "⌘"),
        Triple("Партии", "Политические объединения ДКД", "♟")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 17.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("ЦИФРОВОЕ ГОСУДАРСТВО  ·  ДКД", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        Text("Привет, $name", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Ваше пространство гражданина ДКД", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(17.dp))
        Surface(
            shape = RoundedCornerShape(25.dp),
            color = Panel2,
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(25.dp))
                .clickable { onOpen("Профиль") }
        ) {
            Box(
                Modifier.background(Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF25234D), Color(0xFF3B2860))))
                    .padding(19.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ВАШ СТАТУС В ДКД", color = Color(0xFFC5C7FF), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(role, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("Статус из вашего профиля", color = Color(0xFFD0D3F3), fontSize = 12.sp)
                        }
                        Box(Modifier.size(57.dp).background(Color(0x338B7CFF), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            Text("✦", color = Pink, fontSize = 31.sp)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Открыть профиль  ↗", color = Color(0xFFC5C7FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(21.dp))
        StubSectionTitle("Государственные разделы", "Государство", onState)
        Spacer(Modifier.height(10.dp))
        services.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    Surface(
                        color = CardColor, shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.weight(1f).padding(bottom = 10.dp)
                            .border(1.dp, LineColor, RoundedCornerShape(19.dp))
                            .clickable { onOpen(item.first) }
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.third, color = Purple, fontSize = 25.sp)
                            Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.second, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Surface(
            color = CardColor, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(20.dp))
                .clickable { onOpen("Новости") }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(45.dp).background(Color(0x338B7CFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("✧", color = Pink, fontSize = 24.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Новости государства", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Официальные сообщения и события ДКД", color = Muted, fontSize = 11.sp)
                }
                Text("→", color = Purple, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(23.dp))
        Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF737CA8), fontSize = 8.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(17.dp))
    }
}

@Composable
internal fun StubServiceScreen(title: String, onBack: () -> Unit, onInfo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Purple) }
        Spacer(Modifier.height(9.dp))
        Text("ГОСУДАРСТВЕННЫЙ СЕРВИС  /  ДКД", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(title, color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF25234D), Color(0xFF3B2860))))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цифровые услуги ДКД", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Единое пространство государственных сервисов виртуального государства.", color = Color(0xFFD0D3F3), fontSize = 11.sp)
                Text("NEXORA GROUP", color = Pink, fontSize = 9.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        StubEmptyState("✧", "Раздел готовится к запуску", "Это пока демонстрационный экран. Реальные данные и действия появятся после подключения и проверки соответствующего сервиса.")
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onInfo("Этот раздел пока демонстрационный. Действия появятся после подключения сервиса.") },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple)
        ) { Text("Понятно", color = White) }
    }
}

@Composable
internal fun StubEmptyState(icon: String, title: String, subtitle: String) {
    Surface(
        color = CardColor, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(54.dp).background(Color(0xFF34316B), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Pink, fontSize = 25.sp)
            }
            Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
