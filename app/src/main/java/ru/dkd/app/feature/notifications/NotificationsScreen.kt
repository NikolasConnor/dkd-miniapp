package ru.dkd.app.feature.notifications

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.dkd.app.core.common.array
import ru.dkd.app.core.common.bool
import ru.dkd.app.core.common.int
import ru.dkd.app.core.common.string
import ru.dkd.app.core.network.apiCall
import ru.dkd.app.core.design.*

@Composable
internal fun NotificationsScreen(
    token: String, onBack: () -> Unit,
    onUnreadChanged: (Int) -> Unit, onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        val r = apiCall(token, "GET", "/api/notifications")
        if (r.error != null) onError(r.error)
        notes = r.data?.array("notifications") ?: emptyList()
        onUnreadChanged(r.data?.optInt("unread_count", 0) ?: 0)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("←  Назад", color = Blue) }
            DkdPageHeader("ЦЕНТР СОБЫТИЙ", "Уведомления", "Персональные сообщения и важные события ДКД", "♧")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                scope.launch {
                    val r = apiCall(token, "POST", "/api/notifications/read-all")
                    if (r.error != null) onError(r.error) else refresh++
                }
            }) { Text("Прочитать всё", color = Blue, fontSize = 11.sp) }
            }
        }
        if (notes.isEmpty()) EmptyState("Уведомлений нет", "Здесь появятся важные события и сообщения.")
        else androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes, key = { it.int("id") }) { item ->
                Surface(color = if (item.bool("is_read")) Panel else Color(0xFF25234D),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (item.bool("is_read")) LineColor else Purple, RoundedCornerShape(16.dp))
                        .clickable {
                        scope.launch {
                            val r = apiCall(token, "POST", "/api/notifications/read", JSONObject().put("notification_id", item.int("id")))
                            if (r.error != null) onError(r.error) else refresh++
                        }
                    }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.string("title", "Уведомление"), color = White, fontWeight = FontWeight.SemiBold)
                        Text(item.string("message"), color = Muted, fontSize = 12.sp)
                        Text(item.string("created_at").take(16).replace("T", " "), color = Blue, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("◌", color = Blue, fontSize = 34.sp)
        Text(title, color = White, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, fontSize = 12.sp)
    }
}
