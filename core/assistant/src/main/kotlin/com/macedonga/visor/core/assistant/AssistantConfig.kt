package com.macedonga.visor.core.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How the assistant answers. The key stays in this app's private storage. */
data class AssistantConfig(
    val enabled: Boolean = true,
    val provider: Provider = Provider.CLAUDE,
    val apiKey: String = "",
    val model: String = "",
    // The model that writes each day's lines in the diary; empty, the answers' model.
    val linesModel: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val webSearch: Boolean = false,
    // Let a visual question use the glasses' camera.
    val vision: Boolean = true,
    // Answers in the glasses' own voice, the one that reads notifications; off, the phone's voice streamed to them.
    val glassesVoice: Boolean = true,
    val instructions: String = "",
    // How long a conversation is remembered after its last question.
    val memoryMinutes: Int = 10,
    // How long the glasses wait for an answer before they give up, written to them on connect.
    val answerWaitSeconds: Int = 45,
    // Tools described in the prompt and run from tags in the reply, for an OpenAI-compatible server that ignores the tools field.
    val toolsInText: Boolean = false,
    // The saved set the fields came from, and go back to as they change; empty, none.
    val modelSet: String = "",
    // The assistant's own tools for the saved sets: telling what they are, and switching to one when asked.
    val listSetsTool: Boolean = false,
    val useSetTool: Boolean = false,
) {
    /** What a set keeps: everything on the Meta AI page but whether it answers at all and the set tools themselves. */
    fun kept() = copy(enabled = AssistantConfig().enabled, modelSet = "", listSetsTool = false, useSetTool = false)
}

/** Everything on the Meta AI page, kept under a name to switch back to, with the wearer's note of what it is for. */
data class ModelSet(val name: String, val about: String, val settings: AssistantConfig) {
    fun into(c: AssistantConfig) = settings.copy(enabled = c.enabled, modelSet = name, listSetsTool = c.listSetsTool, useSetTool = c.useSetTool)

    /** As kept on the phone, or in a backup without the key. */
    fun json(key: Boolean = true): JSONObject = settings.let { s ->
        JSONObject().put("name", name).put("about", about).put("provider", s.provider.name).put("baseUrl", s.baseUrl)
            .put("model", s.model).put("linesModel", s.linesModel).put("webSearch", s.webSearch).put("vision", s.vision)
            .put("glassesVoice", s.glassesVoice).put("instructions", s.instructions).put("memoryMinutes", s.memoryMinutes)
            .put("answerWaitSeconds", s.answerWaitSeconds).put("toolsInText", s.toolsInText)
            .apply { if (key) put("apiKey", s.apiKey) }
    }

    companion object {
        fun of(name: String, about: String, c: AssistantConfig) = ModelSet(name, about, c.kept())

        /** A kept set; what it does not name (a set from before sets held everything) comes from [rest]. */
        fun from(o: JSONObject, rest: AssistantConfig): ModelSet? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            // The first sets were kept with these names.
            fun text(key: String, old: String, d: String) = if (o.has(key)) o.optString(key) else o.optString(old, d)
            return of(
                name, o.optString("about"),
                rest.copy(
                    provider = runCatching { Provider.valueOf(o.getString("provider")) }.getOrDefault(Provider.CLAUDE),
                    apiKey = text("apiKey", "api_key", ""),
                    baseUrl = text("baseUrl", "base_url", rest.baseUrl),
                    model = o.optString("model"),
                    linesModel = text("linesModel", "lines_model", ""),
                    webSearch = o.optBoolean("webSearch", rest.webSearch),
                    vision = o.optBoolean("vision", rest.vision),
                    glassesVoice = o.optBoolean("glassesVoice", rest.glassesVoice),
                    instructions = o.optString("instructions", rest.instructions),
                    memoryMinutes = o.optInt("memoryMinutes", rest.memoryMinutes),
                    answerWaitSeconds = o.optInt("answerWaitSeconds", rest.answerWaitSeconds),
                    toolsInText = o.optBoolean("toolsInText", false),
                ),
            )
        }
    }
}

/** The saved sets, for the assistant's own tools: what they are, and putting one in use. */
interface SetBook {
    val sets: StateFlow<List<ModelSet>>
    fun use(name: String)
}

/** The waits offered: 20 is the glasses' own; their watchdog ends a turn still processing at 60. */
val ANSWER_WAITS = listOf(20, 30, 45, 55)

class AssistantConfigStore(context: Context) : FieldBook, SetBook {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    private val _sets = MutableStateFlow(loadSets())
    override val sets: StateFlow<List<ModelSet>> = _sets.asStateFlow()

    init {
        // There is always a set in use: the first one is whatever was set up before sets existed.
        val c = _config.value
        if (_sets.value.none { it.name == c.modelSet }) {
            val first = _sets.value.firstOrNull()
            if (first != null) update { first.into(it) } else {
                val name = c.modelSet.ifBlank { firstName(c) }
                keepSets(listOf(ModelSet.of(name, "", c)))
                update { it.copy(modelSet = name) }
            }
        }
    }

    /** Puts a saved set in use: everything on the page becomes what it keeps. */
    override fun use(name: String) {
        val set = _sets.value.firstOrNull { it.name == name } ?: return
        update { set.into(it) }
    }

    /** A new set in use at once, without a provider's key or models yet; how it answers starts as the one in use. */
    fun create(name: String) {
        val d = AssistantConfig()
        val set = ModelSet.of(name, "", _config.value.copy(provider = d.provider, apiKey = "", baseUrl = d.baseUrl, model = "", linesModel = ""))
        keepSets(_sets.value.filter { it.name != name } + set)
        update { set.into(it) }
    }

