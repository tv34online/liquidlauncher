package ru.tv34.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// Палитра взята с иконки и баннера
val Ink = Color(0xFF121212)
val Orange = Color(0xFFFF6A00)
val Paper = Color(0xFFF4F4F4)
val Muted = Color(0xFF8C8C8C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(File(filesDir, "playlist.m3u")) }
    }
}

@Composable
fun App(cache: File) {
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Playlist.load(cache) } }
            .onSuccess { channels = it }.onFailure { failed = true }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        val list = channels
        val cur = playing
        when {
            list == null -> Text(
                if (failed) "Не удалось загрузить каналы. Проверьте интернет и откройте приложение снова." else "Загружаем каналы",
                Modifier.align(Alignment.Center).padding(32.dp), color = Muted, fontSize = 20.sp
            )
            cur == null -> Browse(list) { playing = it }
            else -> PlayerScreen(list, cur, { playing = it }, { playing = null })
        }
    }
}

@Composable
fun Pressable(modifier: Modifier, onClick: () -> Unit, content: @Composable (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (focused) Orange else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) { content(focused) }
}

@Composable
fun NumBadge(num: Int, inverted: Boolean) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
            .background(if (inverted) Ink else Orange).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("$num", color = if (inverted) Orange else Ink, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
    }
}

@Composable
fun ChannelList(items: List<Channel>, onPick: (Channel) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.num }) { ch ->
            Pressable(Modifier.fillMaxWidth(), { onPick(ch) }) { f ->
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumBadge(ch.num, f)
                    Spacer(Modifier.width(16.dp))
                    Text(ch.name, color = if (f) Ink else Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun GroupItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Pressable(Modifier.padding(2.dp), onClick) { f ->
        Text(
            title, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (f) Ink else if (selected) Orange else Muted, fontSize = 18.sp,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun Browse(all: List<Channel>, onPick: (Channel) -> Unit) {
    val groups = remember(all) { listOf("Все") + all.map { it.group }.distinct() }
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val shown = if (sel == 0) all else all.filter { it.group == groups[sel] }
    val banner = painterResource(R.drawable.banner)
    if (LocalConfiguration.current.screenWidthDp >= 720) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(300.dp).fillMaxHeight().padding(24.dp)) {
                Image(banner, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                Spacer(Modifier.height(16.dp))
                LazyColumn { itemsIndexed(groups) { i, g -> GroupItem(g, i == sel) { sel = i } } }
            }
            ChannelList(shown, onPick, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Image(banner, null, Modifier.height(72.dp).padding(start = 8.dp), contentScale = ContentScale.Fit)
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                itemsIndexed(groups) { i, g -> GroupItem(g, i == sel) { sel = i } }
            }
            ChannelList(shown, onPick, Modifier.weight(1f))
        }
    }
}

@Composable
fun PlayerScreen(all: List<Channel>, ch: Channel, onSwitch: (Channel) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(ch) { player.setMediaItem(MediaItem.fromUri(ch.url)); player.prepare() }
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(ch, show) { if (show) { delay(3000); show = false } }
    BackHandler(onBack = onExit)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures { show = true } }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val i = all.indexOf(ch)
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { onSwitch(all[(i + 1) % all.size]); true }
                    Key.DirectionDown, Key.ChannelDown -> { onSwitch(all[(i - 1 + all.size) % all.size]); true }
                    Key.DirectionCenter, Key.Enter -> { show = true; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(show, Modifier.align(Alignment.BottomStart)) {
            Row(
                Modifier.padding(32.dp).clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha = 0.88f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumBadge(ch.num, false)
                Spacer(Modifier.width(16.dp))
                Text(ch.name, color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}