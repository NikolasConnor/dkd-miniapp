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
private fun StateCard(
    title: String,
    subtitle: String,
    icon: String,
    onClick: () -> Unit
) {
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(19.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(19.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(45.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF34316B), Color(0xFF263B68))),
                    RoundedCornerShape(14.dp)
                ),
                contentAlignment = Alignment.Center
            ) { Text(icon, color = White, fontSize = 22.sp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Text("›", color = Purple, fontSize = 25.sp)
        }
    }
}

@Composable
internal fun StubHomeScreen(name: String, role: String, onOpen: (String) -> Unit, onState: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 17.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("ЦИФРОВОЕ ГОСУДАРСТВО  ·  ДКД", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        Text("Привет, $name", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Рады видеть вас снова", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(17.dp))
        Surface(
            shape = RoundedCornerShape(25.dp),
            color = Panel2,
            modifier = Modifier.fillMaxWidth().border(
                1.dp, Color(0xFF55508E), RoundedCornerShape(25.dp)
            ).clickable { onOpen("Статус гражданина") }
        ) {
            Box(
                Modifier.background(
                    Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF25234D), Color(0xFF3B2860)))
                ).padding(19.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ВАШ ЦИФРОВОЙ СТАТУС", color = Color(0xFFC5C7FF), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Гражданин ДКД", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(role, color = Color(0xFFD0D3F3), fontSize = 12.sp)
                        }
                        Box(
                            Modifier.size(57.dp).background(Color(0x338B7CFF), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("✦", color = Pink, fontSize = 31.sp) }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Открыть цифровой профиль   ↗", color = Color(0xFFC5C7FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(21.dp))
        StubSectionTitle("Государственные услуги", "Все услуги", onState)
        Spacer(Modifier.height(10.dp))
        val services = listOf(
            Triple("Выборы", "Голосования и итоги", "☑"),
            Triple("Документы", "Ваши удостоверения", "▤"),
            Triple("Государство", "Органы власти", "⌘"),
            Triple("Партии", "Политические объединения", "♟"),
            Triple("Законы", "Законодательство", "§"),
            Triple("Обращения", "Заявления граждан", "✉")
        )
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
        Spacer(Modifier.height(7.dp))
        StubSectionTitle("Новости государства", "Все новости") { onOpen("Новости") }
        Spacer(Modifier.height(9.dp))
        Surface(
            color = CardColor, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(20.dp))
                .clickable { onOpen("Новости") }
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF3B2860)))),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ДКД", color = Color(0xFFBBD2F7), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                        Text("DIGITAL STATE", color = Color(0xFFC5C7FF), fontSize = 8.sp, letterSpacing = 3.sp)
                    }
                }
                Text("Главные события ДКД", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Официальные сообщения, решения и обновления государства", color = Muted, fontSize = 11.sp)
                Text("ОТКРЫТЬ НОВОСТИ  →", color = Purple, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(23.dp))
        Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF737CA8), fontSize = 8.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(17.dp))
    }
}

@Composable
internal fun StubGovernmentScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)) {
        Text("ГОСУДАРСТВЕННЫЕ ИНСТИТУТЫ", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Государство", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Органы власти и управление ДКД", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(17.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF25234D), Color(0xFF3B2860))))
                .padding(19.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("ДКД  /  STATE", color = Pink, fontSize = 9.sp, letterSpacing = 2.sp)
                Text("Система государственного управления", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Президентская власть · Парламент · Граждане", color = Color(0xFFD0D3F3), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        val sections = listOf(
            Triple("Президент", "Глава государства и гарант Конституции", "♛"),
            Triple("Правительство", "Премьер-министр и министерства", "⌂"),
            Triple("Государственная Дума", "Депутаты, заседания и законопроекты", "▥"),
            Triple("Политические партии", "Объединения и партийные списки", "♟"),
            Triple("Выборы", "Избирательные кампании и результаты", "☑"),
            Triple("Законодательство", "Законы и подписанные указы", "§")
        )
        sections.forEach { item ->
            Column(Modifier.padding(bottom = 9.dp)) {
                StateCard(item.first, item.second, item.third) { onOpen(item.first) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Данные и действия разделов будут подключаться к действующим сервисам ДКД.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
internal fun StubProfileScreen(name: String, role: String, onLogout: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(17.dp)) {
        Text("ЛИЧНЫЙ КАБИНЕТ", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Профиль", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(17.dp))
        Surface(
            color = Panel2, shape = RoundedCornerShape(23.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(23.dp))
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(66.dp).background(Brush.linearGradient(listOf(Blue, Purple)), RoundedCornerShape(21.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.take(1).uppercase(), color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(role, color = Muted, fontSize = 11.sp)
                    Text("УЧЁТНАЯ ЗАПИСЬ ДКД", color = Pink, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(17.dp))
        listOf("Мои документы", "Настройки приложения", "О приложении NEXORA GROUP").forEach { label ->
            Column(Modifier.padding(bottom = 8.dp)) {
                StateCard(label, "Перейти к разделу", "›") { onOpen(label) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)
        ) { Text("Выйти из аккаунта") }
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
