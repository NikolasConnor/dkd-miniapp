package ru.dkd.app.feature.president

import androidx.compose.foundation.background
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

private data class PresidentUiState(
    val loading: Boolean = true,
    val authorized: Boolean = false,
    val isAdmin: Boolean = false,
    val term: JSONObject? = null,
    val laws: List<JSONObject> = emptyList(),
    val lawsLoaded: Boolean = false,
    val applications: List<JSONObject> = emptyList(),
    val applicationsLoaded: Boolean = false,
    val subjects: List<JSONObject> = emptyList(),
    val subjectsLoaded: Boolean = false,
    val dumaElections: List<JSONObject> = emptyList(),
    val dumaElectionsLoaded: Boolean = false,
    val decrees: List<JSONObject> = emptyList(),
    val decreesLoaded: Boolean = false,
    val assignableRoles: List<JSONObject> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

private class PresidentViewModel : ViewModel() {
    private val mutable = MutableStateFlow(PresidentUiState())
    val state = mutable.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch { load(token, null) }
    }

    private suspend fun load(token: String, successMessage: String?) {
        mutable.value = mutable.value.copy(loading = true, error = null, message = null)
        val status = apiCall(token, "GET", "/api/president/status")
        val statusJson = status.data
        if (status.error != null || statusJson == null) {
            mutable.value = PresidentUiState(loading = false, error = status.error ?: "Не удалось получить статус доступа.")
            return
        }
        val allowed = statusJson.bool("is_president")
        if (!allowed) {
            mutable.value = PresidentUiState(
                loading = false, authorized = false,
                isAdmin = statusJson.bool("is_admin"),
                term = statusJson.optJSONObject("term")
            )
            return
        }
        val lawsResult = apiCall(token, "GET", "/api/president/laws")
        val applicationsResult = apiCall(token, "GET", "/api/president/applications")
        val subjectsResult = apiCall(token, "GET", "/api/president/subjects")
        val electionsResult = apiCall(token, "GET", "/api/votes")
        val decreesResult = apiCall(token, "GET", "/api/president/decrees")
        val allowedRoleNames = setOf("Премьер-министр", "Министр иностранных дел")
        mutable.value = PresidentUiState(
            loading = false, authorized = true,
            isAdmin = statusJson.bool("is_admin"),
            term = statusJson.optJSONObject("term"),
            laws = lawsResult.data?.array("laws") ?: emptyList(),
            lawsLoaded = lawsResult.error == null && lawsResult.data?.has("laws") == true,
            applications = applicationsResult.data?.array("applications") ?: emptyList(),
            applicationsLoaded = applicationsResult.error == null && applicationsResult.data?.has("applications") == true,
            subjects = subjectsResult.data?.array("subjects") ?: emptyList(),
            subjectsLoaded = subjectsResult.error == null && subjectsResult.data?.has("subjects") == true,
            dumaElections = electionsResult.data?.array("duma") ?: emptyList(),
            dumaElectionsLoaded = electionsResult.error == null && electionsResult.data?.has("duma") == true,
            decrees = decreesResult.data?.array("decrees") ?: emptyList(),
            decreesLoaded = decreesResult.error == null && decreesResult.data?.has("decrees") == true,
            assignableRoles = (subjectsResult.data?.array("roles") ?: emptyList())
                .filter { it.string("name") in allowedRoleNames },
            error = lawsResult.error ?: applicationsResult.error ?: subjectsResult.error ?: electionsResult.error ?: decreesResult.error,
            message = successMessage
        )
    }

    private fun submit(token: String, method: String, path: String, payload: JSONObject? = null, success: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, error = null, message = null)
            val result = apiCall(token, method, path, payload)
            if (result.error != null) {
                mutable.value = mutable.value.copy(busy = false, error = result.error)
            } else {
                mutable.value = mutable.value.copy(busy = false)
                load(token, success)
            }
        }
    }

    fun assignRole(token: String, subjectId: Int, roleId: Int) {
        submit(
            token, "PUT", "/api/president/roles",
            JSONObject().put("subject_id", subjectId).put("role_id", roleId).put("assigned", true),
            "Должность назначена. Сервер проверил полномочия и ограничение одной должности на человека."
        )
    }

    fun removeRole(token: String, subjectId: Int, roleId: Int) {
        submit(
            token, "PUT", "/api/president/roles",
            JSONObject().put("subject_id", subjectId).put("role_id", roleId).put("assigned", false),
            "Полномочия должностного лица прекращены."
        )
    }

    fun reviewApplication(token: String, id: Int, status: String) {
        submit(
            token, "PATCH", "/api/president/applications/$id",
            JSONObject().put("status", status).put("note", ""),
            if (status == "approved") "Заявление одобрено." else "Заявление отклонено."
        )
    }

    fun createDecree(token: String, title: String, text: String) {
        submit(token, "POST", "/api/president/decrees",
            JSONObject().put("title", title.trim()).put("text", text.trim()),
            "Указ опубликован.")
    }

    fun updateDecreeExecution(token: String, id: Int, status: String, note: String = "") {
        submit(token, "PATCH", "/api/president/decrees/$id/execution",
            JSONObject().put("status", status).put("note", note),
            "Статус исполнения указа обновлён.")
    }

    fun decideLaw(token: String, id: Int, status: String) {
        submit(token, "PATCH", "/api/president/laws/$id",
            JSONObject().put("status", status),
            if (status == "signed") "Закон подписан." else "По законопроекту применено вето.")
    }

    fun dissolve(token: String) {
        submit(token, "POST", "/api/president/parliament/dissolve", JSONObject(),
            "Решение о роспуске принято сервером.")
    }

    fun scheduleElection(token: String, title: String, starts: String, ends: String) {
        submit(token, "POST", "/api/president/elections/duma",
            JSONObject().put("title", title.trim()).put("starts_at", starts.trim()).put("ends_at", ends.trim()),
            "Выборы в Государственную Думу назначены.")
    }

    fun closeElection(token: String, electionId: Int) {
        submit(token, "POST", "/api/president/elections/duma/$electionId/close", JSONObject(),
            "Голосование завершено. Сервер зафиксировал итоги и обработал формирование созыва.")
    }

    fun clearNotices() {
        mutable.value = mutable.value.copy(error = null, message = null)
    }
}

