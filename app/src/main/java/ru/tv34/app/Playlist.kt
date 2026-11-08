package ru.tv34.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val num: Int, val name: String, val group: String, val url: String, val tvgName: String? = null, val logo: String? = null, val epgKey: String = name)

object Playlist {
    const val URL = "https://gitverse.ru/api/repos/tv34online/tv34online/raw/branch/master/playlist.m3u"
    const val RESERVE = "https://chekvidzetelefap.github.io/tv34app/playlist_rezerv.m3u"
    private val groupRe = Regex("group-title=\"([^\"]*)\"")
    private val tvgRe = Regex("tvg-name=\"([^\"]*)\"")
    private val logoRe = Regex("tvg-logo=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var group = "Каналы"
        var tvg: String? = null
        var logo: String? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",").trim()
                group = groupRe.find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "Каналы"
                tvg = tvgRe.find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                logo = logoRe.find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            } else if (l.isNotEmpty() && !l.startsWith("#") && name != null) {
                out += Channel(out.size + 1, name, group, l, tvg, logo)
                name = null
            }
        }
        return out
    }

    private fun fetch(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "TV34/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Основной источник, потом резервный. Берём первый, где есть хотя бы один канал. */
    private fun download(): String {
        var last: Exception? = null
        for (address in listOf(URL, RESERVE)) {
            try {
                val text = fetch(address)
                if (parse(text).isNotEmpty()) return text
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Плейлист пуст")
    }

    /** Сеть, а при ошибке — последняя сохранённая копия. */
    fun load(cache: File): List<Channel> {
        val text = try { download().also { cache.writeText(it) } }
        catch (e: Exception) { if (cache.exists()) cache.readText() else throw e }
        return parse(text).also { check(it.isNotEmpty()) }
    }
}