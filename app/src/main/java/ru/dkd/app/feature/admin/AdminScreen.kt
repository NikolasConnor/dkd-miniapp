package ru.dkd.app.feature.admin

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*
import ru.dkd.app.core.network.apiCall

internal data class AdminUiState(
    val loading: Boolean = true,
    val isAdmin: Boolean = false,
    val subjects: List<JSONObject> = emptyList(),
    val roles: List<JSONObject> = emptyList(),
    val applications: List<JSONObject> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

internal class AdminViewModel : ViewModel() {
    private val mutable = MutableStateFlow(AdminUiState())
    val state = mutable.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            val status = apiCall(token, "GET", "/api/admin/status")
            if (status.error != null || status.data == null) {
                mutable.value = AdminUiState(loading = false, error = status.error ?: "Не удалось проверить права администратора.")
                return@launch
            }
            val isAdmin = status.data.bool("is_admin")
            if (!isAdmin) {
                mutable.value = AdminUiState(loading = false, isAdmin = false)
                return@launch
            }
            val subjects = apiCall(token, "GET", "/api/admin/subjects")
            val applications = apiCall(token, "GET", "/api/admin/applications")
            mutable.value = AdminUiState(
                loading = false,
                isAdmin = true,
                subjects = subjects.data?.array("subjects") ?: emptyList(),
                roles = subjects.data?.array("roles")?.filter { it.string("name") != "Главный администратор" } ?: emptyList(),
                applications = applications.data?.array("applications") ?: emptyList(),
                error = subjects.error ?: applications.error
            )
        }
    }

    fun assignRole(token: String, subjectId: Int, roleId: Int, assigned: Boolean) = mutate(
        token, "PUT", "/api/admin/subjects/$subjectId/roles",
        JSONObject().put("role_id", roleId).put("assigned", assigned),
        if (assigned) "Должность назначена." else "Должность снята."
    )

    fun setCitizenship(token: String, subjectId: Int, enabled: Boolean) = mutate(
        token, "PATCH", "/api/admin/subjects/$subjectId/citizenship",
        JSONObject().put("is_citizen", enabled),
        if (enabled) "Гражданство оформлено, паспорт создан сервером." else "Гражданство отозвано."
    )

    fun reviewApplication(token: String, id: Int, status: String, note: String) = mutate(
        token, "PATCH", "/api/admin/applications/$id",
        JSONObject().put("status", status).put("review_note", note),
        if (status == "approved") "Заявление одобрено." else "Заявление отклонено."
    )

    private fun mutate(token: String, method: String, path: String, body: JSONObject, success: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, error = null, message = null)
            val result = apiCall(token, method, path, body)
            if (result.error != null) {
                mutable.value = mutable.value.copy(busy = false, error = result.error)
            } else {
                mutable.value = mutable.value.copy(busy = false, message = success)
                refresh(token)
            }
        }
    }

    fun clearMessage() { mutable.value = mutable.value.copy(message = null, error = null) }
}

