package com.macedonga.visor.core.assistant

import android.content.Context
import com.macedonga.visor.core.assistant.mcp.McpServer
import com.macedonga.visor.core.assistant.mcp.McpStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import kotlinx.coroutines.flow.update
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
    // Let a partner-language voice command take a photo; it turns on the glasses' Robusta switch, not Developer mode.
    val voicePhotos: Boolean = false,
    // Who says the answers: the glasses' own voice, the phone's engine streamed to them, or a cloud service.
    val tts: TtsPath = TtsPath.GLASSES,
    // The cloud speech service, OpenAI compatible; the key stays out of backups like the model's.
    val ttsBaseUrl: String = "https://api.openai.com/v1",
    val ttsApiKey: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    // No spoken answer when every tool the model used did what was asked, such as music that now plays.
    val quietAfterActions: Boolean = false,
    // The language the wearer speaks, as a BCP 47 tag, when the glasses have no pack for it; empty, the glasses' own.
    // The phone then hears every question, the model answers in it, and the phone's voice speaks.
    val speechLanguage: String = "",
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
    // A wake phrase (the glasses' custom one) whose requests go to the phone's own assistant; empty, off. Not kept in a set.
    val assistantPhrase: String = "",
    // The phrase counts anywhere in the request, not only at its start.
    val assistantAnywhere: Boolean = false,
    // The ChatGPT account signed in for the ChatGPT provider, by its email; empty when signed out.
    val chatGpt: String = "",
) {
    /** A model can answer: a service needs its key or sign-in, an OpenAI-compatible server may need neither. */
    val hasModel: Boolean get() = when (provider) {
        Provider.OPENAI_COMPATIBLE -> true
        Provider.CHATGPT -> chatGpt.isNotEmpty()
        else -> apiKey.isNotBlank()
    }

    /** What a set keeps: everything on the Meta AI page but whether it answers at all, the set tools and the ChatGPT sign-in. */
    fun kept() = copy(enabled = AssistantConfig().enabled, modelSet = "", listSetsTool = false, useSetTool = false, assistantPhrase = "", assistantAnywhere = false, chatGpt = "")

    /** The API address: the service's own, or the one typed in for an OpenAI-compatible server. */
    val api: String get() = if (provider == Provider.OPENAI_COMPATIBLE) baseUrl else provider.api

    /** The model asked: the one chosen, or the provider's own default. */
    val answerModel: String get() = model.ifBlank { provider.defaultModel }
}

/** One integration as a profile keeps it: on or off, and the tools turned off. */
data class McpChoice(val on: Boolean, val off: Set<String> = emptySet())

/**
 * Everything on the Meta AI page, kept under a name to switch back to, with the wearer's note of what it is for.
 * [mcp] is which integrations are on, by address (a server's id changes when a backup brings it back); null in a
 * profile from before profiles kept them.
 */
data class ModelSet(val name: String, val about: String, val settings: AssistantConfig, val mcp: Map<String, McpChoice>? = null) {
    fun into(c: AssistantConfig) = settings.copy(enabled = c.enabled, modelSet = name, listSetsTool = c.listSetsTool, useSetTool = c.useSetTool, assistantPhrase = c.assistantPhrase, assistantAnywhere = c.assistantAnywhere, chatGpt = c.chatGpt)

