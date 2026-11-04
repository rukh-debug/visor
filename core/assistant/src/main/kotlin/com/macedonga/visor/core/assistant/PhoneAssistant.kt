package com.macedonga.visor.core.assistant

/**
 * Requests for the phone's own assistant: the ones that hold the wearer's assistant phrase. The glasses do not say which
 * wake word woke them, but their transcript starts with it ("hey astra open youtube"). Any other words work as the
 * phrase too ("open google assistant").
 */
internal object PhoneAssistant {

    /**
     * What follows [phrase] in [text], "" when nothing does; null when [text] does not hold it. It must come first, after
     * a "Hey Meta" the glasses may have kept; with [anywhere], at any word ("so open google assistant now").
     */
    fun after(phrase: String, text: String, anywhere: Boolean = false): String? {
        val want = words(phrase).takeIf { it.isNotEmpty() } ?: return null
        val got = words(text).takeIf { it.isNotEmpty() } ?: return null
        // "hey" may be dropped or heard as "hi"; the name may be a letter off ("astro" for "astra").
        val forms = if (want.size > 1 && want.first() in GREETINGS) listOf(want, want.drop(1)) else listOf(want)
        val starts = if (anywhere) got.indices.toList() else listOf(0, metaWake(got)).distinct()
        for (s in starts) for (need in forms) {
            if (s + need.size > got.size) continue
            if (need.indices.all { near(need[it], got[s + it]) || (need[it] in GREETINGS && got[s + it] in GREETINGS) }) {
                return got.drop(s + need.size).joinToString(" ")
            }
        }
        return null
    }

    /** How many words of Meta's own wake word [got] starts with: "hey meta" 2, "meta" 1, else 0. */
    private fun metaWake(got: List<String>): Int = when {
        got.size > 1 && got[0] in GREETINGS && got[1] in META -> 2
        got.isNotEmpty() && got[0] in META -> 1
        else -> 0
    }

    /** What to do with [rest]: open an app, search YouTube, or hand it to the assistant. */
    fun command(rest: String): Command {
        YOUTUBE.firstNotNullOfOrNull { it.matchEntire(rest)?.groupValues?.get(1) }?.let { return Command.YouTube(it.trim()) }
        OPEN.matchEntire(rest)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return Command.Open(it) }
        return Command.Assistant
    }

    sealed interface Command {
        data class Open(val app: String) : Command
        data class YouTube(val query: String) : Command
        data object Assistant : Command
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    private fun near(a: String, b: String): Boolean = a == b || (a.length >= 4 && distance(a, b) <= 1)

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private val GREETINGS = setOf("hey", "hi", "ok", "okay", "ehi", "ei")
    private val META = setOf("meta", "mata", "metta", "matta", "meda")
    private val OPEN = Regex("^(?:please |can you |could you )?(?:open|launch|start|run)(?: up)? (?:the |my )?(.+?)(?: app)?(?: please)?$")
    private val YOUTUBE = listOf(
        Regex("^(?:search|look up|find|play|show me)(?: for)? (.+) on youtube$"),
        Regex("^(?:search )?youtube (?:for |search )?(.+)$"),
        Regex("^(?:open|launch) youtube and (?:search(?: for)?|play|find) (.+)$"),
    )
}
