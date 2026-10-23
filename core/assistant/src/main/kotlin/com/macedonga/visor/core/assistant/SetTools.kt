package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's own tools for the wearer's saved sets, each on only when the wearer turns it on: list_config_sets tells
 * what each set is, switch_config_set puts one in use from the next question. The sets and their notes are read each turn,
 * so a set added or renamed a moment ago is already there.
 */
internal class SetTools(private val book: SetBook, private val config: () -> AssistantConfig) {

    /** What the model is told of the sets beside its tools; empty while both are off. */
    fun prompt(): String {
        val c = config()
        if (!c.listSetsTool && !c.useSetTool) return ""
        return " The wearer keeps several assistant setups, called config sets; you are running on \"${c.modelSet}\"." +
            (if (c.useSetTool) " When they ask for another model or setup (smarter, faster, cheaper, local, offline, one by name), " +
                "switch with switch_config_set; never switch on your own." else "")
    }

    /** Name, description and input schema of each tool that is on. */
    fun tools(): List<Triple<String, String, JSONObject>> {
        val c = config()
        val sets = book.sets.value
        return buildList {
            if (c.listSetsTool) add(Triple(LIST, LIST_HELP, JSONObject().put("type", "object").put("properties", JSONObject())))
            if (c.useSetTool && sets.size > 1) {
                add(
                    Triple(
                        USE, USE_HELP + " The sets: " + sets.joinToString(" ") { "[${it.name}] ${it.short()}" },
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject().put(
                                "name",
                                JSONObject().put("type", "string").put("enum", JSONArray(sets.map { it.name }))
                                    .put("description", "The set's exact name, as listed."),
                            ),
                        ).put("required", JSONArray().put("name")),
                    ),
                )
            }
        }
    }

    /** Runs [name] when it is one of these tools and it is on; null for another tool. */
    fun run(name: String, input: JSONObject): Pair<ToolResult, ToolAction?>? {
        val c = config()
        return when {
            name == LIST && c.listSetsTool -> ToolResult(book.sets.value.joinToString("\n") { it.full(it.name == c.modelSet) }, false) to null
            name == USE && c.useSetTool -> {
                val wanted = input.optString("name").trim()
                val set = book.sets.value.firstOrNull { it.name == wanted } ?: book.sets.value.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                when {
                    set == null -> ToolResult("No set is called \"$wanted\"; the sets are " + book.sets.value.joinToString { "\"${it.name}\"" } + ".", true) to null
                    set.name == c.modelSet -> ToolResult("\"${set.name}\" is already in use; nothing changed.", false) to null
                    else -> {
                        book.use(set.name)
                        ToolResult(
                            "Switched to \"${set.name}\". It answers from the wearer's next question on; this answer still " +
                                "comes from \"${c.modelSet}\". Tell the wearer in a few words.",
                            false,
                        ) to ToolAction(VISOR, "Config set · ${set.name}", true)
                    }
                }
            }
            else -> null
        }
    }

    /** One line for the switch tool's description: the note, else what the set runs on. */
    private fun ModelSet.short(): String = about.ifBlank { model() }.replace('\n', ' ').take(160)

    /** Everything the list tool tells of a set. */
    private fun ModelSet.full(inUse: Boolean): String = buildString {
        val s = settings
        append("- ").append(name).append(if (inUse) " (in use)" else "").append(": ")
        if (about.isNotBlank()) append(about.replace('\n', ' ')).append(". ")
        append("Model ").append(model())
        if (s.linesModel.isNotBlank()) append(", diary lines by ").append(s.linesModel)
        append(". ").append(if (s.glassesVoice) "Glasses' voice" else "Phone's voice")
        append(", camera ").append(if (s.vision) "on" else "off")
        if (s.provider == Provider.CLAUDE) append(", web search ").append(if (s.webSearch) "on" else "off")
        append(", remembers a conversation ${s.memoryMinutes} min, glasses wait ${s.answerWaitSeconds} s for an answer")
        if (s.instructions.isNotBlank()) append(", own instructions: \"").append(s.instructions.replace('\n', ' ').take(120)).append('"')
        append('.')
    }

    private fun ModelSet.model(): String = when (settings.provider) {
        Provider.CLAUDE -> "Claude " + settings.model.ifBlank { "(default model)" }
        Provider.OPENAI_COMPATIBLE -> settings.model.ifBlank { "(server's default model)" } + " on " +
            settings.baseUrl.substringAfter("://").substringBefore('/')
    }

    private companion object {
        const val LIST = "list_config_sets"
        const val USE = "switch_config_set"
        const val VISOR = "Visor"
        const val LIST_HELP =
            "List the wearer's saved config sets. A config set is a whole assistant setup kept under a name: the AI provider " +
                "and model that answer, the voice, whether the camera and web search are used, how long a conversation is " +
                "remembered, how long the glasses wait, and the wearer's own instructions, with their note of what the set is " +
                "for (for example heavy and smart but slow, fast and cheap, private on a local server). Says which set is in " +
                "use now. Call it when the wearer asks which setups or models they have, which one is answering, or how " +
                "they differ, and before switching when their request does not plainly match one set's name or note. Do not " +
                "call it for any other question."
        const val USE_HELP =
            "Switch the assistant to another of the wearer's saved config sets: its provider, model, voice, camera and web " +
                "search, memory window, answer wait and instructions all take over from the wearer's next question; this " +
                "answer still comes from the current set. Call it only when the wearer asks to change model or setup, by name " +
                "(\"use Work\") or by what they want (\"something smarter\", \"the fast one\", \"go local\", \"the cheap " +
                "model\"). Pick the set whose note and model best fit the request: a stronger or larger model for harder, " +
                "slower, deeper work; a smaller or faster one for quick answers; a local server for privacy or no internet. " +
                "When two sets fit equally or none fits, ask the wearer which one instead of guessing. Never switch on your " +
                "own to answer a question better, and never to the set already in use."
    }
}
