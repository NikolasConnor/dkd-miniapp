package ru.dkd.app.feature.home

import androidx.compose.foundation.background
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
            Text(action, color = Blue, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun StubHomeScreen(name: String, role: String, onOpen: (String) -> Unit, onState: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(21.dp))
        Text("ВИРТУАЛЬНОЕ ГОСУДАРСТВО  ·  NEXORA", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        Text("Привет, $name", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Добро пожаловать домой", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen("Статус гражданина") }) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ВАШ ЦИФРОВОЙ СТАТУС", color = Blue, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Text("Гражданин ДКД", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(role, color = Muted, fontSize = 11.sp)
                    }
                    Box(Modifier.size(54.dp).background(Color(0xFF243B5D), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Text("✦", color = Blue, fontSize = 29.sp)
                    }
                }
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Открыть цифровой профиль", color = Blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("↗", color = Blue, fontSize = 19.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        StubSectionTitle("Государственные услуги", "Все услуги", onState)
        Spacer(Modifier.height(10.dp))
        val services = listOf(
            Triple("Выборы", "☑", "Голосования и итоги"),
            Triple("Документы", "▤", "Ваши удостоверения"),
            Triple("Государство", "⌘", "Органы власти"),
            Triple("Партии", "♟", "Политические объединения"),
            Triple("Законы", "§", "Законодательство"),
            Triple("Обращения", "✉", "Заявления граждан")
        )
        services.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    Surface(color = CardColor, shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.weight(1f).padding(bottom = 10.dp).clickable { onOpen(item.first) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.second, color = Blue, fontSize = 25.sp)
                            Text(item.first, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.third, color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StubSectionTitle("Новости государства", "Все новости") { onOpen("Новости") }
        Spacer(Modifier.height(9.dp))
        Surface(color = CardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen("Новости") }) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2B43)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ДКД", color = Color(0xFFBBD2F7), fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                        Text("DIGITAL STATE", color = Muted, fontSize = 8.sp, letterSpacing = 3.sp)
                    }
                }
                Text("Главные события ДКД", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Официальные сообщения, решения и обновления государства", color = Muted, fontSize = 11.sp)
                Text("ОТКРЫТЬ НОВОСТИ  →", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(25.dp))
        Text("NEXORA GROUP  ·  TECHNOLOGY. IDEAS. FUTURE.", color = Color(0xFF61718B), fontSize = 8.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun StubGovernmentScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("NEXORA  /  STATE", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
        Text("Государство", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Институты и управление ДКД", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ДКД", color = Blue, fontSize = 10.sp, letterSpacing = 3.sp)
                Text("Система государственного управления", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Президентская власть · Парламент · Гражданское общество", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf(
            Triple("Президент", "Глава государства и гарант Конституции", "Президент"),
            Triple("Правительство", "Кабинет министров и государственные ведомства", "Правительство"),
            Triple("Государственная Дума", "Депутаты, заседания и законопроекты", "Государственная Дума"),
            Triple("Политические партии", "Объединения и партийные списки", "Партии"),
            Triple("Выборы", "Избирательные кампании и результаты", "Выборы"),
            Triple("Законодательство", "Законы и президентские указы", "Законы")
        ).forEach { item ->
            Surface(color = CardColor, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp).clickable { onOpen(item.third) }) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.first, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.second, color = Muted, fontSize = 10.sp)
                    }
                    Text("↗", color = Blue, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
internal fun StubProfileScreen(name: String, role: String, onLogout: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("NEXORA  /  ACCOUNT", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
        Text("Профиль", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(19.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(66.dp).background(Color(0xFF263B59), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = Blue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(role, color = Muted, fontSize = 11.sp)
                    Text("УЧЁТНАЯ ЗАПИСЬ ДКД", color = Blue, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf("Мои документы", "Настройки приложения", "О приложении Nexora Group").forEach { label ->
            Surface(color = CardColor, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpen(label) }) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("→", color = Blue)
                }
            }
        }
        TextButton(onClick = onLogout) { Text("Выйти из аккаунта", color = Color(0xFFE6A5AF)) }
    }
}

@Composable
internal fun StubServiceScreen(title: String, onBack: () -> Unit, onInfo: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Blue) }
        Spacer(Modifier.height(12.dp))
        Text("ГОСУДАРСТВЕННЫЙ СЕРВИС  /  ДКД", color = Blue, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Surface(color = CardAlt, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цифровые услуги ДКД", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Единое пространство государственных сервисов виртуального государства.", color = Muted, fontSize = 11.sp)
                Text("NEXORA GROUP", color = Blue, fontSize = 9.sp, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        StubEmptyState("✧", "Раздел готовится к запуску", "Это демонстрационная заглушка нового интерфейса. Реальные данные и действия подключаются следующим этапом.")
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onInfo("Это пока демонстрационный экран. Действия появятся после подключения сервиса.") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Понятно")
        }
    }
}

@Composable
internal fun StubEmptyState(icon: String, title: String, subtitle: String) {
    Surface(color = CardColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(54.dp).background(Color(0xFF203452), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Blue, fontSize = 25.sp)
            }
            Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
