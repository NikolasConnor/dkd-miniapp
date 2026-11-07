package ru.dkd.app.feature.government

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.int
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun StateOverviewScreen(
    token: String,
    onOpen: (String) -> Unit,
    onError: (String) -> Unit
) {
    val viewModel: StateViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    val parliament = state.parliament
    val formed = parliament?.bool("formed") ?: false
    val dissolved = parliament?.bool("dissolved") ?: false
    val members = parliament?.array("members") ?: emptyList()
    val chamberParties = parliament?.array("parties") ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("ГОСУДАРСТВЕННЫЕ ИНСТИТУТЫ", color = Pink, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Text("Государство", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("Органы власти и государственное устройство ДКД", color = Muted, fontSize = 12.sp)
        }

        item {
            Box(
                Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(23.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF292B60), Color(0xFF25234D), Color(0xFF3B2860))),
                        RoundedCornerShape(23.dp)
                    ).padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ДКД  /  STATE", color = Pink, fontSize = 9.sp, letterSpacing = 2.sp)
                    Text("Система государственного управления", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Президентская власть · Парламент · Граждане", color = Color(0xFFD0D3F3), fontSize = 11.sp)
                }
            }
        }

        item { SectionTitle("Государственная Дума") }
        item {
            when {
                state.loading && parliament == null -> LoadingCard("Загружаем состав парламента…")
                state.error != null && parliament == null -> InfoCard("Состав парламента недоступен", "Проверьте подключение и повторите обновление.")
                dissolved -> InfoCard("Парламент распущен", "Полномочия текущего созыва прекращены.")
                !formed -> InfoCard("Состав ещё не сформирован", "Состав Государственной Думы появится после завершения парламентских выборов.")
                else -> ParliamentSummary(parliament!!)
            }
        }

        if (formed && members.isNotEmpty()) {
            item { SectionTitle("Депутаты действующего созыва") }
            items(members, key = { "deputy-" + it.optInt("telegram_user_id", it.optInt("id")).toString() + "-" + it.string("full_name") }) { member ->
                Surface(color = CardColor, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(15.dp))) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(37.dp).background(Color(0x338B7CFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text("♟", color = Pink, fontSize = 19.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(member.string("full_name", "Депутат"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(member.string("party_name", "Без партии"), color = Muted, fontSize = 10.sp)
                        }
                        Text("МАНДАТ", color = Good, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (formed && chamberParties.isNotEmpty()) {
            item { SectionTitle("Распределение мест") }
            items(chamberParties, key = { "chamber-" + it.string("name") }) { party ->
                PartySeatCard(party)
            }
        }

        item { SectionTitle("Политические партии") }
        if (state.loading && state.parties.isEmpty()) {
            item { LoadingCard("Загружаем политические партии…") }
        } else if (state.error != null && state.parties.isEmpty()) {
            item { InfoCard("Список партий недоступен", "Проверьте подключение и повторите обновление.") }
        } else if (state.parties.isEmpty()) {
            item { InfoCard("Активных партий нет", "Политические объединения появятся здесь после регистрации.") }
        }
        items(state.parties, key = { "party-" + it.optInt("id").toString() }) { party ->
            Surface(
                color = CardColor, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(17.dp))
                    .clickable { onOpen("Партия: " + party.string("name")) }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text(party.string("emoji", "🎭"), fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(party.string("name", "Политическая партия"), color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Участников: " + party.int("members_count") + "  ·  Председатель: " + party.string("leader_username", "не указан"), color = Muted, fontSize = 9.sp)
                    }
                    Text("›", color = Purple, fontSize = 23.sp)
                }
            }
        }

        item { SectionTitle("Органы государственной власти") }
        item { StateInstitutionCard("Президент", "Глава государства и гарант Конституции", "♛") { onOpen("Президент") } }
        item { StateInstitutionCard("Правительство", "Премьер-министр и министерства", "⌂") { onOpen("Правительство") } }
        item { StateInstitutionCard("Законодательство", "Законы и президентские указы", "§") { onOpen("Законы") } }
        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить сведения", color = Purple)
            }
        }
    }
}

@Composable
private fun ParliamentSummary(parliament: JSONObject) {
    val total = parliament.int("seats_total")
    val occupied = parliament.int("seats_occupied")
    Surface(
        color = CardColor, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("СОСТАВ ДЕЙСТВУЮЩЕГО СОЗЫВА", color = Pink, fontSize = 8.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                    Text("Государственная Дума", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$occupied / $total", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("мест занято", color = Muted, fontSize = 9.sp)
                }
            }
            LinearProgressIndicator(
                progress = { if (total > 0) (occupied.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Purple,
                trackColor = LineColor
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("НАЧАЛО СРОКА", color = Muted, fontSize = 8.sp)
                    Text(parliament.string("term_start").take(10).ifBlank { "—" }, color = White, fontSize = 10.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ОКОНЧАНИЕ СРОКА", color = Muted, fontSize = 8.sp)
                    Text(parliament.string("term_end").take(10).ifBlank { "—" }, color = White, fontSize = 10.sp)
                }
            }
            Text("Количество мест соответствует числу голосов, полученных на выборах; распределение определяется результатами партий.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun PartySeatCard(party: JSONObject) {
    val seats = party.int("seats")
    val percent = party.optDouble("vote_percent", 0.0)
    val partyColor = runCatching {
        Color(android.graphics.Color.parseColor(party.string("color", "#8B7CFF")))
    }.getOrDefault(Purple)
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(party.string("emoji", "🎭"), fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(party.string("name", "Партия"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$seats мест", color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = partyColor,
                trackColor = LineColor
            )
            Text("Доля голосов: " + String.format("%.1f", percent) + "%", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun StateInstitutionCard(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    Surface(
        color = CardColor, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = Pink, fontSize = 22.sp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 10.sp)
            }
            Text("›", color = Purple, fontSize = 23.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun LoadingCard(text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Purple)
            Spacer(Modifier.width(10.dp))
            Text(text, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun InfoCard(title: String, description: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
