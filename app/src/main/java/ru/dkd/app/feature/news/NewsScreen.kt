package ru.dkd.app.feature.news

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
import ru.dkd.app.core.common.string
import ru.dkd.app.core.design.*

@Composable
internal fun NewsScreen(token: String, onError: (String) -> Unit) {
    val viewModel: NewsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(token, refresh) { viewModel.refresh(token) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("ПРЕСС-ЦЕНТР ДКД", color = Pink, fontSize = 9.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Text("Новости", color = White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("Официальные сообщения и события государства", color = Muted, fontSize = 12.sp)
        }

        if (state.loading && state.articles.isEmpty()) {
            item {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем новости…", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        } else if (state.error != null && state.articles.isEmpty()) {
            item { NewsEmptyState("Новости недоступны", "Проверьте подключение к серверу и повторите попытку.") }
        } else if (state.articles.isEmpty()) {
            item { NewsEmptyState("Публикаций пока нет", "Официальные сообщения появятся здесь после публикации пресс-центром ДКД.") }
        }

        items(state.articles, key = { it.optInt("id") }) { article ->
            NewsCard(article) { selected = article }
        }

        item {
            TextButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("↻  Обновить новости", color = Purple)
            }
        }
    }

    selected?.let { article ->
        val category = categoryLabel(article.string("category"))
        val importance = importanceLabel(article.string("importance"))
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Panel,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$importance  ·  $category".uppercase(), color = Pink, fontSize = 9.sp, letterSpacing = 1.sp)
                    Text(article.string("title", "Сообщение пресс-центра"), color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(article.string("text"), color = White, fontSize = 13.sp, lineHeight = 19.sp)
                    Text(article.string("created_at").take(16).replace("T", " "), color = Muted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Закрыть", color = Purple) }
            }
        )
    }
}

@Composable
private fun NewsCard(article: JSONObject, onClick: () -> Unit) {
    val importance = article.string("importance")
    val markerColor = when (importance) {
        "urgent" -> Bad
        "important" -> Pink
        else -> Blue
    }
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(19.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, LineColor, RoundedCornerShape(19.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(markerColor, RoundedCornerShape(20.dp)))
                Spacer(Modifier.width(7.dp))
                Text(importanceLabel(importance).uppercase(), color = markerColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(categoryLabel(article.string("category")), color = Muted, fontSize = 9.sp)
            }
            Text(article.string("title", "Сообщение пресс-центра ДКД"), color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(article.string("text").take(230), color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(article.string("created_at").take(10), color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("ЧИТАТЬ  →", color = Purple, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
        }
    }
}

private fun categoryLabel(value: String): String = when (value) {
    "politics" -> "Политика"
    "elections" -> "Выборы"
    "government" -> "Государство"
    "documents" -> "Документы"
    "events" -> "События"
    else -> "Общество"
}

private fun importanceLabel(value: String): String = when (value) {
    "urgent" -> "Срочно"
    "important" -> "Важно"
    else -> "Новости"
}

@Composable
private fun NewsEmptyState(title: String, description: String) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
