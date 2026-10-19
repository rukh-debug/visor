package com.macedonga.visor.settings

import java.text.Normalizer

/**
 * A forgiving match of what was typed against a few weighted fields: every word typed has to land somewhere, as a
 * whole word, the start of one, a piece of one, initials ("dnd"), letters in order ("wkwrd"), or one slip off a word
 * ("brigtness"). The better the landing and the field, the higher the score; nothing landing scores null.
 */
internal class FuzzyDoc(fields: List<Pair<String?, Float>>) {
    private val fields = fields.mapNotNull { (text, weight) -> text?.takeIf { it.isNotBlank() }?.let { Field(normal(it), weight) } }

    fun score(query: FuzzyQuery): Float? {
        if (query.words.isEmpty()) return null
        var total = 0f
        for (alternatives in query.words) {
            val best = fields.maxOfOrNull { f -> alternatives.maxOf { w -> f.weight * f.match(w) } } ?: 0f
            if (best <= 0f) return null
            total += best
        }
        // The whole query as typed, in one field, beats the same words scattered.
        if (query.words.size > 1 && fields.any { query.whole in it.text }) total *= 1.5f
        return total
    }

    private class Field(val text: String, val weight: Float) {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val initials = words.joinToString("") { it.take(1) }

        fun match(w: String): Float = when {
            words.any { it == w } -> 1f
            words.any { it.startsWith(w) } -> 0.85f
            w.length >= 2 && w in text.replace(" ", "") -> 0.65f
            w.length >= 2 && initials.startsWith(w) -> 0.6f
            w.length >= 3 && words.any { slips(w, it) } -> 0.45f
            w.length >= 3 && inOrder(w, text.replace(" ", "")) -> 0.3f
            else -> 0f
        }
    }
}

/** What was typed, cut into words, each with the words that mean the same. */
internal class FuzzyQuery(typed: String) {
    val whole = normal(typed)
    val words: List<List<String>> = whole.split(' ').filter { it.isNotEmpty() }.map { w -> listOf(w) + SYNONYMS[w].orEmpty() }
}

/** Lowercase, accents off, and _ - . / as spaces, so "wakeword_enabled" and "Wake-word" read alike. */
internal fun normal(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(SEPARATORS, " ").replace(SPACES, " ").trim()

/** One letter off, added, dropped or two swapped, for words long enough that it can only mean the one. */
private fun slips(typed: String, word: String): Boolean {
    val allowed = if (typed.length >= 8) 2 else if (typed.length >= 4) 1 else 0
    if (allowed == 0) return false
    // Against the word, or its start when what was typed is a prefix with a slip in it.
    val target = if (word.length > typed.length + allowed) word.take(typed.length) else word
    return kotlin.math.abs(target.length - typed.length) <= allowed && distance(typed, target, allowed) <= allowed
}

/** Damerau-Levenshtein (optimal string alignment), giving up past [limit]. */
private fun distance(a: String, b: String, limit: Int): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        var rowMin = Int.MAX_VALUE
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
            rowMin = minOf(rowMin, v)
        }
        if (rowMin > limit) return limit + 1
    }
    return d[a.length][b.length]
}

/** Every letter of [typed] in [text], in order, close enough together that it reads as meant. */
private fun inOrder(typed: String, text: String): Boolean {
    var at = -1
    var first = -1
    for (c in typed) {
        at = text.indexOf(c, at + 1)
        if (at < 0) return false
        if (first < 0) first = at
    }
    return at - first + 1 <= typed.length * 3
}

private val MARKS = Regex("\\p{M}+")
private val SEPARATORS = Regex("[_\\-./:]+")
private val SPACES = Regex("\\s+")

/** Words people type for what the firmware names otherwise. */
private val SYNONYMS: Map<String, List<String>> = listOf(
    listOf("dnd", "disturb"),
    listOf("bt", "bluetooth"),
    listOf("wifi", "wlan", "wireless"),
    listOf("mic", "microphone", "microfono"),
    listOf("cam", "camera", "fotocamera"),
    listOf("photo", "picture", "foto", "capture"),
    listOf("video", "recording", "record"),
    listOf("notif", "notification", "notifiche"),
    listOf("volume", "audio", "loud", "sound"),
    listOf("brightness", "led", "light", "luminosita"),
    listOf("timeout", "delay", "duration", "idle"),
    listOf("battery", "power", "charge", "batteria"),
    listOf("wake", "wakeword", "hey"),
    listOf("voice", "assistant", "ai"),
    listOf("language", "locale", "lingua"),
    listOf("touch", "tap", "swipe", "gesture"),
).flatMap { group -> group.map { w -> w to (group - w) } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().distinct() }
