package ru.dkd.app.feature.parties

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun PartyCandidatesScreen(token: String, onBack: () -> Unit, onError: (String) -> Unit) {
    val vm: PartyCandidatesViewModel = viewModel()
    val state by vm.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { vm.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Назад", color = Purple) }
            Text("ИЗБИРАТЕЛЬНАЯ СИСТЕМА", color = Pink, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Text("Список кандидатов", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Сформируйте партийный список из действующих участников вашей партии.", color = Muted, fontSize = 11.sp)
        }

        if (state.loading && state.party == null) {
            item { CandidateNotice("Загружаем состав партии…", "Проверяем полномочия председателя и список участников.") }
        } else if (state.error != null && state.party == null) {
            item { CandidateNotice("Не удалось загрузить список", state.error ?: "Ошибка сервера.") }
        } else if (state.party == null) {
            item { CandidateNotice("Недостаточно полномочий", "Формировать партийный список может только председатель действующей партии.") }
        } else {
            item {
                Surface(color = Panel2, shape = RoundedCornerShape(19.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(19.dp))) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ПАРТИЯ", color = Pink, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("${state.party.string("emoji", "🎭")}  ${state.party.string("name", "Партия")}", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Выбрано кандидатов: ${state.selectedIds.size} из ${state.members.size}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text("УЧАСТНИКИ ПАРТИИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            if (state.members.isEmpty()) {
                item { CandidateNotice("В партии нет участников", "Добавьте участников в партию, чтобы сформировать список.") }
            }
            items(state.members, key = { it.optLong("user_id") }) { member ->
                val id = member.optLong("user_id")
                val candidateIndex = state.selectedIds.indexOf(id)
                val checked = candidateIndex >= 0
                Surface(color = CardColor, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (checked) Purple else LineColor, RoundedCornerShape(14.dp))) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { vm.toggle(id, it) },
                            enabled = !state.busy,
                            colors = CheckboxDefaults.colors(checkedColor = Purple)
                        )
                        Text(member.string("full_name", "Участник"), color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (checked) {
                            Text("${candidateIndex + 1}", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = { vm.move(id, -1) },
                                enabled = !state.busy && candidateIndex > 0,
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                            ) { Text("↑", color = Purple) }
                            TextButton(
                                onClick = { vm.move(id, 1) },
                                enabled = !state.busy && candidateIndex < state.selectedIds.lastIndex,
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                            ) { Text("↓", color = Purple) }
                        }
                    }
                }
            }
            item {
                Text("Сохранённый шаблон синхронизируется с незавершёнными думскими выборами. Завершённые кампании не изменяются.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                Button(
                    onClick = { vm.save(token) },
                    enabled = !state.busy && state.selectedIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(17.dp), color = White, strokeWidth = 2.dp)
                    else Text("Сохранить партийный список", color = White)
                }
            }
        }
        state.message?.let { item { CandidateNotice("Сохранено", it) } }
        item { TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("↻ Обновить состав", color = Purple) } }
    }
}

@Composable
private fun CandidateNotice(title: String, message: String) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
