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
        Triple("Государственная Дума", "Заседания, законы и депутаты", "§"),
        Triple("Выборы", "Бюллетени и результаты голосования", "◉"),
        Triple("Документы", "Паспорт и официальные удостоверения", "▤"),
        Triple("Новости", "Последние события государства", "✦")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ЦИФРОВОЕ ГОСУДАРСТВО", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                Spacer(Modifier.height(5.dp))
                Text("Добро пожаловать,", color = Muted, fontSize = 13.sp)
                Text(name, color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.size(54.dp).background(Brush.linearGradient(listOf(Blue, Purple)), RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center) {
                Text("✦", color = White, fontSize = 27.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            color = Panel2, shape = RoundedCornerShape(25.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(25.dp))
                .clickable { onOpen("Профиль") }
        ) {
            Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF252B61), Color(0xFF302653), Color(0xFF20294B))))
                .padding(19.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ЛИЧНЫЙ КАБИНЕТ", color = Color(0xFFC9CCFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                            modifier = Modifier.weight(1f))
                        Surface(color = Color(0x3385DDB4), shape = RoundedCornerShape(20.dp)) {
                            Text("АКТИВЕН", color = Good, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                    Text(role, color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Ваш статус и государственные документы", color = Color(0xFFD0D3F3), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Открыть профиль", color = Color(0xFFC5C7FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(7.dp))
                        Text("↗", color = Pink, fontSize = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(23.dp))
        StubSectionTitle("Быстрый доступ", "Все разделы") { onOpen("Меню") }
        Spacer(Modifier.height(11.dp))
        shortcuts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    Surface(color = CardColor, shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.weight(1f).padding(bottom = 10.dp)
                            .border(1.dp, LineColor, RoundedCornerShape(19.dp)).clickable { onOpen(item.first) }) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(39.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(item.third, color = Pink, fontSize = 22.sp)
                            }
                            Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.second, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Surface(color = CardColor, shape = RoundedCornerShape(19.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(19.dp))
                .clickable { onOpen("Государство") }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Color(0x338B7CFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("⌘", color = Pink, fontSize = 23.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Органы государственной власти", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Президент, Правительство и парламент", color = Muted, fontSize = 10.sp)
                }
                Text("→", color = Purple, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("NEXORA GROUP  ·  DIGITAL STATE PLATFORM", color = Color(0xFF737CA8), fontSize = 8.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun MenuScreen(name: String, role: String, unreadCount: Int, onOpen: (String) -> Unit) {
    val sections = listOf(
        Triple("Государство", "Структура и органы власти", "⌘"),
        Triple("Государственная Дума", "Состав, заседания и законодательство", "§"),
        Triple("Президентская администрация", "Указы и государственные решения", "♛"),
        Triple("Выборы", "Голосования и официальные результаты", "◉"),
        Triple("Партии", "Политические объединения и списки", "♟"),
        Triple("Заявления", "Обращения и гражданство", "✉"),
        Triple("Новости", "Официальная лента ДКД", "✦"),
        Triple("Профиль", "Личные данные и статус", "◉"),
        Triple("Уведомления", "Важные события и сообщения", "♧"),
        Triple("Панель администратора", "Управление системой и участниками", "⚙")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("НАВИГАЦИЯ", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text("Все разделы", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("$name · $role", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(17.dp))
        sections.forEach { item ->
            Surface(color = CardColor, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    .border(1.dp, LineColor, RoundedCornerShape(17.dp)).clickable { onOpen(item.first) }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text(item.third, color = Pink, fontSize = 21.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = Muted, fontSize = 10.sp)
                    }
                    if (item.first == "Уведомления" && unreadCount > 0) {
                        Surface(color = Bad, shape = RoundedCornerShape(20.dp)) {
                            Text(if (unreadCount > 99) "99+" else unreadCount.toString(), color = White,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
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