    /** As kept on the phone, or in a backup without the key. */
    fun json(key: Boolean = true): JSONObject = settings.let { s ->
        JSONObject().put("name", name).put("about", about).put("provider", s.provider.name).put("baseUrl", s.baseUrl)
            .put("model", s.model).put("linesModel", s.linesModel).put("webSearch", s.webSearch).put("vision", s.vision)
            .put("voicePhotos", s.voicePhotos).put("tts", s.tts.name).put("ttsBaseUrl", s.ttsBaseUrl).put("ttsModel", s.ttsModel).put("ttsVoice", s.ttsVoice)
            .put("quietAfterActions", s.quietAfterActions).put("speechLanguage", s.speechLanguage).put("instructions", s.instructions).put("memoryMinutes", s.memoryMinutes)
            .put("answerWaitSeconds", s.answerWaitSeconds).put("toolsInText", s.toolsInText)
            .apply { if (key) put("apiKey", s.apiKey).put("ttsApiKey", s.ttsApiKey) }
            .apply {
                mcp?.let { m ->
                    put("mcp", JSONObject().apply { m.forEach { (url, c) -> put(url, JSONObject().put("on", c.on).put("off", JSONArray(c.off.sorted()))) } })
                }
            }
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
                    voicePhotos = o.optBoolean("voicePhotos", rest.voicePhotos),
                    // Sets from before the cloud voice kept a boolean for the glasses' voice.
                    tts = o.optString("tts").takeIf { it.isNotEmpty() }?.let { runCatching { TtsPath.valueOf(it) }.getOrNull() }
                        ?: if (o.has("glassesVoice")) (if (o.optBoolean("glassesVoice")) TtsPath.GLASSES else TtsPath.PHONE) else rest.tts,
                    ttsBaseUrl = o.optString("ttsBaseUrl", rest.ttsBaseUrl),
                    ttsApiKey = o.optString("ttsApiKey", ""),
                    ttsModel = o.optString("ttsModel", rest.ttsModel),
                    ttsVoice = o.optString("ttsVoice", rest.ttsVoice),
                    quietAfterActions = o.optBoolean("quietAfterActions", rest.quietAfterActions),
                    speechLanguage = o.optString("speechLanguage", rest.speechLanguage),
                    instructions = o.optString("instructions", rest.instructions),
                    memoryMinutes = o.optInt("memoryMinutes", rest.memoryMinutes),
                    answerWaitSeconds = o.optInt("answerWaitSeconds", rest.answerWaitSeconds),
                    toolsInText = o.optBoolean("toolsInText", false),
                ),
            ).copy(
                mcp = o.optJSONObject("mcp")?.let { m ->
                    m.keys().asSequence().associateWith { url ->
                        val c = m.getJSONObject(url)
                        McpChoice(c.optBoolean("on", true), c.optJSONArray("off")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty())
                    }
                },
            )
        }
    }
}

/** The saved sets, for the assistant's own tools: what they are, and putting one in use. */
interface SetBook {
    val sets: StateFlow<List<ModelSet>>
    fun use(name: String)
}

/** Who says the answer out loud. */
enum class TtsPath { GLASSES, PHONE, CLOUD }

/** Languages the wearer can speak that the glasses have no pack for, heard and spoken by the phone. */
val SPEECH_LANGUAGES = listOf(
    "ru-RU", "uk-UA", "pl-PL", "cs-CZ", "ro-RO", "hu-HU", "el-GR", "sv-SE", "da-DK", "nb-NO", "fi-FI",
    "he-IL", "ar-SA", "vi-VN", "id-ID", "th-TH",
)

/** The waits offered: 20 is the glasses' own; their watchdog ends a turn still processing at 60. */
val ANSWER_WAITS = listOf(20, 30, 45, 55)

/** A wait of one's own: under the glasses' 60 s watchdog, which ends the turn whatever this says. */
val ANSWER_WAIT_RANGE = 10..59

/** The windows offered for a conversation to stay open, and the range of one's own; only the phone keeps it. */
val MEMORY_WINDOWS = listOf(5, 10, 20, 30)
val MEMORY_WINDOW_RANGE = 1..1440

class AssistantConfigStore(context: Context, private val mcp: McpStore) : SetBook {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    private val _sets = MutableStateFlow(loadSets())
    override val sets: StateFlow<List<ModelSet>> = _sets.asStateFlow()


    /** What the integrations are now, as a profile keeps them. */
    private fun mcpNow(): Map<String, McpChoice> = mcp.servers.value.associate { it.url to McpChoice(it.enabled, it.off) }

    private fun fillMcp() {
        if (_sets.value.any { it.mcp == null }) keepSets(_sets.value.map { if (it.mcp == null) it.copy(mcp = mcpNow()) else it })
    }

    /** Integrations turned on or off go into the profile in use; one moved to a new address or deleted, into every profile. */
    @Synchronized
    private fun mcpChanged(before: List<McpServer>, after: List<McpServer>) {
        val moved = before.mapNotNull { b -> after.firstOrNull { it.id == b.id && it.url != b.url }?.let { b.url to it.url } }.toMap()
        val gone = before.filter { b -> after.none { it.id == b.id } }.map { it.url }.toSet()
        val now = mcpNow()
        val current = _config.value.modelSet
        val next = _sets.value.map { s ->
            if (s.name == current) return@map s.copy(mcp = now)
            val m = s.mcp ?: return@map s
            if (moved.isEmpty() && gone.isEmpty()) s
            else s.copy(mcp = m.filterKeys { it !in gone }.mapKeys { (url) -> moved[url] ?: url })
        }
        if (next != _sets.value) keepSets(next)
    }

