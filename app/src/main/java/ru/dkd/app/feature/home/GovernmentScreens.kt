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
    val shortcuts = listOf(
        Triple("Государственная Дума", "Состав, заседания и законы", "§"),
        Triple("Выборы", "Официальные бюллетени", "◉"),
        Triple("Новости", "События и решения ДКД", "✦"),
        Triple("Партии", "Политические объединения", "♟"),
        Triple("Заявления", "Обращения и гражданство", "✉"),
        Triple("Президентская администрация", "Указы и кадровые решения", "♛")
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ЛИЧНЫЙ КАБИНЕТ  /  ДКД", color = Pink, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Добро пожаловать", color = Muted, fontSize = 13.sp)
                Text(name.substringBefore(" ").ifBlank { name }, color = White,
                    fontSize = 29.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(
                Modifier.size(58.dp).background(
                    Brush.linearGradient(listOf(Blue, Purple, Color(0xFF51458D))),
                    RoundedCornerShape(20.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", color = White, fontSize = 29.sp)
            }
        }
        Spacer(Modifier.height(19.dp))
        Surface(
            color = Panel2, shape = RoundedCornerShape(27.dp),
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, Color(0x776D8FFF), RoundedCornerShape(27.dp))
                .clickable { onOpen("Профиль") }
        ) {
            Box(Modifier.background(
                Brush.linearGradient(listOf(Color(0xFF29386C), Color(0xFF302B60), Color(0xFF20264A)))
            )) {
                Column(Modifier.padding(horizontal = 21.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ВАШ СТАТУС В ГОСУДАРСТВЕ", color = Color(0xFFD0D5FF),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
                            modifier = Modifier.weight(1f))
                        Surface(color = Color(0x3363D9A0), shape = RoundedCornerShape(30.dp)) {
                            Text("● АКТИВЕН", color = Good, fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                    Text(role.ifBlank { "Гражданин" }, color = White,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Профиль, полномочия и персональные документы",
                        color = Color(0xFFD0D3F3), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Открыть профиль", color = Color(0xFFD6D8FF),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(7.dp))
                        Text("↗", color = Pink, fontSize = 16.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Государство", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Основные разделы и сервисы", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { onOpen("Меню") }) { Text("Все разделы  →", color = Purple, fontSize = 10.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeFeatureCard("Государство", "Органы власти и структура", "⌘",
                Modifier.weight(1f), onState)
            HomeFeatureCard("Документы", "Паспорт и удостоверения", "▤",
                Modifier.weight(1f)) { onOpen("Документы") }
        }
        Spacer(Modifier.height(10.dp))
        shortcuts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    HomeFeatureCard(item.first, item.second, item.third,
                        Modifier.weight(1f)) { onOpen(item.first) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Surface(
            color = CardColor, shape = RoundedCornerShape(19.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(19.dp))
                .clickable { onOpen("Панель администратора") }
        ) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Color(0x338B7CFF), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) {
                    Text("⚙", color = Pink, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Панель администратора", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Управление участниками и системой", color = Muted, fontSize = 10.sp)
                }
                Text("›", color = Purple, fontSize = 24.sp)
            }
        }
        Spacer(Modifier.height(19.dp))
        Text("NEXORA GROUP  ·  DIGITAL STATE PLATFORM", color = Color(0xFF737CA8),
            fontSize = 8.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HomeFeatureCard(
    title: String, subtitle: String, icon: String, modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .height(178.dp)
            .border(1.dp, Color(0xFF3A426F), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier.size(54.dp)
                    .background(Brush.linearGradient(listOf(Color(0x554F70D5), Color(0x558B7CFF))), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, color = Pink, fontSize = 29.sp, fontWeight = FontWeight.Medium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title, color = White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 19.sp, maxLines = 2
                )
                Text(
                    subtitle, color = Muted, fontSize = 11.sp,
                    lineHeight = 15.sp, maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun MenuScreen(name: String, role: String, unreadCount: Int, onOpen: (String) -> Unit) {
    val sections = listOf(
        Triple("Государство", "Структура и органы власти", "⌘"),
        Triple("Государственная Дума", "Депутаты, заседания и законы", "§"),
        Triple("Президентская администрация", "Указы и государственные решения", "♛"),
        Triple("Выборы", "Бюллетени и результаты голосования", "◉"),
        Triple("Партии", "Объединения и списки кандидатов", "♟"),
        Triple("Заявления", "Обращения и гражданство", "✉"),
        Triple("Новости", "Официальная лента ДКД", "✦"),
        Triple("Документы", "Паспорт и государственные удостоверения", "▤"),
        Triple("Профиль", "Личные данные и статус", "◉"),
        Triple("Уведомления", "Важные события и сообщения", "♧"),
        Triple("Панель администратора", "Управление системой и участниками", "⚙")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("НАВИГАЦИЯ  /  ДКД", color = Pink, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text("Все разделы", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("$name  ·  $role", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        sections.forEach { item ->
            Surface(
                color = CardColor, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    .border(1.dp, LineColor, RoundedCornerShape(17.dp))
                    .clickable { onOpen(item.first) }
            ) {
                Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 17.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(item.third, color = Pink, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = Muted, fontSize = 10.sp)
                    }
                    if (item.first == "Уведомления" && unreadCount > 0) {
                        Surface(color = Bad, shape = RoundedCornerShape(20.dp)) {
                            Text(if (unreadCount > 99) "99+" else unreadCount.toString(),
                                color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    } else Text("›", color = Purple, fontSize = 23.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
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
