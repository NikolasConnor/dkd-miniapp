package ru.dkd.app.feature.news

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            DkdPageHeader("ПРЕСС-ЦЕНТР ДКД", "Новости", "Официальные сообщения и события государства", "◉")
        }

        if (state.fromCache) {
            item {
                Surface(color = Color(0xFF3A304A), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ОФЛАЙН-КОПИЯ", color = Pink, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Нет связи с сервером. Показаны ранее загруженные публикации; новые сообщения могут отсутствовать.",
                            color = White, fontSize = 10.sp, lineHeight = 15.sp)
                        state.cachedAtEpochMs?.let { timestamp ->
                            Text("Сохранено: " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp)),
                                color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
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
            NewsCard(article, token, mediaEnabled = !state.fromCache) { selected = article }
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

@OptIn(UnstableApi::class)
@Composable
private fun NewsVideoPlayer(url: String, token: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(url, token) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = false
            }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> Unit
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
    }
}

@Composable
private fun NewsCard(article: JSONObject, token: String, mediaEnabled: Boolean, onClick: () -> Unit) {
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
            val mediaType = article.string("media_type")
            if (mediaType == "photo" && !mediaEnabled) {
                Surface(color = Panel2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Изображение будет доступно после восстановления соединения.", color = Muted,
                        fontSize = 10.sp, modifier = Modifier.padding(12.dp))
                }
            } else if (mediaType == "photo") {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(ru.dkd.app.core.network.API_BASE_URL + "/api/news/" + article.optInt("id") + "/media")
                        .addHeader("Authorization", "Bearer " + token)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Изображение к новости",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    contentScale = ContentScale.Crop
                )
            } else if (mediaType == "video") {
                if (mediaEnabled) {
                    NewsVideoPlayer(
                        url = ru.dkd.app.core.network.API_BASE_URL + "/api/news/" + article.optInt("id") + "/media",
                        token = token
                    )
                } else {
                    Surface(color = Panel2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Видео будет доступно после восстановления соединения.", color = Muted,
                            fontSize = 10.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
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