@Composable
internal fun PresidentScreen(token: String, onBack: () -> Unit, onError: (String) -> Unit) {
    val vm: PresidentViewModel = viewModel()
    val state by vm.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var decreeDialog by remember { mutableStateOf(false) }
    var electionDialog by remember { mutableStateOf(false) }
    var dissolveDialog by remember { mutableStateOf(false) }
    var electionToClose by remember { mutableStateOf<JSONObject?>(null) }
    var lawToDecide by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var applicationToReview by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var appointmentDialog by remember { mutableStateOf(false) }
    var appointmentToRemove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedSubject by remember { mutableStateOf<JSONObject?>(null) }
    var selectedRole by remember { mutableStateOf<JSONObject?>(null) }
    var subjectSearch by remember { mutableStateOf("") }
    var decreeToUpdate by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    var subjectMenuExpanded by remember { mutableStateOf(false) }
    var roleMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(token, refresh) { vm.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Purple) }
            Text("ПРЕЗИДЕНТСКАЯ АДМИНИСТРАЦИЯ", color = Pink, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Text("Полномочия Президента", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Официальные решения доступны только аккаунту с серверными правами Президента.", color = Muted, fontSize = 11.sp)
        }
        if (state.loading) {
            item { PresidentNotice("Проверяем полномочия…", "Получаем актуальный статус с сервера ДКД.") }
        } else if (!state.authorized) {
            item { PresidentNotice("Доступ закрыт", "Для этого раздела необходима действующая должность Президента или права главного администратора. Интерфейс не заменяет серверную проверку доступа.") }
        } else {
            item {
                Surface(color = Panel2, shape = RoundedCornerShape(21.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(21.dp))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (state.isAdmin) "СЛУЖЕБНЫЙ ДОСТУП АДМИНИСТРАТОРА" else "ПРЕЗИДЕНТ ДКД", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(state.term?.string("message").orEmpty().ifBlank { "Полномочия подтверждены сервером" }, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        val expiry = state.term?.string("end_at").orEmpty()
                        if (expiry.isNotBlank()) Text("Окончание срока: ${expiry.take(10)}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            item { Text("УПРАВЛЕНИЕ ГОСУДАРСТВОМ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresidentAction("Опубликовать указ", "Новый президентский акт", Modifier.weight(1f), !state.busy) { decreeDialog = true }
                    PresidentAction("Назначить выборы", "Новая кампания в Думе", Modifier.weight(1f), !state.busy) { electionDialog = true }
                }
            }
            item { Text("КОНТРОЛЬ ИСПОЛНЕНИЯ УКАЗОВ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.decreesLoaded) {
                item { PresidentNotice("Реестр указов недоступен", "Не удалось получить президентские указы. Управление исполнением временно отключено.") }
            } else if (state.decrees.isEmpty()) {
                item { PresidentNotice("Опубликованных указов пока нет", "После публикации указ появится здесь, и Президент сможет контролировать его исполнение.") }
            } else {
                items(state.decrees, key = { "decree-${it.optInt("id")}" }) { decree ->
                    val decreeId = decree.optInt("id")
                    val executionStatus = decree.string("execution_status", "issued")
                    Surface(color = CardColor, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(16.dp))) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(decree.string("title", "Президентский указ"), color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(decree.string("text").take(360).ifBlank { "Текст указа недоступен." },
                                color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                            Text("Исполнение: " + when (executionStatus) {
                                "issued" -> "Издан · ожидает исполнения"
                                "in_progress" -> "Исполняется"
                                "completed" -> "Исполнен"
                                "suspended" -> "Приостановлен"
                                else -> executionStatus
                            }, color = when (executionStatus) {
                                "completed" -> Good
                                "suspended" -> Bad
                                else -> Pink
                            }, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            decree.string("execution_note").takeIf { it.isNotBlank() }?.let {
                                Text("Отметка: $it", color = Muted, fontSize = 9.sp)
                            }
                            if (executionStatus != "completed") {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    TextButton(enabled = !state.busy, onClick = {
                                        decreeToUpdate = Triple(decreeId, "in_progress", "Указ принят к исполнению")
                                    }) { Text("В работу", color = Purple, fontSize = 9.sp) }
                                    TextButton(enabled = !state.busy, onClick = {
                                        decreeToUpdate = Triple(decreeId, "completed", "Исполнение завершено")
                                    }) { Text("Исполнен", color = Good, fontSize = 9.sp) }
                                    TextButton(enabled = !state.busy, onClick = {
                                        decreeToUpdate = Triple(decreeId, "suspended", "Исполнение приостановлено")
                                    }) { Text("Приостановить", color = Bad, fontSize = 9.sp) }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("ВЫБОРЫ В ГОСУДАРСТВЕННУЮ ДУМУ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.dumaElectionsLoaded) {
                item { PresidentNotice("Сведения о выборах недоступны", "Не удалось проверить состояние думской кампании. Управление завершением временно отключено.") }
            } else if (state.dumaElections.isEmpty()) {
                item { PresidentNotice("Кампаний пока нет", "Президент может назначить новые выборы в Государственную Думу.") }
            } else {
                items(state.dumaElections) { election ->
                    val electionStatus = election.string("status")
                    Surface(color = CardColor, shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(15.dp))) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(election.string("title", "Выборы в Госдуму"), color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Статус: " + when (electionStatus) {
                                "active" -> "Голосование идёт"
                                "scheduled" -> "Ожидают начала"
                                "closed" -> "Завершены"
                                else -> electionStatus
                            }, color = if (electionStatus == "active") Good else Muted, fontSize = 10.sp)
                            Text("Учтено голосов: " + election.optInt("votes_total", 0), color = Muted, fontSize = 10.sp)
                            if (electionStatus == "active") {
                                OutlinedButton(
                                    onClick = { electionToClose = election },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad)
                                ) { Text("Завершить голосование") }
                            }
                        }
                    }
                }
            }
            item {
                Surface(color = Color(0xFF352536), shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF74485F), RoundedCornerShape(17.dp))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Роспуск Государственной Думы", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Необратимое действие: прекращает полномочия действующих депутатов. Сервер проверяет наличие парламента и ограничение — не чаще одного раза за 30 дней.", color = Color(0xFFD6C0CF), fontSize = 10.sp, lineHeight = 15.sp)
                        OutlinedButton(onClick = { dissolveDialog = true }, enabled = !state.busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Text("Рассмотреть роспуск")
                        }
                    }
                }
            }
            item {
                PresidentAction(
                    "Кадровые назначения",
                    "Премьер-министр и министр иностранных дел",
                    Modifier.fillMaxWidth(), !state.busy
                ) { appointmentDialog = true }
            }
            item { Text("НАЗНАЧЕННЫЕ ДОЛЖНОСТИ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            val appointed = state.subjects.filter { subject ->
                subject.array("roles").any { role -> role.string("name") in setOf("Премьер-министр", "Министр иностранных дел") }
            }
            if (!state.subjectsLoaded) {
                item { PresidentNotice("Список должностных лиц недоступен", "Не удалось загрузить данные с сервера. Обновите раздел, прежде чем выполнять кадровые назначения.") }
            } else if (appointed.isEmpty()) {
                item { PresidentNotice("Должности пока не назначены", "Президент может назначить Премьер-министра и Министра иностранных дел.") }
            } else {
                items(appointed, key = { "appointment-${it.optInt("id")}" }) { subject ->
                    val roleNames = subject.array("roles").map { it.string("name") }
                        .filter { it == "Премьер-министр" || it == "Министр иностранных дел" }
                    Surface(color = CardColor, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(14.dp))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(subject.string("full_name", "Должностное лицо"), color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(roleNames.joinToString(" · "), color = Pink, fontSize = 9.sp)
                            subject.array("roles")
                                .filter { it.string("name") in setOf("Премьер-министр", "Министр иностранных дел") }
                                .forEach { role ->
                                    TextButton(
                                        enabled = !state.busy,
                                        onClick = { appointmentToRemove = subject.optInt("id") to role.optInt("id") },
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("Снять полномочия: " + role.string("name"), color = Bad, fontSize = 10.sp) }
                                }
                        }
                    }
                }
            }
            item { Text("ЗАЯВЛЕНИЯ ГРАЖДАН", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.applicationsLoaded) {
                item { PresidentNotice("Заявления недоступны", "Не удалось получить список заявлений. Решения по обращениям временно недоступны.") }
            } else if (state.applications.isEmpty()) {
                item { PresidentNotice("Новых заявлений нет", "Заявления граждан, ожидающие решения, появятся здесь.") }
            }
            items(state.applications, key = { "application-${it.optInt("id")}" }) { application ->
                val applicationId = application.optInt("id")
                val status = application.string("status")
                Surface(color = CardColor, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(application.string("full_name", "Заявитель"), color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val kind = application.string("kind")
                        val detailsText = if (kind == "party_creation") {
                            runCatching {
                                val party = JSONObject(application.string("details"))
                                "Партия: ${party.optString("party_name")} ${party.optString("emoji")}\nОписание: ${party.optString("description").ifBlank { "не указано" }}"
                            }.getOrDefault("Данные заявки на партию не удалось прочитать.")
                        } else application.string("details").ifBlank { "Дополнительная информация не указана." }
                        Text(when (kind) {
                            "citizenship" -> "Заявление на гражданство"
                            "party_creation" -> "Заявка на создание партии"
                            else -> "Обращение гражданина"
                        }, color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(detailsText, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        Text("Статус: " + when (status) {
                            "pending" -> "На рассмотрении"
                            "approved" -> "Одобрено"
                            "rejected" -> "Отклонено"
                            else -> status
                        }, color = if (status == "approved") Good else if (status == "rejected") Bad else Pink, fontSize = 9.sp)
                        if (status == "pending") {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Button(onClick = { applicationToReview = applicationId to "approved" }, enabled = !state.busy,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                                    Text("Одобрить", color = White, fontSize = 10.sp)
                                }
                                OutlinedButton(onClick = { applicationToReview = applicationId to "rejected" }, enabled = !state.busy,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                    Text("Отклонить", color = Bad, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            item { Text("ЗАКОНОПРОЕКТЫ НА РЕШЕНИЕ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            if (!state.lawsLoaded) {
                item { PresidentNotice("Законопроекты недоступны", "Не удалось получить список законопроектов. Подписание и вето временно недоступны.") }
            } else if (state.laws.isEmpty()) {
                item { PresidentNotice("Законопроектов нет", "На сервере нет законопроектов для рассмотрения.") }
            }
            items(state.laws, key = { it.optInt("id") }) { law ->
                val id = law.optInt("id")
                val status = law.string("status")
                Surface(color = CardColor, shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(17.dp))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(law.string("title", "Законопроект"), color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(law.string("description").ifBlank { "Описание не предоставлено." }, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        Text("Статус: " + when (status) {
                            "awaiting_president" -> "Принят Госдумой — ожидает решения Президента"
                            "under_duma_review" -> "Рассматривается Государственной Думой"
                            "signed" -> "Подписан Президентом"
                            "vetoed" -> "Отклонён Президентом"
                            "rejected_by_duma" -> "Отклонён Государственной Думой"
                            else -> status
                        }, color = Pink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        if (status == "awaiting_president") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { lawToDecide = id to "signed" }, enabled = !state.busy,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Подписать", color = White, fontSize = 11.sp) }
                                OutlinedButton(onClick = { lawToDecide = id to "vetoed" }, enabled = !state.busy,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp)) { Text("Вето", color = Pink, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("↻  Обновить полномочия и законопроекты", color = Purple) }
            }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple) }
        state.message?.let { msg -> item { PresidentNotice("Операция выполнена", msg) } }
        state.error?.let { err -> item { PresidentNotice("Не удалось выполнить действие", err) } }
    }

    if (decreeDialog) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.busy) decreeDialog = false },
            containerColor = Panel,
            title = { Text("Новый президентский указ", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, label = { Text("Полный текст указа") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton(enabled = title.trim().length >= 2 && body.isNotBlank() && !state.busy,
                    onClick = { vm.createDecree(token, title, body); decreeDialog = false }) { Text("Опубликовать", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { decreeDialog = false }) { Text("Отмена", color = Muted) } }
        )
    }

    if (electionDialog) {
        var title by remember { mutableStateOf("Выборы в Государственную Думу") }
        var starts by remember { mutableStateOf("") }
        var ends by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.busy) electionDialog = false },
            containerColor = Panel,
            title = { Text("Назначить думские выборы", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Укажите даты в формате ISO 8601, например 2026-11-08T10:00:00+03:00.", color = Muted, fontSize = 10.sp)
                    OutlinedTextField(title, { title = it }, label = { Text("Название кампании") }, singleLine = true)
                    OutlinedTextField(starts, { starts = it }, label = { Text("Начало (ISO 8601)") }, singleLine = true)
                    OutlinedTextField(ends, { ends = it }, label = { Text("Окончание (ISO 8601)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = title.trim().length >= 2 && starts.isNotBlank() && ends.isNotBlank() && !state.busy,
                    onClick = { vm.scheduleElection(token, title, starts, ends); electionDialog = false }) { Text("Назначить", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { electionDialog = false }) { Text("Отмена", color = Muted) } }
        )
    }

    electionToClose?.let { election ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) electionToClose = null },
            containerColor = Panel,
            title = { Text("Завершить думские выборы?", color = White) },
            text = { Text(
                "Кампания «" + election.string("title", "Выборы в Госдуму") + "» будет закрыта. Сервер зафиксирует голоса и попытается сформировать новый состав Думы. Это действие нельзя отменить.",
                color = Muted, fontSize = 12.sp
            ) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    vm.closeElection(token, election.optInt("id"))
                    electionToClose = null
                }) { Text("Завершить выборы", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { electionToClose = null }) { Text("Отмена", color = Muted) } }
        )
    }

    if (dissolveDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) dissolveDialog = false },
            containerColor = Panel,
            title = { Text("Подтвердить роспуск Думы?", color = White) },
            text = { Text("Это действие прекращает полномочия действующих депутатов. Продолжить? Сервер дополнительно проверит все ограничения.", color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = { vm.dissolve(token); dissolveDialog = false }) { Text("Да, распустить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { dissolveDialog = false }) { Text("Отмена", color = Muted) } }
        )
    }

    appointmentToRemove?.let { removal ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) appointmentToRemove = null },
            containerColor = Panel,
            title = { Text("Прекратить полномочия?", color = White) },
            text = { Text("Назначенная должность будет снята с выбранного человека. Продолжить?", color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    vm.removeRole(token, removal.first, removal.second)
                    appointmentToRemove = null
                }) { Text("Снять должность", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { appointmentToRemove = null }) { Text("Отмена", color = Muted) } }
        )
    }

    if (appointmentDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) appointmentDialog = false },
            containerColor = Panel,
            title = { Text("Кадровое назначение", color = White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Президент может назначить только Премьер-министра и Министра иностранных дел. Каждая должность может принадлежать одному человеку.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    Box {
                        OutlinedButton(onClick = { roleMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedRole?.string("name") ?: "Выберите должность", color = White)
                        }
                        DropdownMenu(expanded = roleMenuExpanded, onDismissRequest = { roleMenuExpanded = false }) {
                            state.assignableRoles.forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.string("name")) },
                                    onClick = { selectedRole = role; roleMenuExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = subjectSearch,
                        onValueChange = { subjectSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Найти человека по ФИО или username") }
                    )
                    Box {
                        OutlinedButton(onClick = { subjectMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedSubject?.string("full_name") ?: "Выберите человека", color = White)
                        }
                        DropdownMenu(
                            expanded = subjectMenuExpanded,
                            onDismissRequest = { subjectMenuExpanded = false }
                        ) {
                            state.subjects.filter { subject ->
                                val query = subjectSearch.trim().removePrefix("@").lowercase()
                                query.isBlank() ||
                                    subject.string("full_name").lowercase().contains(query) ||
                                    subject.string("username").lowercase().contains(query)
                            }.take(40).forEach { subject ->
                                DropdownMenuItem(
                                    text = { Text(subject.string("full_name", "Участник ДКД") +
                                        subject.string("username").takeIf { it.isNotBlank() }?.let { " · @$it" }.orEmpty()) },
                                    onClick = { selectedSubject = subject; subjectMenuExpanded = false; subjectSearch = "" }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedRole != null && selectedSubject != null && !state.busy,
                    onClick = {
                        vm.assignRole(token, selectedSubject!!.optInt("id"), selectedRole!!.optInt("id"))
                        appointmentDialog = false
                        selectedRole = null
                        selectedSubject = null
                    }
                ) { Text("Назначить", color = Purple) }
            },
            dismissButton = {
                TextButton(onClick = {
                    appointmentDialog = false
                    selectedRole = null
                    selectedSubject = null
                }) { Text("Отмена", color = Muted) }
            }
        )
    }

    decreeToUpdate?.let { decision ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) decreeToUpdate = null },
            containerColor = Panel,
            title = { Text("Изменить исполнение указа?", color = White) },
            text = { Text(
                "Вы подтверждаете статус «" + when (decision.second) {
                    "in_progress" -> "В работе"
                    "completed" -> "Исполнен"
                    else -> "Приостановлен"
                } + "». Решение будет записано в журнал президентских действий.",
                color = Muted, fontSize = 12.sp
            ) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    vm.updateDecreeExecution(token, decision.first, decision.second, decision.third)
                    decreeToUpdate = null
                }) { Text("Подтвердить", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { decreeToUpdate = null }) { Text("Отмена", color = Muted) } }
        )
    }

    applicationToReview?.let { decision ->
        AlertDialog(
            onDismissRequest = { applicationToReview = null },
            containerColor = Panel,
            title = { Text(if (decision.second == "approved") "Одобрить заявление?" else "Отклонить заявление?", color = White) },
            text = { Text(
                if (decision.second == "approved") "При одобрении сервер выполнит действие, соответствующее типу заявления: выдаст гражданство или создаст партию заявителя."
                else "Заявление будет отклонено, а заявитель получит уведомление.",
                color = Muted, fontSize = 12.sp
            ) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    vm.reviewApplication(token, decision.first, decision.second)
                    applicationToReview = null
                }) { Text("Подтвердить", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { applicationToReview = null }) { Text("Отмена", color = Muted) } }
        )
    }

    lawToDecide?.let { decision ->
        AlertDialog(
            onDismissRequest = { lawToDecide = null },
            containerColor = Panel,
            title = { Text(if (decision.second == "signed") "Подписать закон?" else "Наложить вето?", color = White) },
            text = { Text("Решение будет отправлено на сервер и отразится в официальных документах и истории.", color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = { vm.decideLaw(token, decision.first, decision.second); lawToDecide = null }) {
                    Text("Подтвердить", color = Purple)
                }
            },
            dismissButton = { TextButton(onClick = { lawToDecide = null }) { Text("Отмена", color = Muted) } }
        )
    }
}

@Composable
private fun PresidentAction(title: String, subtitle: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = CardColor, shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, LineColor, RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 9.sp)
            TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text("Открыть →", color = Purple, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun PresidentNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
