package ru.dkd.app.feature.applications

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
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*
import ru.dkd.app.core.network.apiCall

private data class ApplicationsState(
    val loading: Boolean = true,
    val sending: Boolean = false,
    val applications: List<JSONObject> = emptyList(),
    val applicationsLoaded: Boolean = false,
    val profileLoaded: Boolean = false,
    val isCitizen: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

private class ApplicationsViewModel : ViewModel() {
    private val mutable = MutableStateFlow(ApplicationsState())
    val state = mutable.asStateFlow()

    fun refresh(token: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(
                loading = true,
                applicationsLoaded = false,
                profileLoaded = false,
                error = null
            )
            val result = apiCall(token, "GET", "/api/applications")
            val profileResult = apiCall(token, "GET", "/api/profile")
            val profile = profileResult.data?.optJSONObject("profile") ?: profileResult.data
            val applicationsLoaded = result.error == null && result.data?.has("applications") == true
            val profileLoaded = profileResult.error == null && profile?.has("citizenship") == true
            mutable.value = mutable.value.copy(
                loading = false,
                applications = if (applicationsLoaded) result.data!!.array("applications") else mutable.value.applications,
                applicationsLoaded = applicationsLoaded,
                profileLoaded = profileLoaded,
                isCitizen = profileLoaded && profile?.string("citizenship") == "Гражданин ДКД",
                error = result.error ?: profileResult.error
            )
        }
    }

    fun submitParty(token: String, name: String, emoji: String, description: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(sending = true, error = null, message = null)
            val result = apiCall(
                token, "POST", "/api/parties/applications",
                JSONObject().put("name", name.trim()).put("emoji", emoji.trim().ifBlank { "🎭" })
                    .put("description", description.trim())
            )
            if (result.error != null) {
                mutable.value = mutable.value.copy(sending = false, error = result.error)
            } else {
                mutable.value = mutable.value.copy(
                    sending = false, loading = true, applicationsLoaded = false,
                    message = "Заявка на создание партии отправлена Президенту."
                )
                val refreshed = apiCall(token, "GET", "/api/applications")
                val loaded = refreshed.error == null && refreshed.data?.has("applications") == true
                mutable.value = mutable.value.copy(
                    loading = false,
                    applications = if (loaded) refreshed.data!!.array("applications") else mutable.value.applications,
                    applicationsLoaded = loaded,
                    error = refreshed.error
                )
            }
        }
    }

    fun submit(token: String, fullName: String, details: String) {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(sending = true, error = null, message = null)
            val result = apiCall(
                token, "POST", "/api/applications",
                JSONObject().put("kind", "citizenship")
                    .put("full_name", fullName.trim())
                    .put("details", details.trim())
            )
            if (result.error != null) {
                mutable.value = mutable.value.copy(sending = false, error = result.error)
            } else {
                mutable.value = mutable.value.copy(
                    sending = false,
                    loading = true,
                    applicationsLoaded = false,
                    message = "Заявление отправлено на рассмотрение."
                )
                val refreshed = apiCall(token, "GET", "/api/applications")
                val refreshedLoaded = refreshed.error == null && refreshed.data?.has("applications") == true
                mutable.value = mutable.value.copy(
                    loading = false,
                    applications = if (refreshedLoaded) refreshed.data!!.array("applications") else mutable.value.applications,
                    applicationsLoaded = refreshedLoaded,
                    error = refreshed.error
                )
            }
        }
    }
}