    fun rename(from: String, to: String) {
        if (from == to || _sets.value.any { it.name == to }) return
        keepSets(_sets.value.map { if (it.name == from) it.copy(name = to) else it })
        if (_config.value.modelSet == from) update { it.copy(modelSet = to) }
    }

    /** The wearer's note of what a set is for, which the assistant reads when it picks one. */
    fun describe(name: String, about: String) {
        keepSets(_sets.value.map { if (it.name == name) it.copy(about = about.trim()) else it })
    }

    /** Deletes a set, never the last; deleting the one in use moves to the first left. */
    fun forget(name: String) {
        val left = _sets.value.filter { it.name != name }
        if (left.isEmpty()) return
        keepSets(left)
        if (_config.value.modelSet == name) update { left.first().into(it) }
    }

    /** Sets from a backup, keys left as they are here: a new name is added, a known one keeps its key. */
    fun restoreSets(sets: List<ModelSet>) {
        val here = _sets.value.associateBy { it.name }
        keepSets(_sets.value.filter { s -> sets.none { it.name == s.name } } + sets.map { it.copy(settings = it.settings.copy(apiKey = here[it.name]?.settings?.apiKey.orEmpty())) })
    }

    override fun fields(key: String): Fields? = runCatching {
        JSONObject(prefs.getString(FIELDS, null) ?: return null).optJSONObject(key)
            ?.let { Fields(it.getBoolean("reasoning_off"), it.getBoolean("completion_tokens")) }
    }.getOrNull()

    override fun keep(key: String, fields: Fields) {
        val all = runCatching { JSONObject(prefs.getString(FIELDS, null) ?: "{}") }.getOrDefault(JSONObject())
        all.put(key, JSONObject().put("reasoning_off", fields.reasoningOff).put("completion_tokens", fields.completionTokens))
        prefs.edit().putString(FIELDS, all.toString()).apply()
    }

    fun update(change: (AssistantConfig) -> AssistantConfig) {
        val before = _config.value
        val next = change(before)
        // What a server took holds for the address and models it was found with.
        val moved = next.provider != before.provider || next.baseUrl != before.baseUrl || next.model != before.model ||
            next.linesModel != before.linesModel
        prefs.edit()
            .apply { if (moved) remove(FIELDS) }
            .putBoolean("enabled", next.enabled)
            .putString("provider", next.provider.name)
            .putString("api_key", next.apiKey)
            .putString("model", next.model)
            .putString("lines_model", next.linesModel)
            .putString("base_url", next.baseUrl)
            .putBoolean("web_search", next.webSearch)
            .putBoolean("vision", next.vision)
            .putBoolean("glasses_voice", next.glassesVoice)
            .putString("instructions", next.instructions)
            .putInt("memory_minutes", next.memoryMinutes)
            .putInt("answer_wait_s", next.answerWaitSeconds)
            .putBoolean("tools_in_text", next.toolsInText)
            .putString("model_set", next.modelSet)
            .putBoolean("tool_list_sets", next.listSetsTool)
            .putBoolean("tool_use_set", next.useSetTool)
            .apply()
        _config.value = next
        // Edits on the page go back into the set they came from.
        if (next.modelSet.isNotEmpty()) {
            val now = next.kept()
            if (_sets.value.any { it.name == next.modelSet && it.settings != now }) keepSets(_sets.value.map { if (it.name == next.modelSet) it.copy(settings = now) else it })
        }
    }

    private fun load(): AssistantConfig {
        val d = AssistantConfig()
        return AssistantConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            provider = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(d.provider),
            apiKey = prefs.getString("api_key", d.apiKey).orEmpty(),
            model = prefs.getString("model", d.model).orEmpty(),
            linesModel = prefs.getString("lines_model", d.linesModel).orEmpty(),
            baseUrl = prefs.getString("base_url", d.baseUrl).orEmpty(),
            webSearch = prefs.getBoolean("web_search", d.webSearch),
            vision = prefs.getBoolean("vision", d.vision),
            glassesVoice = prefs.getBoolean("glasses_voice", d.glassesVoice),
            instructions = prefs.getString("instructions", d.instructions).orEmpty(),
            memoryMinutes = prefs.getInt("memory_minutes", d.memoryMinutes),
            answerWaitSeconds = prefs.getInt("answer_wait_s", d.answerWaitSeconds),
            toolsInText = prefs.getBoolean("tools_in_text", d.toolsInText),
            modelSet = prefs.getString("model_set", d.modelSet).orEmpty(),
            listSetsTool = prefs.getBoolean("tool_list_sets", d.listSetsTool),
            useSetTool = prefs.getBoolean("tool_use_set", d.useSetTool),
        )
    }

    private fun firstName(c: AssistantConfig): String =
        if (c.provider == Provider.CLAUDE) "Claude" else c.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Default" }

    private fun keepSets(sets: List<ModelSet>) {
        prefs.edit().putString(SETS, JSONArray(sets.map { it.json() }).toString()).apply()
        _sets.value = sets
    }

    private fun loadSets(): List<ModelSet> = runCatching {
        val a = JSONArray(prefs.getString(SETS, null) ?: return emptyList())
        val rest = _config.value
        (0 until a.length()).mapNotNull { i -> ModelSet.from(a.getJSONObject(i), rest) }
    }.getOrDefault(emptyList())

    private companion object {
        const val FIELDS = "server_fields"
        const val SETS = "model_sets"
    }
}
