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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dkd.app.core.design.*


private val GovBackground = Color(0xFFF3F7FC)
private val GovCard = Color(0xFFFFFFFF)
private val GovText = Color(0xFF172B4D)
private val GovMuted = Color(0xFF64758B)
private val GovBlue = Color(0xFF0B63CE)
private val GovLine = Color(0xFFDCE6F1)
private val GovGreen = Color(0xFF168653)
private val GovRed = Color(0xFFD94848)


@Composable
internal fun StubSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = GovText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) {
            Text(action, color = GovBlue, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun HomeScreen(name: String, role: String, onOpen: (String) -> Unit, onState: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val shortcuts = listOf(
        Triple("Государственная Дума", "Состав, заседания и законы", "§"),
        Triple("Выборы", "Официальные бюллетени", "◉"),
        Triple("Новости", "События и решения ДКД", "✦"),
        Triple("Партии", "Политические объединения", "♟"),
        Triple("Заявления", "Обращения и гражданство", "✉"),
        Triple("Президентская администрация", "Указы и кадровые решения", "♛")
    )
    Column(
        Modifier.fillMaxSize().background(GovBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ГОСУДАРСТВЕННЫЕ УСЛУГИ  /  ДКД", color = GovBlue, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Добро пожаловать", color = GovMuted, fontSize = 13.sp)
                Text(name.substringBefore(" ").ifBlank { name }, color = GovText,
                    fontSize = 29.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(
                Modifier.size(58.dp).background(
                    Brush.linearGradient(listOf(GovBlue, GovBlue, Color(0xFF51458D))),
                    RoundedCornerShape(20.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", color = GovText, fontSize = 29.sp)
            }
        }
        Spacer(Modifier.height(19.dp))
        Surface(
            color = GovBlue, shape = RoundedCornerShape(27.dp),
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, Color(0x776D8FFF), RoundedCornerShape(27.dp))
                .clickable { onOpen("Профиль") }
        ) {
            Box(Modifier.background(
                Brush.linearGradient(listOf(Color(0xFF0B63CE), Color(0xFF2679D8), Color(0xFF4A91E5)))
            )) {
                Column(Modifier.padding(horizontal = 21.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ВАШ СТАТУС В ГОСУДАРСТВЕ", color = GovMuted,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
                            modifier = Modifier.weight(1f))
                        Surface(color = Color(0x3363D9A0), shape = RoundedCornerShape(30.dp)) {
                            Text("● АКТИВЕН", color = GovGreen, fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                    Text(role.ifBlank { "Гражданин" }, color = Color.White,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Профиль, полномочия и персональные документы",
                        color = Color(0xFFEAF3FF), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Открыть профиль", color = Color.White,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(7.dp))
                        Text("↗", color = GovBlue, fontSize = 16.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(17.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            placeholder = { Text("Поиск по услугам и разделам", color = GovMuted, fontSize = 12.sp) },
            leadingIcon = { Text("⌕", color = GovBlue, fontSize = 25.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GovText, unfocusedTextColor = GovText,
                focusedBorderColor = GovBlue, unfocusedBorderColor = GovLine,
                cursorColor = GovBlue, focusedContainerColor = GovCard,
                unfocusedContainerColor = GovCard
            )
        )
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.trim()
            val results = (shortcuts.map { it.first to it.second } +
                listOf("Профиль" to "Личные данные и статус", "Документы" to "Паспорт и удостоверения",
                    "Панель администратора" to "Управление системой"))
                .distinctBy { it.first }
                .filter { it.first.contains(query, true) || it.second.contains(query, true) }
            Surface(color = GovCard, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, GovLine, RoundedCornerShape(17.dp))) {
                Column(Modifier.padding(12.dp)) {
                    if (results.isEmpty()) {
                        Text("Ничего не найдено", color = GovMuted, fontSize = 12.sp)
                    } else results.forEach { result ->
                        TextButton(onClick = { onOpen(result.first); searchQuery = "" },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(result.first, color = GovText, modifier = Modifier.weight(1f))
                            Text("›", color = GovBlue, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Государство", color = GovText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Основные разделы и сервисы", color = GovMuted, fontSize = 11.sp)
            }
            TextButton(onClick = { onOpen("Меню") }) { Text("Все разделы  →", color = GovBlue, fontSize = 10.sp) }
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
            color = GovCard, shape = RoundedCornerShape(19.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, GovLine, RoundedCornerShape(19.dp))
                .clickable { onOpen("Панель администратора") }
        ) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(43.dp).background(Color(0xFFE4F0FF), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) {
                    Text("⚙", color = GovBlue, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Панель администратора", color = GovText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Управление участниками и системой", color = GovMuted, fontSize = 10.sp)
                }
                Text("›", color = GovBlue, fontSize = 24.sp)
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
        color = GovCard,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .height(178.dp)
            .border(1.dp, GovLine, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier.size(54.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFFE4F0FF), Color(0xFFD7E9FF))), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, color = GovBlue, fontSize = 29.sp, fontWeight = FontWeight.Medium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title, color = GovText, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 19.sp, maxLines = 2
                )
                Text(
                    subtitle, color = GovMuted, fontSize = 11.sp,
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
    Column(Modifier.fillMaxSize().background(GovBackground).verticalScroll(rememberScrollState()).padding(18.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("НАВИГАЦИЯ  /  ДКД", color = GovBlue, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text("Все разделы", color = GovText, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("$name  ·  $role", color = GovMuted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        sections.forEach { item ->
            Surface(
                color = GovCard, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
                    .border(1.dp, GovLine, RoundedCornerShape(17.dp))
                    .clickable { onOpen(item.first) }
            ) {
                Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 17.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(item.third, color = GovBlue, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.first, color = GovText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = GovMuted, fontSize = 10.sp)
                    }
                    if (item.first == "Уведомления" && unreadCount > 0) {
                        Surface(color = GovRed, shape = RoundedCornerShape(20.dp)) {
                            Text(if (unreadCount > 99) "99+" else unreadCount.toString(),
                                color = GovText, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    } else Text("›", color = GovBlue, fontSize = 23.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
internal fun StubServiceScreen(title: String, onBack: () -> Unit, onInfo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(GovBackground).verticalScroll(rememberScrollState()).padding(17.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = GovBlue) }
        Spacer(Modifier.height(9.dp))
        Text("ГОСУДАРСТВЕННЫЙ СЕРВИС  /  ДКД", color = GovBlue, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(title, color = GovText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0B63CE), Color(0xFF2679D8), Color(0xFF4A91E5))))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цифровые услуги ДКД", color = GovText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Единое пространство государственных сервисов виртуального государства.", color = Color(0xFFEAF3FF), fontSize = 11.sp)
                Text("NEXORA GROUP", color = GovBlue, fontSize = 9.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        StubEmptyState("✧", "Раздел готовится к запуску", "Это пока демонстрационный экран. Реальные данные и действия появятся после подключения и проверки соответствующего сервиса.")
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { onInfo("Этот раздел пока демонстрационный. Действия появятся после подключения сервиса.") },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GovBlue)
        ) { Text("Понятно", color = GovText) }
    }
}

@Composable
internal fun StubEmptyState(icon: String, title: String, subtitle: String) {
    Surface(
        color = GovCard, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, GovLine, RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(54.dp).background(Color(0xFFE4F0FF), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = GovBlue, fontSize = 25.sp)
            }
            Text(title, color = GovText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = GovMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
