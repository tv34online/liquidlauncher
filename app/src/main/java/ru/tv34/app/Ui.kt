package ru.tv34.phone

import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Orange = Color(0xFFFF6A00)
private val Orange2 = Color(0xFFFF8A2B)
private val Panel = Color(0xF2121212)
private val Chip = Color(0xFF2A2A2A)

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun hm(t: Long) = timeFmt.format(Date(t))
private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun TvApp(st: AppState) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    var isPlaying by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlayerError(error: PlaybackException) { playError = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) playError = false }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    var showControls by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(0) }
    LaunchedEffect(touch, showControls, isPlaying) {
        if (showControls && isPlaying) { delay(4000); showControls = false }
    }

    fun play(ch: Channel) {
        st.current = ch
        playError = false
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.play()
        showList = false
        showControls = true
        touch++
    }

    fun stop() {
        player.stop(); player.clearMediaItems()
        st.current = null; showControls = true
    }

    BackHandler(enabled = showList || st.current != null) {
        if (showList) showList = false else stop()
    }

    val cur = st.current
    val prog = cur?.let { st.epg?.now(it.url, now) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    this.player = player
                }
            }
        )

        // касание по экрану
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                if (cur == null) showList = true else { showControls = !showControls; touch++ }
            }
        )

        if (cur == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Выберите канал !", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                st.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { st.load() }, colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Повторить") }
                }
                if (st.loading) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            DotsButton(Modifier.align(Alignment.TopStart).padding(16.dp)) { showList = true }
        } else {
            if (playError) {
                Text("Нет сигнала", color = Color(0xFFCCCCCC), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            }
            if (showControls && !showList) {
                Controls(
                    st = st, ch = cur, prog = prog, now = now, isPlaying = isPlaying,
                    onDots = { showList = true },
                    onToggle = { if (player.isPlaying) player.pause() else player.play(); touch++ },
                    onPrev = {
                        val i = st.channels.indexOf(cur)
                        if (i > 0) play(st.channels[i - 1])
                    },
                    onLive = { player.seekToDefaultPosition(); player.prepare(); player.play(); touch++ },
                    onClose = { stop() },
                    onGuide = { showGuide = true }
                )
            }
        }

        if (showList) {
            ChannelPanel(st, now, Modifier.align(Alignment.CenterStart), onPick = { play(it) }, onClose = { showList = false })
        }
        if (showGuide && cur != null) GuideDialog(st, cur, now) { showGuide = false }
    }
}

@Composable
private fun DotsButton(modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.MoreHoriz, null, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun Controls(
    st: AppState, ch: Channel, prog: Program?, now: Long, isPlaying: Boolean,
    onDots: () -> Unit, onToggle: () -> Unit, onPrev: () -> Unit,
    onLive: () -> Unit, onClose: () -> Unit, onGuide: () -> Unit
) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x22000000), Color(0xCC000000))))
    ) {
        DotsButton(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp), onDots)

        prog?.rating?.let {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(26.dp).background(Orange))
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp).clip(CircleShape).background(Chip)
        ) { Icon(Icons.Default.Close, null, tint = Color.White) }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (prog != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC111111)).clickable { onGuide() }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Сегодня, ${hm(prog.start)} - ${hm(prog.stop)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("\"${prog.title}\"", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp))
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC111111)).clickable { onGuide() }
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) { Text("Телепрограмма", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(6.dp))
            Text(st.displayName(ch), color = Color(0xFF9A9A9A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.Center).size(84.dp)) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
            if (prog != null) {
                Text("-" + mmss(prog.stop - now), color = Color(0xFFBBBBBB), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End))
                Spacer(Modifier.height(6.dp))
                val f = ((now - prog.start).toFloat() / (prog.stop - prog.start).toFloat()).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF333333))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Brush.horizontalGradient(listOf(Orange2, Orange))))
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onPrev, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Orange2, Orange)))
                        .clickable { onLive() }.padding(horizontal = 26.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(10.dp))
                    Text("Сейчас в эфире", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChannelLogo(st: AppState, ch: Channel, size: Int = 44) {
    val letter = st.displayName(ch).firstOrNull()?.uppercase() ?: "?"
    val icon = st.epg?.icons?.get(ch.url)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            Text(letter, color = Orange, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (icon != null) {
            SubcomposeAsyncImage(
                model = icon, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } }
            )
        } else fallback()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelPanel(st: AppState, now: Long, modifier: Modifier, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val FAV = "★ Избранное"
    val ALL = "Все"
    var cat by remember { mutableStateOf(ALL) }
    val groups = remember(st.channels) { st.channels.map { it.group }.distinct() }
    val cats = listOf(ALL, FAV) + groups
    val shown = when (cat) {
        ALL -> st.channels
        FAV -> st.channels.filter { it.url in st.favorites }
        else -> st.channels.filter { it.group == cat }
    }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var renameFor by remember { mutableStateOf<Channel?>(null) }

    Row(modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().background(Panel).statusBarsPadding().padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ТВ 34", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (st.epgLoading) Text("Телепрограмма ${st.epgProgress}%", color = Color(0xFF888888), fontSize = 12.sp)
            }
            // категории над каналами
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { c ->
                    val sel = c == cat
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) Orange else Chip)
                            .clickable { cat = c }.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(c, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (cat == FAV) "Долго нажмите на канал, чтобы добавить в избранное" else "Нет каналов",
                        color = Color(0xFF888888), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.url }) { ch ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { onPick(ch) }, onLongClick = { menuFor = ch })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChannelLogo(st, ch)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(st.displayName(ch), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                st.epg?.now(ch.url, now)?.let {
                                    Text(it.title, color = Color(0xFF9A9A9A), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (ch.url in st.favorites) Icon(Icons.Default.Star, null, tint = Orange, modifier = Modifier.size(18.dp))
                            if (st.current == ch) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Orange))
                            }
                        }
                        DropdownMenu(expanded = menuFor == ch, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(if (ch.url in st.favorites) "Убрать из избранного" else "Добавить в избранное") },
                                onClick = { st.toggleFavorite(ch); menuFor = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Изменить имя канала") },
                                onClick = { renameFor = ch; menuFor = null }
                            )
                        }
                    }
                }
            }
        }
        // тап справа от панели закрывает её
        Box(Modifier.weight(1f).fillMaxHeight().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() })
    }

    renameFor?.let { ch ->
        var text by remember(ch) { mutableStateOf(st.displayName(ch)) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Имя канала") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { st.rename(ch, text); renameFor = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun GuideDialog(st: AppState, ch: Channel, now: Long, onDismiss: () -> Unit) {
    val list = st.epg?.upcoming(ch.url, now).orEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF161616)).padding(16.dp).heightIn(max = 320.dp)) {
            Text(st.displayName(ch), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text(if (st.epgLoading) "Телепрограмма загружается… ${st.epgProgress}%" else "Телепрограммы для этого канала нет",
                    color = Color(0xFF999999), fontSize = 14.sp)
            } else LazyColumn {
                items(list) { p ->
                    val on = now >= p.start && now < p.stop
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(hm(p.start), color = if (on) Orange else Color(0xFF888888), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                        Text(p.title, color = if (on) Color.White else Color(0xFFCCCCCC), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}