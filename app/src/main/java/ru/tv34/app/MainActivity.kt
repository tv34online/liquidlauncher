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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Палитра взята с иконки и баннера
val Ink = Color(0xFF121212)
val Orange = Color(0xFFFF6A00)
val Paper = Color(0xFFF4F4F4)
val Muted = Color(0xFF8C8C8C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(filesDir) }
    }
}

/** Логотипы и программа передач на текущий момент. */
class Guide(private val map: Map<String, ChEpg>, val now: Long) {
    fun logo(ch: Channel): String? = map[ch.name]?.logo ?: ch.logo
    fun current(ch: Channel): Prog? = map[ch.name]?.progs?.firstOrNull { now >= it.start && now < it.stop }
    fun next(ch: Channel): Prog? = current(ch)?.let { c -> map[ch.name]?.progs?.firstOrNull { it.start >= c.stop } }
}

fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun App(dir: File) {
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var epg by remember { mutableStateOf<Map<String, ChEpg>>(emptyMap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var epgPct by remember { mutableIntStateOf(-2) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Playlist.load(File(dir, "playlist.m3u")) } }
            .onSuccess { channels = it }.onFailure { failed = true }
    }
    LaunchedEffect(channels) {
        val list = channels ?: return@LaunchedEffect
        epg = withContext(Dispatchers.IO) {
            runCatching { Epg.load(File(dir, "epg.json"), list) { epgPct = it } }.getOrDefault(emptyMap())
        }
        if (epgPct > -2) { epgPct = 100; delay(1500) }
        epgPct = -2
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val guide = Guide(epg, now)
    Box(Modifier.fillMaxSize().background(Ink)) {
        val list = channels
        val cur = playing
        when {
            list == null -> Text(
                if (failed) "Не удалось загрузить каналы. Проверьте интернет и откройте приложение снова." else "Загружаем каналы",
                Modifier.align(Alignment.Center).padding(32.dp), color = Muted, fontSize = 20.sp
            )
            cur == null -> Browse(list, guide) { playing = it }
            else -> PlayerScreen(list, cur, guide, { playing = it }, { playing = null })
        }
        if (playing == null && epgPct > -2) Text(
            if (epgPct < 0) "Телепрограмма…" else "Телепрограмма $epgPct%",
            Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(8.dp))
                .background(Ink.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Muted, fontSize = 14.sp
        )
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
fun Wordmark(size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Телевизор", color = Paper, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape((size.value * 0.3f).dp)).background(Orange)
                .padding(horizontal = (size.value * 0.3f).dp, vertical = (size.value * 0.06f).dp)
        ) { Text("34", color = Ink, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp) }
    }
}

const val LOGO_URL = "https://gitverse.ru/api/repos/tv34online/tv34online/raw/branch/master/banner_tv.png"

/** Логотип с GitVerse; если не загрузился — текстовая надпись. */
@Composable
fun BrandLogo(height: Dp, fallback: TextUnit) {
    var failed by remember { mutableStateOf(false) }
    if (failed) Wordmark(fallback)
    else AsyncImage(
        LOGO_URL, "Телевизор 34", Modifier.fillMaxWidth().height(height),
        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, onError = { failed = true }
    )
}

@Composable
fun NumBadge(num: Int, inverted: Boolean, size: Dp = 48.dp) {
    Box(
        Modifier.defaultMinSize(minWidth = size).height(size).clip(RoundedCornerShape(10.dp))
            .background(if (inverted) Ink else Orange).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("$num", color = if (inverted) Orange else Ink, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black)
    }
}

/** Логотип канала из EPG; если его нет или он не загрузился — номер канала. */
@Composable
fun Logo(ch: Channel, guide: Guide, size: Dp, inverted: Boolean) {
    val url = guide.logo(ch)
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) NumBadge(ch.num, inverted, size)
    else Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color.Black).padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
    }
}

@Composable
fun ChannelList(items: List<Channel>, guide: Guide, onPick: (Channel) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.num }) { ch ->
            Pressable(Modifier.fillMaxWidth(), { onPick(ch) }) { f ->
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(ch, guide, 48.dp, f)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(ch.name, color = if (f) Ink else Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        guide.current(ch)?.let {
                            Text(it.title, color = if (f) Ink.copy(alpha = 0.7f) else Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
fun Browse(all: List<Channel>, guide: Guide, onPick: (Channel) -> Unit) {
    val groups = remember(all) { listOf("Все") + all.map { it.group }.distinct() }
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val shown = if (sel == 0) all else all.filter { it.group == groups[sel] }
    if (LocalConfiguration.current.screenWidthDp >= 720) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(300.dp).fillMaxHeight().padding(24.dp)) {
                BrandLogo(110.dp, 28.sp)
                Spacer(Modifier.height(20.dp))
                LazyColumn { itemsIndexed(groups) { i, g -> GroupItem(g, i == sel) { sel = i } } }
            }
            ChannelList(shown, guide, onPick, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)) { BrandLogo(64.dp, 26.sp) }
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                itemsIndexed(groups) { i, g -> GroupItem(g, i == sel) { sel = i } }
            }
            ChannelList(shown, guide, onPick, Modifier.weight(1f))
        }
    }
}

@Composable
fun PlayerScreen(all: List<Channel>, ch: Channel, guide: Guide, onSwitch: (Channel) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(ch) { player.setMediaItem(MediaItem.fromUri(ch.url)); player.prepare() }
    var show by remember { mutableStateOf(true) }
    var tap by remember { mutableIntStateOf(0) }
    LaunchedEffect(ch, tap) { show = true; delay(4000); show = false }
    BackHandler(onBack = onExit)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures { tap++ } }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val i = all.indexOf(ch)
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { onSwitch(all[(i + 1) % all.size]); true }
                    Key.DirectionDown, Key.ChannelDown -> { onSwitch(all[(i - 1 + all.size) % all.size]); true }
                    Key.DirectionCenter, Key.Enter -> { tap++; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize()
        )
        AnimatedVisibility(show, Modifier.align(Alignment.BottomStart)) {
            val cur = guide.current(ch)
            val next = guide.next(ch)
            Column(
                Modifier.padding(32.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp))
                    .background(Ink.copy(alpha = 0.92f)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(ch, guide, 64.dp, false)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(ch.name, color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            cur?.title ?: "Идёт эфир", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (cur != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hm(cur.start), color = Muted, fontSize = 15.sp)
                        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(4.dp).background(Muted.copy(alpha = 0.35f))) {
                            val frac = ((guide.now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Orange))
                        }
                        Text(hm(cur.stop), color = Muted, fontSize = 15.sp)
                    }
                }
                if (next != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Далее в ${hm(next.start)}: ${next.title}", color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}