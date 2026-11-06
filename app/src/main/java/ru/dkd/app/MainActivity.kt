package ru.dkd.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1A2638)
private val Blue = Color(0xFF8DBBFA)
private val Muted = Color(0xFFA8B5C7)
private val White = Color(0xFFF4F7FC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DKDApp() }
    }
}

private data class Destination(val title: String, val emoji: String, val subtitle: String)

@Composable
private fun DKDApp() {
    var page by remember { mutableStateOf("Главная") }
    var notice by remember { mutableStateOf<String?>(null) }
    val destinations = listOf(
        Destination("Мой профиль", "👤", "Личные сведения и статус"),
        Destination("Государственная Дума", "🏛️", "Парламент и законопроекты"),
        Destination("Правительство", "⚖️", "Государственное управление"),
        Destination("Документы", "📄", "Удостоверения и решения"),
        Destination("Новости", "📰", "События ДКД"),
        Destination("Настройки", "⚙️", "Параметры приложения")
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, onPrimary = Navy, background = Navy,
            surface = Panel, onSurface = White, secondary = Muted
        )
    ) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = White) {
                    listOf("Главная", "Документы", "Новости", "Профиль").forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(when (item) {
                                "Главная" -> "⌂"
                                "Документы" -> "▤"
                                "Новости" -> "▣"
                                else -> "●"
                            }, fontSize = 19.sp) },
                            label = { Text(item, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Blue,
                                selectedTextColor = Blue,
                                indicatorColor = Color(0xFF293D58),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = remember { SnackbarHostState() }) }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).background(Color(0xFF263B59), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("🏛️", fontSize = 24.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ДКД", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = White)
                        Text("Цифровая государственная платформа", fontSize = 11.sp, color = Muted)
                    }
                    Surface(color = Color(0xFF254B3A), shape = RoundedCornerShape(50)) {
                        Text("DEMO", Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            color = Color(0xFF9BE2B5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                when (page) {
                    "Главная" -> {
                        Text("Добро пожаловать", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = White)
                        Text("Демократическая Конфедерация Дебилов", color = Muted, fontSize = 13.sp)
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Цифровое пространство ДКД", color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("Здесь собраны основные государственные сервисы и документы.", color = Muted, fontSize = 12.sp)
                                Text("Демонстрационная версия · данные не изменяются", color = Blue, fontSize = 11.sp)
                            }
                        }
                        Text("Государственные разделы", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        destinations.forEach { item ->
                            DestinationCard(item) {
                                page = item.title
                                notice = null
                            }
                        }
                    }
                    "Мой профиль", "Профиль" -> ProfilePage()
                    "Государственная Дума" -> InfoPage("🏛️", "Государственная Дума",
                        "Парламент ДКД", listOf("Состав парламента", "Законопроекты", "Заседания", "Голосования"),
                        onAction = { notice = "Это демонстрационный раздел. Реальные действия пока отключены." })
                    "Правительство" -> InfoPage("⚖️", "Правительство",
                        "Исполнительная власть ДКД", listOf("Президент", "Премьер-министр", "Министерства", "Государственные решения"),
                        onAction = { notice = "Раздел пока работает в режиме просмотра макета." })
                    "Документы" -> InfoPage("📄", "Мои документы",
                        "Электронные документы ДКД", listOf("Удостоверение Президента", "Мандат депутата", "Паспорт гражданина", "Подписанные указы"),
                        onAction = { notice = "Макет документа. Проверка подлинности пока не подключена." })
                    "Новости" -> InfoPage("📰", "Новости ДКД",
                        "Лента государственных событий", listOf("Официальные сообщения", "Решения Правительства", "Работа парламента"),
                        onAction = { notice = "Демонстрационная новость. Сервер новостей пока не подключён." })
                    "Настройки" -> InfoPage("⚙️", "Настройки",
                        "Настройки приложения", listOf("Оформление: тёмное", "Версия: 0.1.0 DEMO", "Подключение к серверу: отключено"),
                        onAction = { notice = "Настройки прототипа. Интеграция будет добавлена позднее." })
                    else -> ProfilePage()
                }
                notice?.let {
                    Surface(color = Color(0xFF293D58), shape = RoundedCornerShape(12.dp)) {
                        Text(it, Modifier.fillMaxWidth().padding(13.dp), color = White, fontSize = 12.sp)
                    }
                }
                Text("ДКД · Android prototype 0.1.0", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun DestinationCard(item: Destination, onClick: () -> Unit) {
    Surface(
        color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(Color(0xFF263B59), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center) { Text(item.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(item.subtitle, color = Muted, fontSize = 11.sp)
            }
            Text("›", color = Blue, fontSize = 27.sp)
        }
    }
}

@Composable
private fun ProfilePage() {
    Text("Мой профиль", fontSize = 26.sp, color = White, fontWeight = FontWeight.Bold)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("👤", fontSize = 48.sp)
            Spacer(Modifier.height(8.dp))
            Text("Демонстрационный гражданин", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Профиль-пример · не авторизован", color = Muted, fontSize = 11.sp)
        }
    }
    listOf("Статус" to "Демонстрационный", "Должность" to "Не назначена", "Репутация" to "—", "Гражданство" to "Не проверено").forEach {
        Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(it.first, color = Muted, fontSize = 13.sp)
                Text(it.second, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun InfoPage(
    emoji: String, title: String, description: String,
    items: List<String>, onAction: () -> Unit
) {
    Text(title, fontSize = 26.sp, color = White, fontWeight = FontWeight.Bold)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(emoji, fontSize = 35.sp)
            Text(description, color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Демонстрационный режим. Данные и государственные процессы не изменяются.",
                color = Muted, fontSize = 12.sp)
        }
    }
    items.forEach { item ->
        Surface(color = Panel, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAction)) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item, color = White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("›", color = Blue, fontSize = 24.sp)
            }
        }
    }
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29476B))) {
        Text("Открыть демонстрационный раздел")
    }
}
