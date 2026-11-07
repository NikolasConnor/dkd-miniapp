package ru.dkd.app.feature.parties

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun PartiesScreen(token: String, onError: (String) -> Unit) {
    val viewModel: PartiesViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var confirmLeave by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var editParty by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("ПОЛИТИЧЕСКАЯ СИСТЕМА", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Партии ДКД", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Действующие политические объединения и их состав", color = Muted, fontSize = 12.sp)
            if (state.isCitizen && state.myPartyId == null) {
                Spacer(Modifier.height(5.dp))
                Button(
                    onClick = { createOpen = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) { Text("＋  Создать политическую партию", color = White, fontSize = 12.sp) }
            }
        }

        if (state.loading && state.parties.isEmpty()) {
            item {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем партии…", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        } else if (state.error != null && state.parties.isEmpty()) {
            item { PartyNotice("Список партий недоступен", "Не удалось получить сведения с сервера ДКД.") }
        } else if (state.parties.isEmpty()) {
            item { PartyNotice("Действующих партий пока нет", "После создания политических объединений они появятся в этом разделе.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartyStat("ПАРТИЙ", state.parties.size.toString(), Modifier.weight(1f))
                    PartyStat("ВАШ СТАТУС", if (state.myPartyId != null) "Участник" else if (state.isCitizen) "Гражданин" else "Не гражданин", Modifier.weight(1f))
                }
            }
            items(state.parties, key = { it.optInt("id") }) { party ->
                PartyCard(
                    party = party,
                    myPartyId = state.myPartyId,
                    isCitizen = state.isCitizen,
                    busy = state.busy,
                    onJoin = { viewModel.join(token, party.optInt("id")) },
                    onLeave = { confirmLeave = true },
                    onEdit = { editParty = party }
                )
            }
        }

        state.message?.let { message -> item { PartyNotice("Готово", message) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple) }

        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить список", color = Purple)
            }
        }
    }

    editParty?.let { party ->
        var name by remember(party.optInt("id")) { mutableStateOf(party.string("name")) }
        var emoji by remember(party.optInt("id")) { mutableStateOf(party.string("emoji", "🎭")) }
        var description by remember(party.optInt("id")) { mutableStateOf(party.string("description")) }
        var color by remember(party.optInt("id")) { mutableStateOf(party.string("color", "#8B7CFF").uppercase()) }
        val colors = listOf("#8B7CFF", "#5E8BFF", "#F0B7D0", "#63D9A0", "#FF8298", "#E6B85C", "#39B8C7", "#FFFFFF")
        AlertDialog(
            onDismissRequest = { if (!state.busy) editParty = null },
            containerColor = Panel,
            title = { Text("Настройки партии", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название") })
                    OutlinedTextField(emoji, { emoji = it.take(8) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Эмодзи") })
                    OutlinedTextField(description, { description = it.take(1000) }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Описание") })
                    Text("ЦВЕТ ПАРТИИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    colors.chunked(4).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.forEach { option ->
                                val swatch = Color(android.graphics.Color.parseColor(option))
                                Surface(
                                    color = swatch,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(34.dp).border(
                                        if (color == option) 3.dp else 1.dp,
                                        if (color == option) White else LineColor,
                                        RoundedCornerShape(10.dp)
                                    ).clickable { color = option }
                                ) {}
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.trim().length >= 2 && !state.busy,
                    onClick = {
                        viewModel.updateSettings(token, party.optInt("id"), name, emoji, description, color)
                        editParty = null
                    }
                ) { Text("Сохранить", color = Purple) }
            },
            dismissButton = {
                TextButton(onClick = { editParty = null }, enabled = !state.busy) { Text("Отмена", color = Muted) }
            }
        )
    }

    if (createOpen) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("🎭") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.busy) createOpen = false },
            containerColor = Panel,
            title = { Text("Создать партию", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Новая партия будет создана от имени вашего гражданского аккаунта.", color = Muted, fontSize = 11.sp)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(60) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Название партии") }
                    )
                    OutlinedTextField(
                        value = emoji, onValueChange = { emoji = it.take(8) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Эмодзи партии") }
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it.take(1000) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                        label = { Text("Описание (необязательно)") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.trim().length >= 2 && emoji.isNotBlank() && !state.busy,
                    onClick = {
                        viewModel.create(token, name, emoji, description)
                        createOpen = false
                    }
                ) { Text("Создать", color = Purple) }
            },
            dismissButton = {
                TextButton(onClick = { createOpen = false }, enabled = !state.busy) { Text("Отмена", color = Muted) }
            }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Panel,
            title = { Text("Покинуть партию?", color = White) },
            text = { Text("Если вы председатель, сервер передаст руководство другому участнику или распустит партию, если вы последний участник.", color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    confirmLeave = false
                    viewModel.leave(token)
                }) { Text("Покинуть", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Отмена", color = Muted) } }
        )
    }
}

@Composable
private fun PartyCard(
    party: JSONObject,
    myPartyId: Int?,
    isCitizen: Boolean,
    busy: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onEdit: () -> Unit
) {
    val id = party.optInt("id")
    val members = party.array("members")
    val isMine = party.bool("is_member")
    val canManage = party.bool("can_manage")
    val color = runCatching { Color(android.graphics.Color.parseColor(party.string("color", "#8B7CFF"))) }.getOrDefault(Purple)
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = .55f), RoundedCornerShape(20.dp))
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(45.dp).background(color.copy(alpha = .18f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(party.string("emoji", "🎭"), fontSize = 23.sp) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(party.string("name", "Политическая партия"), color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(if (isMine) "Ваша партия" else "Политическое объединение", color = if (isMine) Good else Muted, fontSize = 10.sp)
                }
                Text("${party.optInt("members_count", members.size)} чел.", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            val description = party.string("description")
            if (description.isNotBlank()) Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            HorizontalDivider(color = LineColor)
            Text("СОСТАВ", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (members.isEmpty()) {
                Text("Сведения об участниках отсутствуют.", color = Muted, fontSize = 10.sp)
            } else {
                members.take(8).forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("•", color = color, fontSize = 17.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(member.string("name", "Участник"), color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (member.bool("is_leader")) {
                            Text("ПРЕДСЕДАТЕЛЬ", color = Pink, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                        }
                    }
                }
                if (members.size > 8) Text("И ещё ${members.size - 8} участников", color = Muted, fontSize = 9.sp)
            }
            if (canManage) {
                Text("Управление политическим объединением", color = Blue, fontSize = 9.sp)
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp)) {
                    Text("Настроить партию", color = Purple, fontSize = 11.sp)
                }
            }
            if (isMine) {
                OutlinedButton(onClick = onLeave, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp)) {
                    Text("Покинуть партию", color = Bad, fontSize = 11.sp)
                }
            } else if (isCitizen && myPartyId == null) {
                Button(onClick = onJoin, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                    Text("Вступить в партию", color = White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PartyStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, letterSpacing = .7.sp)
            Text(value, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PartyNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
