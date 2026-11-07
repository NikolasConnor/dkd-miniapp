package ru.dkd.app.feature.duma

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

private data class DumaState(
    val loading: Boolean = true,
    val authorized: Boolean = false,
    val sessions: List<JSONObject> = emptyList(),
    val sessionsLoaded: Boolean = false,
    val laws: List<JSONObject> = emptyList(),
    val lawsLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

private class DumaViewModel : ViewModel() {
    private val mutable = MutableStateFlow(DumaState())
    val state = mutable.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch { load(token, null) }
    }

    private suspend fun load(token: String, message: String?) {
        mutable.value = mutable.value.copy(loading = true, error = null, message = null)
        val result = apiCall(token, "GET", "/api/duma")
        if (result.error != null || result.data == null) {
            mutable.value = DumaState(loading = false, error = result.error ?: "Раздел Думы недоступен.")
            return
        }
        val sessionsLoaded = result.data.has("sessions") && result.data.opt("sessions") is org.json.JSONArray
        val lawsLoaded = result.data.has("laws") && result.data.opt("laws") is org.json.JSONArray
        mutable.value = DumaState(
            loading = false, authorized = true,
            sessions = if (sessionsLoaded) result.data.array("sessions") else emptyList(),
            sessionsLoaded = sessionsLoaded,
            laws = if (lawsLoaded) result.data.array("laws") else emptyList(),
            lawsLoaded = lawsLoaded,
            error = if (!sessionsLoaded || !lawsLoaded) "Сервер вернул неполный ответ. Обновите раздел; парламентские действия временно недоступны." else null,
            message = message
        )
    }

    private fun act(token: String, method: String, path: String, payload: JSONObject?, success: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, error = null, message = null)
            val result = apiCall(token, method, path, payload)
            if (result.error != null) mutable.value = mutable.value.copy(busy = false, error = result.error)
            else {
                mutable.value = mutable.value.copy(busy = false)
                load(token, success)
            }
        }
    }

    fun openSession(token: String, title: String) =
        act(token, "POST", "/api/duma/sessions", JSONObject().put("title", title.trim()), "Заседание открыто.")

    fun closeSession(token: String, id: Int) =
        act(token, "POST", "/api/duma/sessions/$id/close", JSONObject(), "Заседание закрыто.")

    fun submitLaw(token: String, sessionId: Int, title: String, description: String) =
        act(token, "POST", "/api/duma/sessions/$sessionId/laws",
            JSONObject().put("title", title.trim()).put("description", description.trim()),
            "Законопроект внесён на рассмотрение.")

    fun vote(token: String, lawId: Int, vote: String) =
        act(token, "POST", "/api/duma/laws/$lawId/vote", JSONObject().put("vote", vote),
            "Ваш голос принят сервером.")
}

