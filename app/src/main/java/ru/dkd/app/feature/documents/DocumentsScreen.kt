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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dkd.app.core.network.DkdDocumentDto
import ru.dkd.app.core.network.PassportDto
import ru.dkd.app.core.design.*

private data class DocumentItem(
    val category: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val date: String,
    val status: String
)

@Composable
internal fun DocumentsScreen(token: String, onError: (String) -> Unit) {
    val viewModel: DocumentsViewModel = viewModel()
    val uiState by viewModel.state.collectAsState()
    var selected by remember { mutableStateOf<DocumentItem?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(uiState.error) { uiState.error?.let(onError) }

    val loading = uiState.loading
    val hasPassport = uiState.hasPassport
    val passportLoadFailed = uiState.passportLoadFailed
    val documentsLoadFailed = uiState.documentsLoadFailed
    val passport = uiState.passport
    val mandates = uiState.mandates
    val presidentCertificates = uiState.presidentCertificates
    val decrees = uiState.decrees
    val laws = uiState.laws

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("ЦИФРОВЫЕ ДОКУМЕНТЫ", color = Pink, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Text("Документы", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("Ваши удостоверения и официальные акты ДКД", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
        }

        item {
            if (loading && passport == null) {
                Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем документы…", color = Muted, fontSize = 12.sp)
                    }
                }
            } else if (passportLoadFailed) {
                EmptyDocumentNote("Паспорт не удалось загрузить", "Проверьте подключение и повторите обновление. Статус документа пока неизвестен.")
            } else if (hasPassport && passport != null) {
                PassportCard(passport = passport!!)
            } else {
                Surface(
                    color = CardColor, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(20.dp))
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Паспорт гражданина ДКД", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Паспорт появляется автоматически после одобрения заявления на гражданство.",
                            color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                        )
                        Text("ПОКА НЕ ВЫДАН", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }

        item { SectionHeading("Удостоверения и мандаты", mandates.size + presidentCertificates.size) }
        if (documentsLoadFailed) {
            item { EmptyDocumentNote("Список документов недоступен", "Проверьте подключение и обновите раздел. Пустой ответ сервера не считается подтверждением отсутствия документов.") }
        } else if (mandates.isEmpty() && presidentCertificates.isEmpty()) {
            item { EmptyDocumentNote("Активных удостоверений нет", "Мандат отображается только у действующего депутата. Президентское удостоверение — только у действующего Президента.") }
        }
        items(mandates, key = { "mandate-${it.id}" }) { doc ->
            DocumentRow(
                icon = "▤",
                title = doc.title.ifBlank { "Мандат депутата" },
                subtitle = "Мандат депутата Государственной Думы",
                status = "Действителен",
                onClick = {
                    selected = DocumentItem("Мандат", doc.title, "Удостоверение депутата Государственной Думы",
                        doc.text, doc.createdAt, "Действителен")
                }
            )
        }
        items(presidentCertificates, key = { "president-${it.id}" }) { doc ->
            DocumentRow(
                icon = "♛",
                title = doc.title.ifBlank { "Удостоверение Президента ДКД" },
                subtitle = "Документ главы государства",
                status = "Действительно",
                onClick = {
                    selected = DocumentItem("Президентское удостоверение", doc.title, "Документ главы государства",
                        doc.text, doc.createdAt, "Действительно")
                }
            )
        }

        item { SectionHeading("Президентские указы", decrees.size) }
        if (decrees.isEmpty() && !documentsLoadFailed) item { EmptyDocumentNote("Указов пока нет", "Здесь отображаются опубликованные президентские указы, доступные вашему аккаунту.") }
        items(decrees, key = { "decree-${it.id}" }) { doc ->
            DocumentRow(
                icon = "§",
                title = doc.title.ifBlank { "Президентский указ" },
                subtitle = doc.createdAt.take(10).ifBlank { "Официальный документ ДКД" },
                status = "Опубликован",
                onClick = {
                    selected = DocumentItem("Указ", doc.title, "Официальный документ Президента",
                        doc.text, doc.createdAt, "Опубликован")
                }
            )
        }

        item { SectionHeading("Законы, подписанные Президентом", laws.size) }
        if (laws.isEmpty() && !documentsLoadFailed) item { EmptyDocumentNote("Подписанных законов пока нет", "В этот список попадают только законы со статусом «подписан».") }
        items(laws, key = { "law-${it.id}" }) { doc ->
            DocumentRow(
                icon = "⚖",
                title = doc.title.ifBlank { "Закон ДКД" },
                subtitle = doc.description.ifBlank { "Законодательный акт" }.take(90),
                status = "Подписан",
                onClick = {
                    selected = DocumentItem("Закон", doc.title, "Законодательный акт",
                        doc.description, doc.createdAt, "Подписан Президентом")
                }
            )
        }

        item {
            Spacer(Modifier.height(7.dp))
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить документы", color = Purple)
            }
        }
    }

    selected?.let { document ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Panel,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(document.category.uppercase(), color = Pink, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    Text(document.title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(document.status, color = Good, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (document.date.isNotBlank()) Text("Дата: ${document.date.take(10)}", color = Muted, fontSize = 11.sp)
                    Text(document.body.ifBlank { document.subtitle }, color = White, fontSize = 13.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Закрыть", color = Purple) }
            }
        )
    }
}

@Composable
private fun PassportCard(passport: PassportDto) {
    Box(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFF7770B8), RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF3D2854), Color(0xFF24254D), Color(0xFF1A2446))),
                RoundedCornerShape(22.dp)
            ).padding(17.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ДКД  /  DIGITAL ID", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("ПАСПОРТ ГРАЖДАНИНА", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                }
                Text("🏛️", fontSize = 27.sp)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .16f))
            Text(passport.fullName.ifBlank { "Гражданин ДКД" }, color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("ФАМИЛИЯ, ИМЯ, ОТЧЕСТВО", color = Color(0xFFC5C7FF), fontSize = 8.sp, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("НОМЕР ДОКУМЕНТА", color = Muted, fontSize = 8.sp, letterSpacing = .8.sp)
                    Text(passport.number, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                val active = passport.status == "active"
                Text(
                    passport.statusLabel.ifBlank { if (active) "Действителен" else "Аннулирован" }.uppercase(),
                    color = if (active) Good else Bad,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .6.sp
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = .16f))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PassportMeta("ВЫДАН", passport.issuedAt)
                PassportMeta("КОД ПОДРАЗДЕЛЕНИЯ", passport.divisionCode)
            }
            Text(passport.issuedBy.ifBlank { "Служба гражданства ДКД" }, color = Color(0xFFC5C7FF), fontSize = 9.sp)
        }
    }
}

@Composable
private fun PassportMeta(label: String, value: String) {
    Column(Modifier.widthIn(max = 170.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted, fontSize = 8.sp, letterSpacing = .6.sp)
        Text(value.ifBlank { "—" }, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Surface(color = Color(0x338B7CFF), shape = RoundedCornerShape(20.dp)) {
            Text(count.toString(), color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun DocumentRow(icon: String, title: String, subtitle: String, status: String, onClick: () -> Unit) {
    Surface(
        color = CardColor, shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(17.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(43.dp).background(Color(0x338B7CFF), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) { Text(icon, color = Pink, fontSize = 21.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 2)
                Text(status, color = Good, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("›", color = Purple, fontSize = 24.sp)
        }
    }
}

@Composable
private fun EmptyDocumentNote(title: String, description: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
