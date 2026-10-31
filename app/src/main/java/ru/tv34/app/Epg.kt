package ru.tv34.app

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Prog(val start: Long, val stop: Long, val title: String)
data class ChEpg(val logo: String?, val progs: List<Prog>)

/** Логотипы и программа передач из url-tvg плейлиста (XMLTV, в том числе .gz). */
object Epg {
    const val URL = "https://iptvx.one/EPG"
    private val noise = setOf("канал", "телеканал", "hd", "fhd", "tv", "тв")

    fun norm(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() && it !in noise }.joinToString("")

    fun load(file: File, channels: List<Channel>): Map<String, ChEpg> {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3 * 3600_000L) {
            runCatching { return read(file) }
        }
        return try { fetch(channels).also { write(file, it) } }
        catch (e: Exception) { if (file.exists()) read(file) else emptyMap() }
    }

    private fun fetch(channels: List<Channel>): Map<String, ChEpg> {
        val keys = HashMap<String, Channel>()
        for (c in channels) listOfNotNull(c.name, c.tvgName).map(::norm).filter { it.isNotEmpty() }
            .forEach { keys.putIfAbsent(it, c) }
        val conn = URL(URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TV34/1.0")
        val raw = BufferedInputStream(conn.inputStream, 65536)
        raw.mark(2); val b0 = raw.read(); val b1 = raw.read(); raw.reset()
        val input = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 65536) else raw

        val withTz = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val noTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(s: String?): Long {
            if (s == null) return 0
            return try { withTz.parse(s.trim())!!.time }
            catch (e: Exception) { try { noTz.parse(s.trim().take(14))!!.time } catch (e2: Exception) { 0 } }
        }

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
                val name = idToName[p.getAttributeValue(null, "channel") ?: ""]
                val start = time(p.getAttributeValue(null, "start"))
                val stop = time(p.getAttributeValue(null, "stop"))
                var title: String? = null
                while (true) {
                    ev = p.next()
                    if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "programme")) break
                    if (ev == XmlPullParser.START_TAG && p.name == "title" && title == null) title = p.nextText()
                }
                if (name != null && title != null && stop > now && start < horizon)
                    progs.getOrPut(name) { mutableListOf() }.add(Prog(start, stop, title.trim()))
            }
            ev = p.next()
        }
        return (logos.keys + progs.keys).associateWith { ChEpg(logos[it], progs[it].orEmpty().sortedBy { x -> x.start }) }
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