package ru.dkd.app.feature.elections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun ElectionsScreen(token: String, onError: (String) -> Unit) {
    val viewModel: ElectionsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var pendingVote by remember { mutableStateOf<PendingElectionVote?>(null) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("ИЗБИРАТЕЛЬНАЯ СИСТЕМА", color = Pink, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Text("Выборы", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("Официальные кампании и результаты голосований ДКД", color = Muted, fontSize = 12.sp)
        }

        if (state.loading && state.presidential.isEmpty() && state.duma.isEmpty()) {
            item {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем избирательные кампании…", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        } else if (state.error != null && state.presidential.isEmpty() && state.duma.isEmpty()) {
            item { ElectionEmptyState("Не удалось загрузить выборы", "Проверьте подключение к серверу и обновите раздел.") }
        } else if (state.presidential.isEmpty() && state.duma.isEmpty()) {
            item { ElectionEmptyState("Активных кампаний нет", "Когда будут назначены выборы, здесь появятся официальные бюллетени и результаты.") }
        }

        if (state.presidential.isNotEmpty()) {
            item { ElectionSectionTitle("Президентские выборы", state.presidential.size) }
            items(state.presidential.size) { index -> ElectionCard(state.presidential[index], "presidential", state.busy) { id, option, label ->
                pendingVote = PendingElectionVote("presidential", id, option, label)
            } }
        }

        if (state.duma.isNotEmpty()) {
            item { ElectionSectionTitle("Выборы в Государственную Думу", state.duma.size) }
            items(state.duma.size) { index -> ElectionCard(state.duma[index], "duma", state.busy) { id, option, label ->
                pendingVote = PendingElectionVote("duma", id, option, label)
            } }
        }

        state.message?.let { message -> item { ElectionEmptyState("Голос принят", message) } }
        state.error?.let { error -> item { ElectionEmptyState("Не удалось проголосовать", error) } }
        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить выборы", color = Purple)
            }
        }
    }

    pendingVote?.let { vote ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) pendingVote = null },
            containerColor = Panel,
            title = { Text("Подтвердить голос?", color = White) },
            text = { Text(
                if (vote.kind == "duma") "Вы выбираете партию «${vote.label}». После отправки изменить голос будет нельзя."
                else "Вы выбираете кандидата «${vote.label}». После отправки изменить голос будет нельзя.",
                color = Muted, fontSize = 12.sp
            ) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    viewModel.castVote(token, vote.kind, vote.electionId, vote.option)
                    pendingVote = null
                }) { Text("Подтвердить", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { pendingVote = null }, enabled = !state.busy) { Text("Отмена", color = Muted) } }
        )
    }
}

@Composable
private data class PendingElectionVote(
    val kind: String,
    val electionId: Int,
    val option: String,
    val label: String
)

private fun ElectionCard(election: JSONObject, kind: String, busy: Boolean, onVote: (Int, String, String) -> Unit) {
    val status = election.string("status")
    val isActive = status == "active"
    val isClosed = status == "closed"
    val hasVoted = election.bool("has_voted")
    val canVote = election.bool("can_vote", true)
    val options = election.array("candidate_details")
    val parties = election.array("party_lists")
    val results = election.optJSONObject("vote_counts")

    Surface(
        color = CardColor,
        shape = RoundedCornerShape(21.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(21.dp))
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (kind == "presidential") "ПРЕЗИДЕНТСКАЯ КАМПАНИЯ" else "ПАРЛАМЕНТСКАЯ КАМПАНИЯ",
                    color = Pink, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = when {
                        isActive -> Color(0xFF153E38)
                        isClosed -> Color(0xFF343B69)
                        else -> Color(0xFF3A3157)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        when (status) {
                            "active" -> "Идёт"
                            "closed" -> "Завершены"
                            "scheduled" -> "Ожидаются"
                            else -> "Статус: $status"
                        },
                        color = when {
                            isActive -> Good
                            isClosed -> Muted
                            else -> Pink
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
            Text(election.string("title", "Избирательная кампания"), color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val start = election.string("starts_at")
            val end = election.string("ends_at")
            if (start.isNotBlank() || end.isNotBlank()) {
                val dateLabels = listOfNotNull(
                    start.takeIf { it.isNotBlank() }?.let { "Начало: " + it.take(16).replace("T", " ") },
                    end.takeIf { it.isNotBlank() }?.let { "Окончание: " + it.take(16).replace("T", " ") }
                )
                Text(dateLabels.joinToString("  ·  "), color = Muted, fontSize = 10.sp)
            }

            if (kind == "presidential" && options.isNotEmpty()) {
                HorizontalDivider(color = LineColor)
                Text("КАНДИДАТЫ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                options.forEach { candidate ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(37.dp).background(Color(0x338B7CFF), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text(candidate.string("avatar_emoji", "👤"), fontSize = 20.sp) }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            candidate.string("full_name", candidate.string("name")),
                            color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (isActive && !hasVoted && kind == "presidential") {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    val option = candidate.string("name", candidate.string("full_name"))
                                    if (option.isNotBlank()) {
                                        onVote(
                                            election.optInt("id"),
                                            option,
                                            candidate.string("full_name", option)
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("Выбрать", color = Purple, fontSize = 10.sp) }
                        }
                    }
                }
            }

            if (kind == "duma" && parties.isNotEmpty()) {
                HorizontalDivider(color = LineColor)
                Text("ПАРТИЙНЫЕ СПИСКИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                parties.forEach { party ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(party.string("party_emoji", "🎭"), fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(party.string("party_name", "Партия"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (isActive && !hasVoted && canVote) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        val option = party.string("party_name")
                                        if (option.isNotBlank()) onVote(election.optInt("id"), option, option)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) { Text("Голосовать", color = Purple, fontSize = 10.sp) }
                            }
                        }
                        val candidates = candidateNames(party.optJSONArray("candidates"))
                        if (candidates.isNotEmpty()) {
                            Text(candidates.joinToString(" · "), color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }

            if (isClosed && results != null) {
                HorizontalDivider(color = LineColor)
                Text("ИТОГИ ГОЛОСОВАНИЯ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                val resultKeys = results.keys()
                while (resultKeys.hasNext()) {
                    val key = resultKeys.next()
                    val count = results.optInt(key, 0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(key, color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(count.toString(), color = Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Всего голосов: " + election.optInt("votes_total", 0), color = Muted, fontSize = 10.sp)
            } else if (hasVoted) {
                Surface(color = Color(0xFF153E38), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "Ваш голос учтён: " + election.string("my_option"),
                        color = Good, fontSize = 11.sp, modifier = Modifier.padding(11.dp)
                    )
                }
            } else if (isActive) {
                Surface(
                    color = Color(0xFF302A4E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (kind == "duma" && !canVote) election.string("vote_block_reason", "Вы не можете голосовать на этих выборах.")
                        else if (kind == "duma") "Выберите партийный список и подтвердите голос. Сервер проверит право голоса и не примет повторный бюллетень."
                        else "Выберите кандидата выше и подтвердите отправку голоса.",
                        color = Color(0xFFD7D2FF), fontSize = 10.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(11.dp)
                    )
                }
            } else if (!isClosed) {
                Text("Голосование ещё не началось.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun candidateNames(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.opt(index)
        val name = when (item) {
            is JSONObject -> item.optString("full_name").ifBlank { item.optString("name") }
            else -> item?.toString().orEmpty()
        }
        name.takeIf { it.isNotBlank() && it != "null" }
    }
}

@Composable
private fun ElectionSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count.toString(), color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ElectionEmptyState(title: String, description: String) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
