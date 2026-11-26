package io.agedm.tv.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HiddenLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("hidden_library", Context.MODE_PRIVATE)

    var revision: Long = 0
        private set

    fun contains(id: Long) = prefs.contains(id.toString())

    fun hide(cards: List<AnimeCard>) {
        val edit = prefs.edit()
        for (card in cards) edit.putString(card.animeId.toString(), Json.encodeToString(
            AnimeCard(card.animeId, card.title, card.cover)))
        edit.apply()
        revision++
    }

    fun restore(id: Long) {
        prefs.edit().remove(id.toString()).apply()
        revision++
    }

    fun cards(): List<AnimeCard> = prefs.all.values.filterIsInstance<String>().mapNotNull {
        runCatching { Json.decodeFromString<AnimeCard>(it) }.getOrNull()
    }.sortedBy { it.title }
}