@Composable
internal fun ApplicationsScreen(token: String, onBack: () -> Unit, onError: (String) -> Unit) {
    val vm: ApplicationsViewModel = viewModel()
    val state by vm.state.collectAsState()
    var fullName by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var partyName by remember { mutableStateOf("") }
    var partyEmoji by remember { mutableStateOf("🎭") }
    var partyDescription by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { vm.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    val pending = state.applications.any { it.string("kind") == "citizenship" && it.string("status") == "pending" }
    val partyPending = state.applications.any { it.string("kind") == "party_creation" && it.string("status") == "pending" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Purple) }
            Text("ГОСУДАРСТВЕННЫЕ УСЛУГИ", color = Pink, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Text("Заявления", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Подача и отслеживание обращений в органы ДКД", color = Muted, fontSize = 11.sp)
        }
        item {
            Surface(color = Panel2, shape = RoundedCornerShape(19.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF55508E), RoundedCornerShape(19.dp))) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ЗАЯВЛЕНИЕ НА ГРАЖДАНСТВО ДКД", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Получение гражданства", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("После одобрения заявления сервер оформляет гражданство и паспорт. Повторная подача при уже имеющемся гражданстве или активном заявлении будет отклонена сервером.", color = Color(0xFFD0D3F3), fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        if (!state.profileLoaded || !state.applicationsLoaded) {
            item {
                ApplicationNotice(
                    if (state.loading) "Проверяем данные" else "Подача временно недоступна",
                    if (state.loading) "Получаем статус гражданства и историю заявлений с сервера."
                    else "Не удалось достоверно проверить гражданство или активные заявления. Обновите раздел; форма подачи пока скрыта, чтобы не создать повторное обращение."
                )
            }
        } else if (state.isCitizen) {
            item { ApplicationNotice("Гражданство уже подтверждено", "Сервер вернул одобренное заявление. Проверьте выданный паспорт в разделе «Документы».") }
        } else if (pending) {
            item { ApplicationNotice("Заявление на рассмотрении", "Новое заявление на гражданство нельзя подать, пока текущее не рассмотрено.") }
        } else {
            item {
                Surface(color = CardColor, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(18.dp))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(
                            value = fullName, onValueChange = { fullName = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Фамилия, имя, отчество") }
                        )
                        OutlinedTextField(
                            value = details, onValueChange = { details = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text("Дополнительная информация (необязательно)") }
                        )
                        Button(
                            onClick = { vm.submit(token, fullName, details) },
                            enabled = fullName.trim().length >= 2 && !state.sending,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple)
                        ) {
                            if (state.sending) CircularProgressIndicator(Modifier.size(17.dp), color = White, strokeWidth = 2.dp)
                            else Text("Подать заявление", color = White)
                        }
                    }
                }
            }
        }
        if (state.profileLoaded && state.applicationsLoaded && state.isCitizen) {
            item {
                Surface(color = CardColor, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(18.dp))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("ЗАЯВКА НА СОЗДАНИЕ ПАРТИИ", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Учредить политическую партию", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Партия появится только после одобрения Президентом. Председателем станет заявитель.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        if (partyPending) {
                            ApplicationNotice("Заявка уже у Президента", "Дождитесь решения по текущей заявке.")
                        } else {
                            OutlinedTextField(value = partyName, onValueChange = { partyName = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название партии") })
                            OutlinedTextField(value = partyEmoji, onValueChange = { partyEmoji = it.take(8) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Эмодзи партии") })
                            OutlinedTextField(value = partyDescription, onValueChange = { partyDescription = it },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Программа / описание") })
                            Button(onClick = { vm.submitParty(token, partyName, partyEmoji, partyDescription) },
                                enabled = partyName.trim().length >= 2 && !state.sending,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                                if (state.sending) CircularProgressIndicator(Modifier.size(17.dp), color = White, strokeWidth = 2.dp)
                                else Text("Отправить Президенту", color = White)
                            }
                        }
                    }
                }
            }
        }
        state.message?.let { msg -> item { ApplicationNotice("Готово", msg) } }
        item { Text("ИСТОРИЯ ЗАЯВЛЕНИЙ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
        if (state.loading && state.applications.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Purple, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Загружаем историю…", color = Muted, fontSize = 11.sp)
                }
            }
        } else if (state.applications.isEmpty()) {
            item { ApplicationNotice("Заявлений пока нет", "Поданные обращения и решения по ним появятся здесь.") }
        }
        items(state.applications, key = { it.optInt("id") }) { application ->
            val status = application.string("status")
            val label = when (status) {
                "pending" -> "На рассмотрении"
                "approved" -> "Одобрено"
                "rejected" -> "Отклонено"
                else -> status
            }
            val color = when (status) {
                "approved" -> Good
                "rejected" -> Bad
                else -> Pink
            }
            Surface(color = CardColor, shape = RoundedCornerShape(15.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(15.dp))) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(when (application.string("kind")) {
                        "citizenship" -> "Гражданство ДКД"
                        "party_creation" -> "Создание политической партии"
                        else -> "Обращение"
                    }, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(application.string("created_at").take(10), color = Muted, fontSize = 9.sp)
                    application.string("review_note").takeIf { it.isNotBlank() }?.let {
                        Text("Комментарий: $it", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
        item { TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("↻  Обновить заявления", color = Purple) } }
    }
}

@Composable
private fun ApplicationNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
