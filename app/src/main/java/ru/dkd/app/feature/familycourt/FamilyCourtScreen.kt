package ru.dkd.app.feature.familycourt

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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.dkd.app.core.network.apiCall

private val Navy = Color(0xFF111329)
private val Panel = Color(0xFF20213F)
private val Panel2 = Color(0xFF292A4B)
private val Accent = Color(0xFF9A8CFF)
private val TextMain = Color(0xFFF5F5FF)
private val TextMuted = Color(0xFFB8B9D4)
private val Good = Color(0xFF79D6B0)
private val Warning = Color(0xFFFFC477)

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject.text(key: String, fallback: String = ""): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" } ?: fallback

@Composable
internal fun FamilyCourtScreen(
    token: String,
    onBack: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var participants by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var marriagePeople by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cases by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var marriages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var certificates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var adminAppeals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    var selectedDefendant by remember { mutableIntStateOf(0) }
    var defendantMenu by remember { mutableStateOf(false) }
    var selectedPartner by remember { mutableIntStateOf(0) }
    var partnerMenu by remember { mutableStateOf(false) }
    var claimTitle by remember { mutableStateOf("") }
    var claimText by remember { mutableStateOf("") }

    suspend fun refresh() {
        loading = true
        val peopleResult = apiCall(token, "GET", "/api/judicial/participants")
        val casesResult = apiCall(token, "GET", "/api/judicial/cases")
        val marriagePeopleResult = apiCall(token, "GET", "/api/marriages/people")
        val marriagesResult = apiCall(token, "GET", "/api/marriages")
        participants = peopleResult.data?.optJSONArray("participants").objects()
        cases = casesResult.data?.optJSONArray("cases").objects()
        marriagePeople = marriagePeopleResult.data?.optJSONArray("people").objects()
        marriages = marriagesResult.data?.optJSONArray("marriages").objects()
        certificates = marriagesResult.data?.optJSONArray("certificates").objects()
        if (selectedDefendant == 0 && participants.isNotEmpty()) {
            selectedDefendant = participants.first().optInt("user_id")
        }
        if (selectedPartner == 0 && marriagePeople.isNotEmpty()) {
            selectedPartner = marriagePeople.first().optInt("user_id")
        }
        // Не-администраторы получат 403; это штатно и не мешает обычному разделу.
        val appealResult = apiCall(token, "GET", "/api/admin/judicial/appeals")
        adminAppeals = appealResult.data?.optJSONArray("appeals").objects()
        loading = false
        error = peopleResult.error ?: casesResult.error ?: marriagePeopleResult.error ?: marriagesResult.error
    }

    suspend fun perform(method: String, path: String, payload: JSONObject? = null) {
        busy = true
        error = null
        message = null
        val result = apiCall(token, method, path, payload)
        busy = false
        if (result.error != null) {
            error = result.error
        } else {
            message = result.data?.text("message", "Изменения сохранены.")
            refresh()
        }
    }

    LaunchedEffect(token) { refresh() }

    Column(
        Modifier.fillMaxSize().background(Navy)
    ) {
        Column(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 17.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("← Назад", color = Accent)
            }
            Text("ГОСУДАРСТВЕННЫЕ СЕРВИСЫ", color = Accent, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Text("Суд и семья", color = TextMain, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Судебные дела, браки и официальные свидетельства ДКД", color = TextMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("⚖ Суд") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent, selectedLabelColor = Navy,
                        labelColor = TextMuted
                    )
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("💍 Браки") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent, selectedLabelColor = Navy,
                        labelColor = TextMuted
                    )
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                    Text("Обновить", color = Accent, fontSize = 11.sp)
                }
            }
        }

        if (error != null) {
            NoticeCard("Не удалось загрузить данные", error.orEmpty(), Warning)
        }
        if (message != null) {
            NoticeCard("Готово", message.orEmpty(), Good)
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else if (tab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard {
                        Text("ПОДАТЬ ИСК", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Новое судебное дело", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Укажите участника, к которому предъявляется иск, название и обстоятельства дела.", color = TextMuted, fontSize = 11.sp)
                        Box {
                            OutlinedButton(onClick = { defendantMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                val chosen = participants.firstOrNull { it.optInt("user_id") == selectedDefendant }
                                Text(chosen?.text("full_name", "Выберите ответчика") ?: "Выберите ответчика", color = TextMain)
                            }
                            DropdownMenu(expanded = defendantMenu, onDismissRequest = { defendantMenu = false }) {
                                participants.filter { it.optInt("user_id") != 0 }.forEach { person ->
                                    DropdownMenuItem(
                                        text = { Text(person.text("full_name", "Участник ДКД")) },
                                        onClick = {
                                            selectedDefendant = person.optInt("user_id")
                                            defendantMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = claimTitle, onValueChange = { claimTitle = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Название дела (4–160 символов)") }
                        )
                        OutlinedTextField(
                            value = claimText, onValueChange = { claimText = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 4,
                            label = { Text("Иск и обстоятельства (20–8000 символов)") }
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    perform("POST", "/api/judicial/cases",
                                        JSONObject().put("defendant_user_id", selectedDefendant)
                                            .put("title", claimTitle.trim())
                                            .put("claim_text", claimText.trim()))
                                    if (error == null) { claimTitle = ""; claimText = "" }
                                }
                            },
                            enabled = !busy && selectedDefendant > 0 && claimTitle.trim().length in 4..160 && claimText.trim().length in 20..8000,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Navy)
                        ) { Text(if (busy) "Отправка…" else "Зарегистрировать иск", fontWeight = FontWeight.Bold) }
                    }
                }

                item {
                    Text("МОИ СУДЕБНЫЕ ДЕЛА", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                if (cases.isEmpty()) {
                    item { NoticeCard("Дел пока нет", "Здесь появятся иски, в которых вы участвуете.", TextMuted) }
                }
                items(cases, key = { it.optInt("id") }) { case ->
                    JudicialCaseCard(
                        item = case,
                        busy = busy,
                        onResponse = { value ->
                            scope.launch { perform("POST", "/api/judicial/cases/${case.optInt("id")}/response", JSONObject().put("text", value)) }
                        },
                        onJudge = {
                            scope.launch { perform("POST", "/api/judicial/cases/${case.optInt("id")}/judge") }
                        },
                        onAppeal = { value ->
                            scope.launch { perform("POST", "/api/judicial/cases/${case.optInt("id")}/appeals", JSONObject().put("appeal_text", value)) }
                        }
                    )
                }

                if (adminAppeals.isNotEmpty()) {
                    item {
                        Text("АПЕЛЛЯЦИИ ДЛЯ АДМИНИСТРАТОРА", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    items(adminAppeals.filter { it.text("status") == "pending" }, key = { "appeal-${it.optInt("id")}" }) { appeal ->
                        AdminAppealCard(appeal = appeal, busy = busy, onReview = { outcome, decision ->
                            scope.launch {
                                perform("PATCH", "/api/admin/judicial/appeals/${appeal.optInt("id")}",
                                    JSONObject().put("outcome", outcome).put("admin_decision", decision))
                            }
                        })
                    }
                }
                item {
                    SectionCard {
                        Text("СУДЕБНЫЙ ПРОЦЕСС", color = Accent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Text("Истец подаёт иск, ответчик представляет позицию, затем любая из сторон может передать дело ИИ-судье. После решения каждой стороне доступна одна апелляция; апелляции рассматривает администратор.", color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard {
                        Text("ПРЕДЛОЖЕНИЕ О БРАКЕ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Зарегистрировать брак", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("Выберите участника ДКД. Брак будет зарегистрирован только после его согласия.", color = TextMuted, fontSize = 11.sp)
                        Box {
                            OutlinedButton(onClick = { partnerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                val chosen = marriagePeople.firstOrNull { it.optInt("user_id") == selectedPartner }
                                Text(chosen?.text("full_name", "Выберите участника") ?: "Выберите участника", color = TextMain)
                            }
                            DropdownMenu(expanded = partnerMenu, onDismissRequest = { partnerMenu = false }) {
                                marriagePeople.forEach { person ->
                                    DropdownMenuItem(
                                        text = { Text(person.text("full_name", "Участник ДКД")) },
                                        onClick = {
                                            selectedPartner = person.optInt("user_id")
                                            partnerMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { scope.launch { perform("POST", "/api/marriages", JSONObject().put("target_user_id", selectedPartner)) } },
                            enabled = !busy && selectedPartner > 0,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Navy)
                        ) { Text("Отправить предложение", fontWeight = FontWeight.Bold) }
                    }
                }
                item { Text("ИСТОРИЯ БРАКОВ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                if (marriages.isEmpty()) {
                    item { NoticeCard("Записей пока нет", "Отправленные вам и ваши предложения появятся здесь.", TextMuted) }
                }
                items(marriages, key = { it.optInt("id") }) { marriage ->
                    MarriageCard(marriage = marriage, busy = busy, onAction = { action ->
                        val id = marriage.optInt("id")
                        val method: String
                        val path: String
                        when (action) {
                            "accept" -> { method = "POST"; path = "/api/marriages/$id/accept" }
                            "reject" -> { method = "POST"; path = "/api/marriages/$id/reject" }
                            "cancel" -> { method = "DELETE"; path = "/api/marriages/$id" }
                            else -> { method = "POST"; path = "/api/marriages/$id/divorce" }
                        }
                        scope.launch { perform(method, path) }
                    })
                }
                if (certificates.isNotEmpty()) {
                    item { Text("СВИДЕТЕЛЬСТВА", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                    items(certificates, key = { "certificate-${it.optInt("id")}" }) { certificate ->
                        SectionCard {
                            Text("ОФИЦИАЛЬНЫЙ ДОКУМЕНТ ДКД", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(certificate.text("title", "Свидетельство о заключении брака"), color = TextMain, fontWeight = FontWeight.Bold)
                            Text("№ ${certificate.text("number", "—")}", color = TextMuted, fontSize = 11.sp)
                            Text("${certificate.text("partner_one_name")} и ${certificate.text("partner_two_name")}", color = TextMain, fontSize = 13.sp)
                            Text(certificate.text("status_label"), color = if (certificate.text("status") == "divorced") Warning else Good, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(certificate.text("text"), color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JudicialCaseCard(
    item: JSONObject,
    busy: Boolean,
    onResponse: (String) -> Unit,
    onJudge: () -> Unit,
    onAppeal: (String) -> Unit
) {
    val id = item.optInt("id")
    val status = item.text("status")
    val role = if (item.text("plaintiff_user_id") == item.text("my_user_id")) "Истец" else item.text("my_role", "")
    var response by remember(id) { mutableStateOf("") }
    var appeal by remember(id) { mutableStateOf("") }
    val statusLabel = when (status) {
        "awaiting_response" -> "Ожидается ответ ответчика"
        "ready_for_judge" -> "Готово к рассмотрению ИИ-судьёй"
        "decided" -> "Решение вынесено"
        "appeal_pending" -> "Апелляция на рассмотрении"
        "closed" -> "Дело закрыто"
        else -> status
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Дело №$id", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(statusLabel, color = if (status == "decided" || status == "closed") Good else Warning, fontSize = 9.sp)
        }
        Text(item.text("title"), color = TextMain, fontWeight = FontWeight.SemiBold)
        Text("Истец: ${item.text("plaintiff_name")}\nОтветчик: ${item.text("defendant_name")}", color = TextMuted, fontSize = 10.sp)
        Text(item.text("claim_text"), color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        if (item.text("defendant_response").isNotBlank()) {
            Text("ПОЗИЦИЯ ОТВЕТЧИКА", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(item.text("defendant_response"), color = TextMuted, fontSize = 11.sp)
        }
        if (item.text("ai_decision").isNotBlank()) {
            Text("РЕШЕНИЕ ИИ-СУДЬИ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(item.text("ai_decision"), color = TextMain, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (item.text("final_decision").isNotBlank()) {
            Text("ИТОГ АПЕЛЛЯЦИИ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(item.text("final_decision"), color = TextMain, fontSize = 11.sp)
        }
        if (status == "awaiting_response" && item.text("my_role") == "defendant") {
            OutlinedTextField(
                value = response, onValueChange = { response = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Ваш ответ (20–8000 символов)") }
            )
            Button(onClick = { onResponse(response.trim()) }, enabled = !busy && response.trim().length in 20..8000) {
                Text("Отправить ответ")
            }
        }
        if (status == "ready_for_judge") {
            Button(onClick = onJudge, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Navy)) {
                Text("Передать дело ИИ-судье")
            }
        }
        if ((status == "decided" || status == "appeal_pending") && !item.optBoolean("my_appeal_used")) {
            OutlinedTextField(
                value = appeal, onValueChange = { appeal = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Основания апелляции (20–8000 символов)") }
            )
            OutlinedButton(onClick = { onAppeal(appeal.trim()) }, enabled = !busy && appeal.trim().length in 20..8000) {
                Text("Подать апелляцию")
            }
        }
    }
}

@Composable
private fun MarriageCard(
    marriage: JSONObject,
    busy: Boolean,
    onAction: (String) -> Unit
) {
    val status = marriage.text("status")
    val statusLabel = when (status) {
        "proposed" -> if (marriage.optBoolean("can_accept")) "Входящее предложение" else "Предложение отправлено"
        "active" -> "В браке"
        "divorce_pending" -> if (marriage.optBoolean("can_confirm_divorce")) "Ожидает вашего подтверждения развода" else "Ожидается подтверждение супруга"
        "divorced" -> "Брак расторгнут"
        "rejected" -> "Предложение отклонено"
        else -> status
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💍", fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(marriage.text("other_name", "Участник ДКД"), color = TextMain, fontWeight = FontWeight.Bold)
                Text(statusLabel, color = if (status == "active") Good else TextMuted, fontSize = 10.sp)
            }
        }
        Text("Участники: ${marriage.text("partner_one_name")} и ${marriage.text("partner_two_name")}", color = TextMuted, fontSize = 10.sp)
        if (marriage.optBoolean("can_accept")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction("accept") }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Navy)) { Text("Принять") }
                OutlinedButton(onClick = { onAction("reject") }, enabled = !busy) { Text("Отклонить") }
            }
        }
        if (marriage.optBoolean("can_cancel")) {
            OutlinedButton(onClick = { onAction("cancel") }, enabled = !busy) { Text("Отменить предложение") }
        }
        if (marriage.optBoolean("can_divorce")) {
            OutlinedButton(onClick = { onAction("divorce") }, enabled = !busy) { Text("Подать заявление на развод") }
        }
        if (marriage.optBoolean("can_confirm_divorce")) {
            Button(onClick = { onAction("divorce") }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Warning, contentColor = Navy)) {
                Text("Подтвердить развод")
            }
        }
    }
}

@Composable
private fun AdminAppealCard(
    appeal: JSONObject,
    busy: Boolean,
    onReview: (String, String) -> Unit
) {
    var decision by remember(appeal.optInt("id")) { mutableStateOf("") }
    var outcome by remember(appeal.optInt("id")) { mutableStateOf("upheld") }
    SectionCard {
        Text("Апелляция №${appeal.optInt("id")} · дело №${appeal.optInt("case_id")}", color = TextMain, fontWeight = FontWeight.Bold)
        Text(appeal.text("case_title"), color = Accent, fontSize = 12.sp)
        Text("Заявитель: ${appeal.text("appellant_name", "участник ДКД")}", color = TextMuted, fontSize = 10.sp)
        Text(appeal.text("appeal_text"), color = TextMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = outcome == "upheld", onClick = { outcome = "upheld" }, label = { Text("Оставить") })
            FilterChip(selected = outcome == "changed", onClick = { outcome = "changed" }, label = { Text("Изменить") })
        }
        OutlinedTextField(value = decision, onValueChange = { decision = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Решение администратора (20–8000 символов)") })
        Button(onClick = { onReview(outcome, decision.trim()) }, enabled = !busy && decision.trim().length in 20..8000) {
            Text("Рассмотреть апелляцию")
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF45466F), RoundedCornerShape(18.dp)),
        color = Panel2,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun NoticeCard(title: String, body: String, color: Color) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(body, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