@Composable
internal fun AdminScreen(token: String, onBack: () -> Unit, onError: (String) -> Unit, onNavigate: (String) -> Unit) {
    val vm: AdminViewModel = viewModel()
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var reviewTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var reviewNote by remember { mutableStateOf("") }

    LaunchedEffect(token) { vm.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(17.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Назад", color = Purple) }
            DkdPageHeader("ЦЕНТР УПРАВЛЕНИЯ", "Администрирование", "Участники, должности, гражданство и заявления", "⚙")
        }
        if (state.loading) {
            item { AdminNotice("Проверяем доступ", "Получаем права и данные административной панели с сервера.") }
        } else if (!state.isAdmin) {
            item { AdminNotice("Доступ закрыт", "Панель доступна только главному администратору ДКД.") }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminMetric("Участники", state.subjects.size.toString(), Modifier.weight(1f))
                    AdminMetric("Заявления", state.applications.count { it.string("status") == "pending" }.toString(), Modifier.weight(1f))
                    AdminMetric("Должности", state.roles.size.toString(), Modifier.weight(1f))
                }
            }
            item {
                Text("ПОЛНЫЕ ПОЛНОМОЧИЯ АДМИНИСТРАТОРА", color = Muted, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Главный администратор имеет доступ ко всем разделам управления государством.",
                    color = White, fontSize = 11.sp)
            }
            item {
                val destinations = listOf(
                    Triple("Президент и указы", "👑", "president"),
                    Triple("Государство и правительство", "🏛️", "government"),
                    Triple("Государственная Дума", "⚖️", "duma"),
                    Triple("Выборы и результаты", "🗳️", "elections"),
                    Triple("Партии и кандидаты", "🎭", "party-candidates"),
                    Triple("Новости и публикации", "📰", "news"),
                    Triple("Заявления граждан", "📨", "applications"),
                    Triple("Уведомления", "🔔", "notifications")
                )
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    destinations.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            pair.forEach { (title, emoji, route) ->
                                Surface(
                                    color = CardColor,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                        .border(1.dp, LineColor, RoundedCornerShape(16.dp))
                                        .clickable { onNavigate(route) }
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(emoji, fontSize = 24.sp)
                                        Text(title, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Открыть раздел →", color = Purple, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            state.message?.let { item { AdminNotice("Готово", it) } }
            item {
                Text("ЗАЯВЛЕНИЯ НА РАССМОТРЕНИИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            val pending = state.applications.filter { it.string("status") == "pending" }
            if (pending.isEmpty()) item { AdminNotice("Новых заявлений нет", "Все поступившие заявления уже рассмотрены.") }
            itemsIndexed(pending, key = { index, _ -> "app-$index" }) { _, application ->
                Surface(color = CardColor, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            when (application.string("kind")) {
                                "citizenship" -> "Заявление на гражданство"
                                "party_creation" -> "Заявка на создание партии"
                                else -> "Обращение"
                            },
                            color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Text(application.string("full_name").ifBlank { application.string("username").ifBlank { "Участник ДКД" } },
                            color = Muted, fontSize = 11.sp)
                        Text(application.string("details").take(500), color = White, fontSize = 10.sp, lineHeight = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !state.busy, onClick = {
                                reviewNote = ""
                                reviewTarget = Pair(application.optInt("id"), "approved")
                            }) { Text("Одобрить", color = Good) }
                            TextButton(enabled = !state.busy, onClick = {
                                reviewNote = ""
                                reviewTarget = Pair(application.optInt("id"), "rejected")
                            }) { Text("Отклонить", color = Bad) }
                        }
                    }
                }
            }
            item {
                Text("УПРАВЛЕНИЕ УЧАСТНИКАМИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Поиск по ФИО или username") }
                )
            }
            val filtered = state.subjects.filter {
                val q = query.trim().removePrefix("@").lowercase()
                q.isBlank() || it.string("full_name").lowercase().contains(q) ||
                    it.string("username").lowercase().contains(q)
            }
            itemsIndexed(filtered, key = { index, _ -> "user-$index" }) { _, subject ->
                val active = selected?.optInt("id") == subject.optInt("id")
                Surface(color = if (active) Panel2 else CardColor, shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (active) Purple else LineColor, RoundedCornerShape(15.dp))
                        .clickable { selected = if (active) null else subject }) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(subject.string("full_name").ifBlank { "Без имени" }, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(subject.string("username").let { if (it.isBlank()) "ID: ${subject.optLong("user_id")}" else "@$it · ID: ${subject.optLong("user_id")}" },
                                    color = Muted, fontSize = 9.sp)
                            }
                            Text(if (subject.bool("is_citizen")) "ГРАЖДАНИН" else "НЕ ГРАЖДАНИН",
                                color = if (subject.bool("is_citizen")) Good else Pink, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            subject.array("roles").joinToString(" · ") { it.string("name") }.ifBlank { "Без должностей" },
                            color = Muted, fontSize = 10.sp
                        )
                        if (active) {
                            HorizontalDivider(color = LineColor)
                            Text("ПРОФИЛЬ УЧАСТНИКА", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text("Репутация: ${subject.optInt("reputation")}  ·  Партия: ${subject.string("party_name").ifBlank { "—" }}",
                                color = Muted, fontSize = 10.sp)
                            Text("Паспорт: ${subject.string("passport_number").ifBlank { "не выдан" }}",
                                color = Muted, fontSize = 10.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Гражданство", color = White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = subject.bool("is_citizen"),
                                    enabled = !state.busy,
                                    onCheckedChange = { vm.setCitizenship(token, subject.optInt("id"), it) }
                                )
                            }
                            Text("ДОЛЖНОСТИ", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            state.roles.forEach { role ->
                                val hasRole = subject.array("roles").any { it.optInt("id") == role.optInt("id") }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${role.string("emoji")} ${role.string("name")}", color = White, fontSize = 10.sp,
                                        modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = hasRole,
                                        enabled = !state.busy,
                                        onCheckedChange = { vm.assignRole(token, subject.optInt("id"), role.optInt("id"), it) }
                                    )
                                }
                            }
                        } else {
                            Text("Нажмите, чтобы открыть профиль и изменить права", color = Purple, fontSize = 9.sp)
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { vm.refresh(token) }, modifier = Modifier.fillMaxWidth()) { Text("↻ Обновить данные", color = Purple) }
            }
        }
    }

    reviewTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) reviewTarget = null },
            containerColor = Panel,
            title = { Text(if (target.second == "approved") "Одобрить заявление?" else "Отклонить заявление?", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Решение будет сохранено в системе ДКД.", color = Muted, fontSize = 11.sp)
                    OutlinedTextField(value = reviewNote, onValueChange = { reviewNote = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Комментарий заявителю") })
                }
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    vm.reviewApplication(token, target.first, target.second, reviewNote)
                    reviewTarget = null
                }) { Text("Подтвердить", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { reviewTarget = null }) { Text("Отмена", color = Muted) } }
        )
    }
}

@Composable
private fun AdminMetric(title: String, value: String, modifier: Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun AdminNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
