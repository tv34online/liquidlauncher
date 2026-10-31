package ru.tv34.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val num: Int, val name: String, val group: String, val url: String)

object Playlist {
    const val URL = "https://gitverse.ru/api/repos/tv34online/tv34online/raw/branch/master/playlist.m3u"
    private val groupRe = Regex("group-title=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var group = "Каналы"
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",").trim()
                group = groupRe.find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "Каналы"
            } else if (l.isNotEmpty() && !l.startsWith("#") && name != null) {
                out += Channel(out.size + 1, name, group, l)
                name = null
            }
        }
        return out
    }

    private fun download(): String {
        val c = URL(URL).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "TV34/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Сеть, а при ошибке — последняя сохранённая копия. */
    fun load(cache: File): List<Channel> {
        val text = try { download().also { cache.writeText(it) } }
        catch (e: Exception) { if (cache.exists()) cache.readText() else throw e }
        return parse(text).also { check(it.isNotEmpty()) }
    }
}