    /** Turns the integrations to what [set] keeps; one it never had is off there. */
    private fun useMcp(set: ModelSet) {
        val m = set.mcp ?: return mcpChanged(mcp.servers.value, mcp.servers.value)
        mcp.updateAll { s -> m[s.url]?.let { s.copy(enabled = it.on, off = it.off) } ?: s.copy(enabled = false) }
    }

    /** Puts a saved set in use: everything on the page becomes what it keeps. */
    override fun use(name: String) {
        val set = _sets.value.firstOrNull { it.name == name } ?: return
        update { set.into(it) }
        useMcp(set)
    }

    /** A new set in use at once, without a provider's key or models yet; how it answers starts as the one in use. */
    fun create(name: String) {
        val d = AssistantConfig()
        val set = ModelSet.of(name, "", _config.value.copy(provider = d.provider, apiKey = "", baseUrl = d.baseUrl, model = "", linesModel = "")).copy(mcp = mcpNow())
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
        if (_config.value.modelSet == name) {
            update { left.first().into(it) }
            useMcp(left.first())
        }
    }

    /** Sets from a backup, keys left as they are here: a new name is added, a known one keeps its key. */
    fun restoreSets(sets: List<ModelSet>) {
        val here = _sets.value.associateBy { it.name }
        keepSets(_sets.value.filter { s -> sets.none { it.name == s.name } } + sets.map { it.copy(settings = it.settings.copy(apiKey = here[it.name]?.settings?.apiKey.orEmpty(), ttsApiKey = here[it.name]?.settings?.ttsApiKey.orEmpty())) })
        fillMcp()
    }

    private val _sessionVoices = MutableStateFlow(prefs.getStringSet(SESSION_VOICES, null).orEmpty().toSet())
    /** Voices the glasses spoke session turns in, where the phone speaks. */
    val sessionVoices: StateFlow<Set<String>> = _sessionVoices.asStateFlow()

    fun markSessionVoice(voice: String) = setSessionVoices { it + voice }

    /** A plain turn in [voice]: the glasses speak it again, as after Visor turned their session path off. */
    fun forgetSessionVoice(voice: String) = setSessionVoices { it - voice }

    private fun setSessionVoices(change: (Set<String>) -> Set<String>) {
        _sessionVoices.update(change)
        prefs.edit().putStringSet(SESSION_VOICES, _sessionVoices.value).apply()
    }

    /** The [Fields] found for an address and model, kept until either changes. */
    fun fields(key: String): Fields? = runCatching {
        JSONObject(prefs.getString(FIELDS, null) ?: return null).optJSONObject(key)
            ?.let { Fields(it.getBoolean("reasoning_off"), it.getBoolean("completion_tokens"), it.optBoolean("thinks")) }
    }.getOrNull()

    fun keep(key: String, fields: Fields) {
        val all = runCatching { JSONObject(prefs.getString(FIELDS, null) ?: "{}") }.getOrDefault(JSONObject())
        all.put(key, JSONObject().put("reasoning_off", fields.reasoningOff).put("completion_tokens", fields.completionTokens).put("thinks", fields.thinks))
        prefs.edit().putString(FIELDS, all.toString()).apply()
    }

    private val _noSearch = MutableStateFlow(prefs.getStringSet(NO_SEARCH, null).orEmpty().toSet())
    /** The provider and model pairs that refused a web search, so their answers go without it. */
    val noSearch: StateFlow<Set<String>> = _noSearch.asStateFlow()

