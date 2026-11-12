package ru.tv34.phone

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

const val PLAYLIST_URL = "https://tv34online.gitverse.site/playlist/playlist.m3u"
const val DEFAULT_EPG = "https://iptvx.one/EPG"

data class Channel(val name: String, val group: String, val url: String, val tvgId: String?)
data class Program(val start: Long, val stop: Long, val title: String, val rating: String?)
data class PlaylistResult(val channels: List<Channel>, val epgUrl: String?)

class Epg(
    val programs: Map<String, List<Program>>,
    val icons: Map<String, String>
) {
    fun now(url: String, t: Long): Program? = programs[url]?.firstOrNull { t >= it.start && t < it.stop }
    fun upcoming(url: String, t: Long): List<Program> = programs[url]?.filter { it.stop > t }.orEmpty()
}

fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

fun parseM3u(text: String): PlaylistResult {
    var epg: String? = null
    val out = mutableListOf<Channel>()
    var info: String? = null
    var extGroup = ""
    for (raw in text.lineSequence()) {
        val l = raw.trim()
        when {
            l.startsWith("#EXTM3U") -> epg = attrRe.findAll(l)
                .firstOrNull { it.groupValues[1] == "url-tvg" || it.groupValues[1] == "x-tvg-url" }
                ?.groupValues?.get(2)?.split(",")?.firstOrNull()?.trim()
            l.startsWith("#EXTINF") -> info = l
            l.startsWith("#EXTGRP:") -> extGroup = l.removePrefix("#EXTGRP:").trim()
            l.isNotEmpty() && !l.startsWith("#") && info != null -> {
                val attrs = attrRe.findAll(info).associate { it.groupValues[1] to it.groupValues[2] }
                val name = info.substringAfterLast(",").trim().ifEmpty { attrs["tvg-name"] ?: "Канал" }
                val group = (attrs["group-title"] ?: extGroup).ifBlank { "Без категории" }
                out += Channel(name, group, l, attrs["tvg-id"]?.takeIf { it.isNotBlank() })
                info = null
            }
        }
    }
    return PlaylistResult(out, epg)
}

private class Counting(inp: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(inp) {
    var n = 0L
    override fun read(): Int { val r = super.read(); if (r >= 0) { n++; onBytes(n) }; return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len); if (r > 0) { n += r; onBytes(n) }; return r
    }
}

fun httpGet(url: String): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 30000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "TV34/1.0")
    return c
}

fun loadPlaylist(): PlaylistResult {
    val c = httpGet(PLAYLIST_URL)
    return c.inputStream.use { parseM3u(it.readBytes().toString(Charsets.UTF_8)) }
}

private fun parseTime(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
        if (s.trim().contains(' ')) {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(s.trim())?.time
        } else {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s.trim().take(14))?.time
        }
    } catch (e: Exception) { null }
}

/** Скачивает XMLTV (в т.ч. .gz), оставляет только программы каналов из плейлиста. */
fun loadEpg(channels: List<Channel>, epgUrl: String, onProgress: (Int) -> Unit): Epg {
    val byId = HashMap<String, MutableList<Channel>>()
    val byName = HashMap<String, MutableList<Channel>>()
    for (ch in channels) {
        ch.tvgId?.let { byId.getOrPut(it) { mutableListOf() }.add(ch) }
        byName.getOrPut(norm(ch.name)) { mutableListOf() }.add(ch)
    }
    val conn = httpGet(epgUrl)
    val total = conn.contentLengthLong
    val counting = Counting(conn.inputStream) { n ->
        if (total > 0) onProgress(((n * 100) / total).toInt().coerceAtMost(99))
    }
    val buffered = BufferedInputStream(counting, 64 * 1024)
    buffered.mark(2)
    val b0 = buffered.read(); val b1 = buffered.read()
    buffered.reset()
    val stream: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

    val progs = HashMap<String, MutableList<Program>>()
    val icons = HashMap<String, String>()
    val epgToChannels = HashMap<String, List<Channel>>()
    val minStop = System.currentTimeMillis() - 2 * 3600_000L
    val maxStart = System.currentTimeMillis() + 36 * 3600_000L

    stream.use { s ->
        val p = Xml.newPullParser()
        p.setInput(s, null)
        var ev = p.eventType
        var curChId: String? = null
        var curChNames = mutableListOf<String>()
        var curIcon: String? = null
        var pStart = 0L; var pStop = 0L; var pTitle = ""; var pRating: String? = null
        var pChannels: List<Channel>? = null
        var inRating = false; var inTitle = false; var inDisplay = false; var inValue = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> { curChId = p.getAttributeValue(null, "id"); curChNames = mutableListOf(); curIcon = null }
                    "display-name" -> inDisplay = true
                    "icon" -> if (curChId != null && pChannels == null) curIcon = p.getAttributeValue(null, "src")
                    "programme" -> {
                        pChannels = epgToChannels[p.getAttributeValue(null, "channel")]
                        pStart = parseTime(p.getAttributeValue(null, "start")) ?: 0L
                        pStop = parseTime(p.getAttributeValue(null, "stop")) ?: 0L
                        pTitle = ""; pRating = null
                    }
                    "title" -> inTitle = true
                    "rating" -> inRating = true
                    "value" -> inValue = true
                }
                XmlPullParser.TEXT -> {
                    val t = p.text
                    if (inDisplay) curChNames.add(t.trim())
                    else if (inTitle && pChannels != null) pTitle += t
                    else if (inRating && inValue) pRating = t.trim()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "display-name" -> inDisplay = false
                    "title" -> inTitle = false
                    "value" -> inValue = false
                    "rating" -> inRating = false
                    "channel" -> {
                        val id = curChId
                        if (id != null) {
                            val matched = LinkedHashSet<Channel>()
                            byId[id]?.let { matched.addAll(it) }
                            for (n in curChNames) byName[norm(n)]?.let { matched.addAll(it) }
                            if (matched.isNotEmpty()) {
                                epgToChannels[id] = matched.toList()
                                curIcon?.let { ic -> matched.forEach { icons[it.url] = ic } }
                            }
                        }
                        curChId = null
                    }
                    "programme" -> {
                        val list = pChannels
                        if (list != null && pStop > minStop && pStart < maxStart && pTitle.isNotBlank()) {
                            val pr = Program(pStart, pStop, pTitle.trim(), pRating)
                            list.forEach { progs.getOrPut(it.url) { mutableListOf() }.add(pr) }
                        }
                        pChannels = null
                    }
                }
            }
            ev = p.next()
        }
    }
    onProgress(100)
    progs.values.forEach { it.sortBy { pr -> pr.start } }
    return Epg(progs, icons)
}