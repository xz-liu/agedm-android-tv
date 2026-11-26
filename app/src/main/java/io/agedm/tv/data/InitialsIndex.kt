package io.agedm.tv.data

import android.icu.text.AlphabeticIndex
import java.text.Normalizer
import java.util.Locale

/** Use Android's Chinese dictionary ordering so traditional and simplified Han share initials. */
class InitialsIndex(titles: List<AgeBangumiLookupMetadata>, cover: (Long) -> String) {
    private companion object { val LATIN_WORD = Regex("[A-Za-z0-9]+") }

    private data class Entry(val card: AnimeCard, val keys: Set<String>)
    private val entries: List<Entry>

    init {
        val alphabet = AlphabeticIndex<Unit>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH).buildImmutableIndex()
        val letters = mutableMapOf<Char, String>()
        fun key(title: String): String = buildString {
            for (ch in Normalizer.normalize(title, Normalizer.Form.NFKC).uppercase(Locale.ROOT)) {
                when {
                    ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> append(letters.getOrPut(ch) {
                        val bucket = alphabet.getBucket(alphabet.getBucketIndex(ch.toString()))
                        bucket?.label.orEmpty().filter { it in 'A'..'Z' }
                    })
                }
            }
        }
        entries = titles.filter { it.animeId > 0 && it.title.isNotBlank() }.map { item ->
            val names = listOf(item.title, item.originalTitle) + item.otherTitles
            val keys = buildSet {
                for (name in names) {
                    add(key(name))
                    val words = LATIN_WORD.findAll(name).toList()
                    if (words.size > 1) add(words.joinToString("") { it.value.first().uppercaseChar().toString() })
                }
                remove("")
            }
            Entry(AnimeCard(item.animeId, item.title, cover(item.animeId)), keys)
        }
    }

    fun search(query: String): List<AnimeCard> {
        if (query.isBlank()) return emptyList()
        val text = query.uppercase(Locale.ROOT)
        // Prefix and exact matches precede matches in the middle of a title.
        return entries.mapNotNull { entry ->
            val rank = entry.keys.minOfOrNull { key ->
                when {
                    key == text -> 0
                    key.startsWith(text) -> 1
                    text in key -> 2
                    else -> 3
                }
            } ?: 3
            if (rank == 3) null else rank to entry.card
        }.sortedWith(compareBy({ it.first }, { it.second.title })).map { it.second }
    }
}