    // After every field above: update() reads them.
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
        // Integrations were the same for every profile before; each profile starts with them as they are.
        fillMcp()
        mcp.onChange = ::mcpChanged
    }

    fun cannotSearch(c: AssistantConfig): Boolean = searchKey(c) in _noSearch.value

    fun markCannotSearch(c: AssistantConfig) = setNoSearch(_noSearch.value + searchKey(c))

    private fun setNoSearch(keys: Set<String>) {
        _noSearch.value = keys
        prefs.edit().putStringSet(NO_SEARCH, keys).apply()
    }

    /**
     * Another provider, with the key and models it had last time; the ones left keep theirs for the way back. A
     * ChatGPT sign-in stays with ChatGPT.
     */
    fun switchProvider(to: Provider) {
        val c = _config.value
        if (c.provider == to) return
        val slots = runCatching { JSONObject(prefs.getString(SLOTS, null) ?: "{}") }.getOrDefault(JSONObject())
        slots.put(c.provider.name, JSONObject().put("key", c.apiKey).put("model", c.model).put("lines", c.linesModel))
        prefs.edit().putString(SLOTS, slots.toString()).apply()
        val slot = slots.optJSONObject(to.name)
        update {
            it.copy(
                provider = to,
                apiKey = slot?.optString("key").orEmpty(),
                model = slot?.optString("model").orEmpty(),
                linesModel = slot?.optString("lines").orEmpty(),
            )
        }
    }

    fun update(change: (AssistantConfig) -> AssistantConfig) {
        val before = _config.value
        val next = change(before)
        // Turned on again, search is tried again with every model.
        if (next.webSearch && !before.webSearch && _noSearch.value.isNotEmpty()) setNoSearch(emptySet())
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
            .putBoolean("voice_photos", next.voicePhotos)
            .putString("tts", next.tts.name)
            .putString("tts_base_url", next.ttsBaseUrl)
            .putString("tts_api_key", next.ttsApiKey)
            .putString("tts_model", next.ttsModel)
            .putString("tts_voice", next.ttsVoice)
            .putBoolean("quiet_after_actions", next.quietAfterActions)
            .putString("speech_language", next.speechLanguage)
            .putString("instructions", next.instructions)
            .putInt("memory_minutes", next.memoryMinutes)
            .putInt("answer_wait_s", next.answerWaitSeconds)
            .putBoolean("tools_in_text", next.toolsInText)
            .putString("model_set", next.modelSet)
            .putBoolean("tool_list_sets", next.listSetsTool)
            .putBoolean("tool_use_set", next.useSetTool)
            .putString("assistant_phrase", next.assistantPhrase)
            .putBoolean("assistant_anywhere", next.assistantAnywhere)
            .putString("chatgpt", next.chatGpt)
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
        val baseUrl = prefs.getString("base_url", d.baseUrl).orEmpty()
        val provider = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(d.provider)
        return AssistantConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            // A known service's address typed in by hand is that service's preset.
            provider = if (provider == Provider.OPENAI_COMPATIBLE) Provider.at(baseUrl) ?: provider else provider,
            apiKey = prefs.getString("api_key", d.apiKey).orEmpty(),
            model = prefs.getString("model", d.model).orEmpty(),
            linesModel = prefs.getString("lines_model", d.linesModel).orEmpty(),
            baseUrl = baseUrl,
            webSearch = prefs.getBoolean("web_search", d.webSearch),
            vision = prefs.getBoolean("vision", d.vision),
            voicePhotos = prefs.getBoolean("voice_photos", d.voicePhotos),
            // Before "tts", a boolean chose the glasses' voice or the phone's.
            tts = prefs.getString("tts", null)?.let { runCatching { TtsPath.valueOf(it) }.getOrNull() }
                ?: if (prefs.getBoolean("glasses_voice", true)) TtsPath.GLASSES else TtsPath.PHONE,
            ttsBaseUrl = prefs.getString("tts_base_url", d.ttsBaseUrl).orEmpty(),
            ttsApiKey = prefs.getString("tts_api_key", d.ttsApiKey).orEmpty(),
            ttsModel = prefs.getString("tts_model", d.ttsModel).orEmpty(),
            ttsVoice = prefs.getString("tts_voice", d.ttsVoice).orEmpty(),
            quietAfterActions = prefs.getBoolean("quiet_after_actions", d.quietAfterActions),
            speechLanguage = prefs.getString("speech_language", d.speechLanguage).orEmpty(),
            instructions = prefs.getString("instructions", d.instructions).orEmpty(),
            memoryMinutes = prefs.getInt("memory_minutes", d.memoryMinutes),
            answerWaitSeconds = prefs.getInt("answer_wait_s", d.answerWaitSeconds),
            toolsInText = prefs.getBoolean("tools_in_text", d.toolsInText),
            modelSet = prefs.getString("model_set", d.modelSet).orEmpty(),
            listSetsTool = prefs.getBoolean("tool_list_sets", d.listSetsTool),
            useSetTool = prefs.getBoolean("tool_use_set", d.useSetTool),
            assistantPhrase = prefs.getString("assistant_phrase", d.assistantPhrase).orEmpty(),
            assistantAnywhere = prefs.getBoolean("assistant_anywhere", d.assistantAnywhere),
            chatGpt = prefs.getString("chatgpt", d.chatGpt).orEmpty(),
        )
    }

    private fun firstName(c: AssistantConfig): String =
        if (c.provider != Provider.OPENAI_COMPATIBLE) c.provider.title else c.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Default" }

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
        const val SESSION_VOICES = "session_voices"
        const val NO_SEARCH = "no_search"
        const val SLOTS = "provider_slots"

        fun searchKey(c: AssistantConfig) = "${c.provider.name} ${c.answerModel}"
    }
}
