package ru.dkd.app.feature.government

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun StateScreen(token: String, onError: (String) -> Unit) {
    val viewModel: StateViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    val parliament = state.parliament
    val members = parliament?.array("members") ?: emptyList()
    val parties = state.parties
    val chamberParties = parliament?.array("parties") ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            DkdPageHeader("ИНСТИТУТЫ ВЛАСТИ", "Государство", "Органы власти и государственное устройство ДКД", "🏛")
        }

        if (state.fromCache) {
            item {
                Surface(
                    color = Color(0xFF3A304A),
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ОФЛАЙН-КОПИЯ", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Нет связи с сервером. Показаны последние сохранённые сведения; они могут быть устаревшими.",
                            color = White, fontSize = 10.sp, lineHeight = 15.sp)
                        state.cachedAtEpochMs?.let { timestamp ->
                            Text("Сохранено: " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp)),
                                color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        if (state.loading && parliament == null && parties.isEmpty()) {
            item {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем сведения о государстве…", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        } else if (state.error != null && parliament == null && parties.isEmpty()) {
            item { StateNotice("Сведения недоступны", "Сервер не вернул данные о государственных институтах. Попробуйте обновить раздел.") }
        } else {
            item {
                Surface(
                    color = Panel2,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(22.dp))
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("ДКД  /  STATE", color = Pink, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                        Text("Государственное устройство", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("Президентская власть · Государственная Дума · Политические партии",
                            color = Color(0xFFD0D3F3), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }

            if (parliament != null) {
                item { StateSectionTitle("Государственная Дума") }
                item {
                    val formed = parliament.optBoolean("formed", false)
                    val total = parliament.optInt("seats_total", 0)
                    val occupied = parliament.optInt("seats_occupied", members.size)
                    Surface(color = CardColor, shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(18.dp))) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (formed) "Действующий состав" else "Состав не сформирован", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StateMetric("МЕСТ", total.toString(), Modifier.weight(1f))
                                StateMetric("ЗАНЯТО", occupied.toString(), Modifier.weight(1f))
                                StateMetric("ДЕПУТАТОВ", members.size.toString(), Modifier.weight(1f))
                            }
                            if (chamberParties.any { it.optInt("seats") > 0 }) {
                                Text("РАСПРЕДЕЛЕНИЕ МЕСТ", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                PartyComposition(chamberParties)
                            }
                            val termStart = parliament.string("term_start")
                            val termEnd = parliament.string("term_end")
                            if (termStart.isNotBlank() || termEnd.isNotBlank()) {
                                Text("Срок полномочий: ${termStart.take(10).ifBlank { "—" }} — ${termEnd.take(10).ifBlank { "—" }}",
                                    color = Muted, fontSize = 10.sp)
                            }
                            if (!formed) Text("После завершения новых выборов здесь появится состав парламента.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }

                if (members.isNotEmpty()) {
                    item { StateSectionTitle("Депутаты") }
                    itemsIndexed(members, key = { index, _ -> "deputy-$index" }) { _, member ->
                        Row(
                            Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(14.dp))
                                .border(1.dp, LineColor, RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(35.dp).background(Color(0x338B7CFF), RoundedCornerShape(11.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(member.string("party_name").take(1).ifBlank { "Д" }, color = Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(member.string("full_name", "Депутат"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(member.string("party_name", "Без партии"), color = Muted, fontSize = 9.sp)
                            }
                            Text("МАНДАТ", color = Good, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item { StateSectionTitle("Политические партии") }
            if (parties.isEmpty()) {
                item { StateNotice("Партии не найдены", "В настоящий момент нет доступных действующих партий.") }
            } else {
                itemsIndexed(parties, key = { index, _ -> "party-$index" }) { _, party ->
                    Surface(color = CardColor, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(16.dp))) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(party.string("emoji", "🎭"), fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(party.string("name", "Партия"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(party.string("description", "Политическое объединение ДКД"), color = Muted, fontSize = 9.sp, maxLines = 2)
                            }
                            Text("${party.optInt("members_count", 0)} чел.", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить сведения", color = Purple)
            }
        }
    }
}

@Composable
private fun StateSectionTitle(title: String) {
    Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun StateMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = Muted, fontSize = 7.sp, letterSpacing = .5.sp)
            Text(value, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StateNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PartyComposition(parties: List<JSONObject>) {
    val represented = parties.filter { it.optInt("seats") > 0 }
    val totalSeats = represented.sumOf { it.optInt("seats").coerceAtLeast(0) }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(17.dp).clip(RoundedCornerShape(9.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        represented.forEach { party ->
            val seats = party.optInt("seats").coerceAtLeast(1)
            val color = runCatching {
                Color(android.graphics.Color.parseColor(party.string("color", "#8B7CFF")))
            }.getOrDefault(Purple)
            Box(
                Modifier.weight(seats.toFloat()).fillMaxHeight().background(color)
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        represented.forEach { party ->
            val color = runCatching {
                Color(android.graphics.Color.parseColor(party.string("color", "#8B7CFF")))
            }.getOrDefault(Purple)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    party.string("name", "Партия"),
                    color = White, fontSize = 10.sp, modifier = Modifier.weight(1f)
                )
                Text(
                    "${party.optInt("seats")} мест · ${(party.optInt("seats") * 100f / totalSeats).toInt()}%",
                    color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

