package ru.tv34.phone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppState(ctx: Context, private val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("tv34", Context.MODE_PRIVATE)

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var epg by mutableStateOf<Epg?>(null)
    var epgProgress by mutableStateOf(0)
    var epgLoading by mutableStateOf(false)
    var current by mutableStateOf<Channel?>(null)
    var favorites by mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet())
    val customNames = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) -> if (k.startsWith("name_") && v is String) put(k.removePrefix("name_"), v) }
    }

    fun displayName(ch: Channel) = customNames[ch.url] ?: ch.name

    fun toggleFavorite(ch: Channel) {
        favorites = if (ch.url in favorites) favorites - ch.url else favorites + ch.url
        prefs.edit().putStringSet("fav", favorites).apply()
    }

    fun rename(ch: Channel, name: String) {
        if (name.isBlank() || name == ch.name) {
            customNames.remove(ch.url); prefs.edit().remove("name_" + ch.url).apply()
        } else {
            customNames[ch.url] = name.trim(); prefs.edit().putString("name_" + ch.url, name.trim()).apply()
        }
    }

    fun load() {
        loading = true; error = null
        scope.launch(Dispatchers.IO) {
            try {
                val r = loadPlaylist()
                channels = r.channels
                loading = false
                loadEpgAsync(r)
            } catch (e: Exception) {
                error = "Не удалось загрузить плейлист"
                loading = false
            }
        }
    }

    private fun loadEpgAsync(r: PlaylistResult) {
        epgLoading = true; epgProgress = 0
        scope.launch(Dispatchers.IO) {
            try {
                epg = loadEpg(r.channels, r.epgUrl ?: DEFAULT_EPG) { epgProgress = it }
            } catch (_: Exception) {
            } finally {
                epgLoading = false
            }
        }
    }
}