package ru.tv34.app

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class Prog(val start: Long, val stop: Long, val title: String)
data class ChEpg(val logo: String?, val progs: List<Prog>)

/** Программа передач из url-tvg плейлиста (XMLTV, в том числе .gz). */
object Epg {
    const val URL = "https://iptvx.one/EPG"
    private const val FRESH_MS = 2 * 3600_000L
    private val noise = setOf("канал", "телеканал", "hd", "fhd", "tv", "тв")

    fun norm(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() && it !in noise }.joinToString("")

    /**
     * Сначала сразу отдаёт сохранённую программу (onCached), потом, если она старше двух часов,
     * обновляет её из сети. Если сервер сообщает, что файл не менялся, тяжёлая загрузка пропускается.
     */
    fun load(
        file: File, channels: List<Channel>,
        onProgress: (Int) -> Unit = {}, onCached: (Map<String, ChEpg>) -> Unit = {}
    ): Map<String, ChEpg> {
        val cached = if (file.exists()) runCatching { read(file) }.getOrNull() else null
        if (cached != null) onCached(cached)
        if (cached != null && System.currentTimeMillis() - file.lastModified() < FRESH_MS) return cached
        return try {
            val fresh = fetch(channels, onProgress, if (cached != null) file.lastModified() else 0L)
            if (fresh == null) { file.setLastModified(System.currentTimeMillis()); cached ?: emptyMap() }
            else fresh.also { write(file, it) }
        } catch (e: Exception) { cached ?: emptyMap() }
    }

    /** null означает «не изменилось с прошлой загрузки». */
    private fun fetch(channels: List<Channel>, onProgress: (Int) -> Unit, since: Long): Map<String, ChEpg>? {
        val keys = HashMap<String, Channel>()
        for (c in channels) listOfNotNull(c.name, c.tvgName).map(::norm).filter { it.isNotEmpty() }
            .forEach { keys.putIfAbsent(it, c) }
        val conn = URL(URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TV34/1.0")
        if (since > 0) conn.ifModifiedSince = since
        if (conn.responseCode == 304) return null
        val total = conn.contentLengthLong
        var done = 0L
        var last = -1
        onProgress(if (total > 0) 0 else -1)
        val raw = BufferedInputStream(CountingStream(conn.inputStream) { n ->
            done += n
            if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceAtMost(99)
                if (pct != last) { last = pct; onProgress(pct) }
            }
        }, 128 * 1024)
        raw.mark(2); val b0 = raw.read(); val b1 = raw.read(); raw.reset()
        val input = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 128 * 1024) else raw

        val idToName = HashMap<String, String>()
        val logos = HashMap<String, String>()
        val claimed = HashSet<String>()
        val progs = HashMap<String, MutableList<Prog>>()
        val now = System.currentTimeMillis()
        val horizon = now + 12 * 3600_000L

        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "channel") {
                val id = p.getAttributeValue(null, "id") ?: ""
                var matched: Channel? = null
                var icon: String? = null
                while (true) {
                    ev = p.next()
                    if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "channel")) break
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "display-name") { val c = keys[norm(p.nextText())]; if (matched == null) matched = c }
                        else if (p.name == "icon") icon = p.getAttributeValue(null, "src")
                    }
                }
                if (matched != null && claimed.add(matched.name)) {
                    idToName[id] = matched.name
                    if (icon != null) logos[matched.name] = icon
                }
            } else if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                // Чужие каналы и передачи вне окна пропускаем без разбора времени и заголовка.
                val name = idToName[p.getAttributeValue(null, "channel") ?: ""]
                var start = 0L; var stop = 0L
                if (name != null) { start = xmltvTime(p.getAttributeValue(null, "start")); stop = xmltvTime(p.getAttributeValue(null, "stop")) }
                val wanted = name != null && stop > now && start < horizon
                var title: String? = null
                while (true) {
                    ev = p.next()
                    if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "programme")) break
                    if (wanted && ev == XmlPullParser.START_TAG && p.name == "title" && title == null) title = p.nextText()
                }
                if (wanted && title != null) progs.getOrPut(name!!) { mutableListOf() }.add(Prog(start, stop, title.trim()))
            }
            ev = p.next()
        }
        return (logos.keys + progs.keys).associateWith { ChEpg(logos[it], progs[it].orEmpty().sortedBy { x -> x.start }) }
    }

    /** "20261104120000 +0300" -> миллисекунды; без часового пояса считается UTC. */
    private fun xmltvTime(s: String?): Long {
        if (s == null || s.length < 14) return 0
        return try {
            val y = s.substring(0, 4).toInt(); val mo = s.substring(4, 6).toInt(); val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt(); val mi = s.substring(10, 12).toInt(); val se = s.substring(12, 14).toInt()
            var off = 0
            val t = s.substring(14).trim()
            if (t.length >= 5 && (t[0] == '+' || t[0] == '-')) {
                off = (t.substring(1, 3).toInt() * 60 + t.substring(3, 5).toInt()) * 60
                if (t[0] == '-') off = -off
            }
            val yy = if (mo <= 2) y - 1 else y
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = era * 146097L + doe - 719468
            (days * 86400 + h * 3600L + mi * 60L + se - off) * 1000
        } catch (e: Exception) { 0 }
    }

    private fun write(file: File, data: Map<String, ChEpg>) {
        val o = JSONObject()
        for ((k, v) in data) o.put(k, JSONObject().put("l", v.logo ?: "").put("p", JSONArray().also { a ->
            v.progs.forEach { a.put(JSONArray().put(it.start).put(it.stop).put(it.title)) }
        }))
        file.writeText(o.toString())
    }

    private fun read(file: File): Map<String, ChEpg> {
        val o = JSONObject(file.readText())
        return o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            val a = v.getJSONArray("p")
            ChEpg(v.getString("l").ifEmpty { null }, (0 until a.length()).map {
                val r = a.getJSONArray(it); Prog(r.getLong(0), r.getLong(1), r.getString(2))
            })
        }
    }
}

private class CountingStream(input: InputStream, val onRead: (Int) -> Unit) : FilterInputStream(input) {
    override fun read(): Int { val b = super.read(); if (b >= 0) onRead(1); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) onRead(n); return n }
}