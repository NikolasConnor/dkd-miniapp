package ru.dkd.app.feature.documents

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.dkd.app.core.design.*
import ru.dkd.app.core.network.DkdDocumentDto
import ru.dkd.app.core.network.MarriageDocumentDto
import ru.dkd.app.core.network.PassportDto

private enum class DocFilter(val title: String) {
    ALL("Все"), PERSONAL("Личные"), POWER("Власть"), FAMILY("Семья"), ACTS("Законы и акты")
}

private data class VisualDocument(
    val key: String,
    val title: String,
    val category: String,
    val number: String,
    val holder: String,
    val subtitle: String,
    val body: String,
    val date: String,
    val status: String,
    val accent: Color,
    val icon: String,
    val active: Boolean = true
)

@Composable
internal fun DocumentsScreen(token: String, onError: (String) -> Unit) {
    val viewModel: DocumentsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var selected by remember { mutableStateOf<VisualDocument?>(null) }
    var filter by remember { mutableStateOf(DocFilter.ALL) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    val docs = buildList {
        state.passport?.let { p ->
            add(VisualDocument(
                "passport", "Паспорт гражданина ДКД", "ЛИЧНЫЙ ДОКУМЕНТ",
                p.number.ifBlank { "Номер не указан" }, p.fullName.ifBlank { "Гражданин ДКД" },
                "Удостоверение личности гражданина", listOfNotNull(
                    "Гражданство: ${p.citizenship.ifBlank { "ДКД" }}",
                    "Страна: ${p.country.ifBlank { "ДКД" }}",
                    "Код подразделения: ${p.divisionCode.ifBlank { "—" }}",
                    "Выдан: ${p.issuedBy.ifBlank { "Служба гражданства ДКД" }}"
                ).joinToString("\n"),
                p.issuedAt, p.statusLabel.ifBlank { if (p.status == "active") "Действителен" else "Статус уточняется" },
                Color(0xFF8EA8FF), "▣", p.status == "active"
            ))
        }
        state.mandates.forEach { d ->
            add(VisualDocument(
                "mandate-${d.id}", "Мандат депутата", "ГОСУДАРСТВЕННОЕ УДОСТОВЕРЕНИЕ",
                "№ ${d.id}", "", "Государственная Дума ДКД", d.text,
                d.createdAt, "Действителен", Color(0xFFDBB36A), "🏛"
            ))
        }
        state.presidentCertificates.forEach { d ->
            add(VisualDocument(
                "president-${d.id}", "Удостоверение Президента", "ВЫСШЕЕ ГОСУДАРСТВЕННОЕ УДОСТОВЕРЕНИЕ",
                "№ ${d.id}", "", "Президент Демократического Конституционного Государства",
                d.text, d.createdAt, "Действительно", Color(0xFFE0B66D), "♛"
            ))
        }
        state.marriages.filter { it.status == "active" || it.status == "divorced" }.forEach { m ->
            val divorced = m.status == "divorced"
            add(VisualDocument(
                "marriage-${m.id}", if (divorced) "Свидетельство о разводе" else "Свидетельство о браке",
                if (divorced) "СЕМЕЙНЫЙ ДОКУМЕНТ · РАСТОРЖЕНИЕ" else "СЕМЕЙНЫЙ ДОКУМЕНТ · РЕГИСТРАЦИЯ",
                "Реестровая запись № ${m.id}", "Вы и ${m.partner?.fullName?.ifBlank { "второй супруг" } ?: "второй супруг"}",
                if (divorced) "Запись о расторжении брака" else "Запись о заключении брака",
                "Второй участник: ${m.partner?.fullName ?: "не указан"}\nДата регистрации: ${m.acceptedAt?.take(10) ?: "—"}\nДата расторжения: ${m.endedAt?.take(10) ?: "—"}",
                if (divorced) m.endedAt.orEmpty() else m.acceptedAt.orEmpty(),
                if (divorced) "Брак расторгнут" else "Действительно",
                if (divorced) Color(0xFFB6A0E8) else Color(0xFFE78EAD),
                if (divorced) "♡" else "∞", !divorced
            ))
        }
        state.decrees.forEach { d ->
            add(VisualDocument(
                "decree-${d.id}", d.title.ifBlank { "Указ Президента" }, "ОФИЦИАЛЬНЫЙ АКТ",
                "№ ${d.id}", "Государство ДКД", "Указ Президента",
                d.text, d.createdAt, "Опубликован", Color(0xFFD6A36C), "§"
            ))
        }
        state.laws.forEach { d ->
            add(VisualDocument(
                "law-${d.id}", d.title.ifBlank { "Закон ДКД" }, "ЗАКОНОДАТЕЛЬНЫЙ АКТ",
                "№ ${d.id}", "Государство ДКД", "Закон, подписанный Президентом",
                d.description, d.createdAt, "Подписан", Color(0xFF79C5AE), "⚖"
            ))
        }
    }

    val visible = docs.filter { doc ->
        when (filter) {
            DocFilter.ALL -> true
            DocFilter.PERSONAL -> doc.key == "passport"
            DocFilter.POWER -> doc.key.startsWith("mandate-") || doc.key.startsWith("president-")
            DocFilter.FAMILY -> doc.key.startsWith("marriage-")
            DocFilter.ACTS -> doc.key.startsWith("decree-") || doc.key.startsWith("law-")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 17.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ЦИФРОВОЙ РЕЕСТР ДКД", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Мои документы", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Официальные удостоверения, свидетельства и государственные акты", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Surface(
                color = Color(0xFF20264A),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF59649A), RoundedCornerShape(20.dp))
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ВАШ ЦИФРОВОЙ ПОРТФЕЛЬ", color = Color(0xFFBFC9FF), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("${docs.size} документов и записей", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Данные загружаются из государственного реестра", color = Muted, fontSize = 9.sp)
                    }
                    Text("▤", color = Color(0xFFBFC9FF), fontSize = 38.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                DocFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.title, fontSize = 9.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple.copy(alpha = .35f),
                            selectedLabelColor = White,
                            labelColor = Muted
                        )
                    )
                }
            }
        }
        if (state.loading && docs.isEmpty()) {
            item { DocumentNotice("Синхронизация с реестром", "Загружаем ваши удостоверения и официальные записи…") }
        }
        if (state.passportLoadFailed || state.documentsLoadFailed) {
            item { DocumentNotice("Не все реестры доступны", "Показываем только те сведения, которые сервер подтвердил. Обновите раздел, когда соединение восстановится.") }
        }
        if (!state.loading && visible.isEmpty()) {
            item {
                DocumentNotice(
                    if (filter == DocFilter.ALL) "Пока нет выданных документов" else "В этой категории пока пусто",
                    when (filter) {
                        DocFilter.POWER -> "Мандат депутата выдаётся действующему депутату, удостоверение Президента — действующему главе государства."
                        DocFilter.FAMILY -> "Свидетельства появятся после регистрации брака или подтверждённого расторжения."
                        DocFilter.PERSONAL -> "Паспорт появляется после получения гражданства ДКД."
                        else -> "Когда документ будет официально оформлен и опубликован, он появится здесь."
                    }
                )
            }
        }
        items(visible, key = { it.key }) { doc ->
            OfficialDocumentCard(doc = doc, onClick = { selected = doc })
        }
        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить реестр", color = Purple, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    selected?.let { doc ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Color(0xFF171A35),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(doc.category, color = doc.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(doc.title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(doc.status, color = if (doc.active) Good else Color(0xFFE5B46D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(doc.number, color = doc.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (doc.holder.isNotBlank()) Text(doc.holder, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(doc.subtitle, color = Muted, fontSize = 11.sp)
                    if (doc.date.isNotBlank()) Text("Дата: ${doc.date.take(10)}", color = Muted, fontSize = 10.sp)
                    if (doc.body.isNotBlank()) Text(doc.body, color = White, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Сведения получены из реестра ДКД. QR-проверка и PDF доступны только при наличии соответствующих данных/функций сервера.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Закрыть", color = Purple) }
            }
        )
    }
}

