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
    // The saved set the model fields came from, and go back to as they change; empty, none.
    val modelSet: String = "",
)

/** A provider, its server, key and models, kept under a name to switch back to. */
data class ModelSet(
    val name: String,
    val provider: Provider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val linesModel: String,
) {
    fun into(c: AssistantConfig) = c.copy(provider = provider, apiKey = apiKey, baseUrl = baseUrl, model = model, linesModel = linesModel, modelSet = name)

    companion object {
        fun of(name: String, c: AssistantConfig) = ModelSet(name, c.provider, c.apiKey, c.baseUrl, c.model, c.linesModel)
    }
}

/** The waits offered: 20 is the glasses' own; their watchdog ends a turn still processing at 60. */
val ANSWER_WAITS = listOf(20, 30, 45, 55)

class AssistantConfigStore(context: Context) : FieldBook {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    private val _sets = MutableStateFlow(loadSets())
    val sets: StateFlow<List<ModelSet>> = _sets.asStateFlow()

    init {
        // There is always a set in use: the first one is whatever was set up before sets existed.
        val c = _config.value
        if (_sets.value.none { it.name == c.modelSet }) {
            val first = _sets.value.firstOrNull()
            if (first != null) update { first.into(it) } else {
                val name = c.modelSet.ifBlank { firstName(c) }
                keepSets(listOf(ModelSet.of(name, c)))
                update { it.copy(modelSet = name) }
            }
        }
    }

    /** Puts a saved set's provider, key and models in use. */
    fun use(name: String) {
        val set = _sets.value.firstOrNull { it.name == name } ?: return
        update { set.into(it) }
    }

    /** A new, empty set, in use at once, to be filled in where the old one was. */
    fun create(name: String) {
        val d = AssistantConfig()
        val set = ModelSet(name, d.provider, "", d.baseUrl, "", "")
        keepSets(_sets.value.filter { it.name != name } + set)
        update { set.into(it) }
    }

    fun rename(from: String, to: String) {
        if (from == to || _sets.value.any { it.name == to }) return
        keepSets(_sets.value.map { if (it.name == from) it.copy(name = to) else it })
        if (_config.value.modelSet == from) update { it.copy(modelSet = to) }
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
        keepSets(_sets.value.filter { s -> sets.none { it.name == s.name } } + sets.map { it.copy(apiKey = here[it.name]?.apiKey.orEmpty()) })
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
            .putString("model_set", next.modelSet)
            .apply()
        _config.value = next
        // Edits to the model fields go back into the set they came from.
        if (next.modelSet.isNotEmpty()) {
            val now = ModelSet.of(next.modelSet, next)
            if (_sets.value.any { it.name == now.name && it != now }) keepSets(_sets.value.map { if (it.name == now.name) now else it })
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
            modelSet = prefs.getString("model_set", d.modelSet).orEmpty(),
        )
    }

    private fun firstName(c: AssistantConfig): String =
        if (c.provider == Provider.CLAUDE) "Claude" else c.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Default" }

    private fun keepSets(sets: List<ModelSet>) {
        val a = JSONArray()
        sets.forEach {
            a.put(
                JSONObject().put("name", it.name).put("provider", it.provider.name).put("api_key", it.apiKey)
                    .put("base_url", it.baseUrl).put("model", it.model).put("lines_model", it.linesModel),
            )
        }
        prefs.edit().putString(SETS, a.toString()).apply()
        _sets.value = sets
    }

    private fun loadSets(): List<ModelSet> = runCatching {
        val a = JSONArray(prefs.getString(SETS, null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ModelSet(
                o.getString("name"), runCatching { Provider.valueOf(o.getString("provider")) }.getOrDefault(Provider.CLAUDE),
                o.optString("api_key"), o.optString("base_url"), o.optString("model"), o.optString("lines_model"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val FIELDS = "server_fields"
        const val SETS = "model_sets"
    }
}