@Composable
internal fun DumaScreen(token: String, onBack: () -> Unit, onError: (String) -> Unit) {
    val vm: DumaViewModel = viewModel()
    val state by vm.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var openDialog by remember { mutableStateOf(false) }
    var submitFor by remember { mutableStateOf<Int?>(null) }
    var confirmClose by remember { mutableStateOf<Int?>(null) }
    var confirmVote by remember { mutableStateOf<Pair<Int, String>?>(null) }

    LaunchedEffect(token, refresh) { vm.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Purple) }
            Text("ПАРЛАМЕНТ ДКД", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Государственная Дума", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Заседания, законопроекты и парламентские голосования", color = Muted, fontSize = 11.sp)
        }
        if (state.loading) {
            item { DumaNotice("Проверяем полномочия депутата…", "Доступ подтверждается сервером ДКД.") }
        } else if (!state.authorized) {
            item { DumaNotice("Доступ ограничен", "Парламентская работа доступна только действующим депутатам и администратору. Одного отображения кнопок недостаточно для получения полномочий.") }
        } else {
            if (!state.sessionsLoaded || !state.lawsLoaded) {
                item { DumaNotice("Данные парламента загружены не полностью", "Не удалось подтвердить наличие заседаний и законопроектов. Создание заседаний и голосование временно скрыты до успешной загрузки.") }
            } else item {
                Button(onClick = { openDialog = true }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                    Text("Открыть заседание", color = White)
                }
            }
            item { Text("ЗАСЕДАНИЯ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.sessionsLoaded) item { DumaNotice("Список заседаний недоступен", "Не удалось загрузить заседания с сервера.") }
            else if (state.sessions.isEmpty()) item { DumaNotice("Заседаний пока нет", "Депутат может открыть заседание, после чего внести законопроект на рассмотрение.") }
            items(state.sessions, key = { "session-${it.optInt("id")}" }) { session ->
                val sessionId = session.optInt("id")
                val closed = session.bool("closed")
                val sessionLaws = session.array("laws")
                Surface(color = CardColor, shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(17.dp))) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(session.string("title", "Заседание Государственной Думы"), color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(if (closed) "ЗАСЕДАНИЕ ЗАКРЫТО" else "ЗАСЕДАНИЕ ОТКРЫТО", color = if (closed) Muted else Good, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(session.string("created_at").take(16).replace("T", " "), color = Muted, fontSize = 9.sp)
                        val closesAt = session.string("closes_at")
                        if (closesAt.isNotBlank()) {
                            Text(
                                if (closed) "Заседание завершено · срок до ${closesAt.take(16).replace("T", " ")}"
                                else "Автоматическое закрытие: ${closesAt.take(16).replace("T", " ")}",
                                color = Muted, fontSize = 9.sp
                            )
                        }
                        if (!closed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                OutlinedButton(onClick = { submitFor = sessionId }, enabled = !state.busy,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                    Text("Внести законопроект", color = Purple, fontSize = 9.sp)
                                }
                                TextButton(onClick = { confirmClose = sessionId }, enabled = !state.busy) {
                                    Text("Закрыть", color = Bad, fontSize = 10.sp)
                                }
                            }
                        }
                        sessionLaws.forEach { law -> DumaLawCard(law, state.busy) { vote -> confirmVote = law.optInt("id") to vote } }
                    }
                }
            }
            item { Text("ВСЕ ЗАКОНОПРОЕКТЫ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.lawsLoaded) item { DumaNotice("Список законопроектов недоступен", "Не удалось загрузить законопроекты с сервера.") }
            else if (state.laws.isEmpty()) item { DumaNotice("Законопроектов нет", "Новые законопроекты появятся после внесения на открытом заседании.") }
            items(state.laws, key = { "law-${it.optInt("id")}" }) { law ->
                DumaLawCard(law, state.busy) { vote -> confirmVote = law.optInt("id") to vote }
            }
            item { TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("↻  Обновить парламент", color = Purple) } }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple) }
        state.message?.let { item { DumaNotice("Операция выполнена", it) } }
        state.error?.let { item { DumaNotice("Ошибка", it) } }
    }

    if (openDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { openDialog = false }, containerColor = Panel,
            title = { Text("Открыть заседание", color = White) },
            text = { OutlinedTextField(title, { title = it }, label = { Text("Название заседания") }, singleLine = true) },
            confirmButton = { TextButton(enabled = title.trim().length >= 2 && !state.busy, onClick = { vm.openSession(token, title); openDialog = false }) { Text("Открыть", color = Purple) } },
            dismissButton = { TextButton(onClick = { openDialog = false }) { Text("Отмена", color = Muted) } }
        )
    }
    submitFor?.let { sessionId ->
        var title by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { submitFor = null }, containerColor = Panel,
            title = { Text("Новый законопроект", color = White) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Полный текст законопроекта") }, minLines = 4)
            } },
            confirmButton = { TextButton(enabled = title.trim().length >= 2 && description.isNotBlank() && !state.busy, onClick = { vm.submitLaw(token, sessionId, title, description); submitFor = null }) { Text("Внести", color = Purple) } },
            dismissButton = { TextButton(onClick = { submitFor = null }) { Text("Отмена", color = Muted) } }
        )
    }
    confirmClose?.let { id ->
        AlertDialog(onDismissRequest = { confirmClose = null }, containerColor = Panel,
            title = { Text("Закрыть заседание?", color = White) },
            text = { Text("После закрытия заседания внесение законопроектов и голосование по нему станут недоступны.", color = Muted, fontSize = 12.sp) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { vm.closeSession(token, id); confirmClose = null }) { Text("Закрыть заседание", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Отмена", color = Muted) } }
        )
    }
    confirmVote?.let { pair ->
        AlertDialog(onDismissRequest = { confirmVote = null }, containerColor = Panel,
            title = { Text(if (pair.second == "for") "Голосовать «За»?" else "Голосовать «Против»?", color = White) },
            text = { Text("Голос будет сохранён в истории заседания. Повторное голосование по одному законопроекту запрещено сервером.", color = Muted, fontSize = 12.sp) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { vm.vote(token, pair.first, pair.second); confirmVote = null }) { Text("Подтвердить", color = Purple) } },
            dismissButton = { TextButton(onClick = { confirmVote = null }) { Text("Отмена", color = Muted) } }
        )
    }
}

@Composable
private fun DumaLawCard(law: JSONObject, busy: Boolean, onVote: (String) -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(law.string("title", "Законопроект"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(law.string("description").take(350), color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
            Text("За: ${law.optInt("votes_for")}  ·  Против: ${law.optInt("votes_against")}", color = Muted, fontSize = 9.sp)
            Text("Статус: ${law.string("status")}", color = Pink, fontSize = 9.sp)
            if (law.bool("can_vote")) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = { onVote("for") }, enabled = !busy, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                        Text("За", color = White, fontSize = 10.sp)
                    }
                    OutlinedButton(onClick = { onVote("against") }, enabled = !busy, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp)) { Text("Против", color = Pink, fontSize = 10.sp) }
                }
            } else if (law.bool("has_voted")) {
                Text("Вы уже проголосовали", color = Good, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DumaNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