@Composable
private fun OfficialDocumentCard(doc: VisualDocument, onClick: () -> Unit) {
    val shape = RoundedCornerShape(21.dp)
    Surface(
        color = Color.Transparent,
        shape = shape,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(listOf(
                    doc.accent.copy(alpha = .22f),
                    Color(0xFF252747),
                    Color(0xFF1B1D39)
                )),
                shape
            ).border(1.dp, doc.accent.copy(alpha = .68f), shape)
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ДКД  •  ОФИЦИАЛЬНЫЙ ДОКУМЕНТ", color = doc.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
                        Text(doc.category, color = Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    }
                    Text(doc.icon, color = doc.accent, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = doc.accent.copy(alpha = .35f))
                Text(doc.title.uppercase(), color = White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .4.sp)
                if (doc.holder.isNotBlank()) {
                    Text(doc.holder, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(doc.subtitle, color = Color(0xFFD0D2EC), fontSize = 10.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(doc.number, color = doc.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (doc.date.isNotBlank()) Text("ВЫДАН / ОФОРМЛЕН  ${doc.date.take(10)}", color = Muted, fontSize = 8.sp)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (doc.active) "● ДЕЙСТВУЕТ" else "● ${doc.status.uppercase()}", color = if (doc.active) Good else Color(0xFFE5B46D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text("ОТКРЫТЬ  ›", color = doc.accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = doc.accent.copy(alpha = .25f))
                Text("ДЕМОКРАТИЧЕСКОЕ КОНСТИТУЦИОННОЕ ГОСУДАРСТВО  ·  ДКД", color = Muted, fontSize = 7.sp, letterSpacing = .5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DocumentNotice(title: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
