package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.Control
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.DeviceKey
import com.macedonga.visor.EventLogKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.R
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.ui.VisorIcons

/**
 * Every setting the glasses keep, by the firmware's own groups, and the tools for the link. The groups for glasses
 * with a display come last and dimmed, unless the glasses connected have one. A search over them all sits on top.
 */
@Composable
fun AdvancedScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val display = hasDisplay(glasses.info?.build)
    val all = allKeys(model.settings, state)
    val changed = all.count { state.changed(it, model.settings.catalogue[it]) }
    val byGroup = all.groupBy { specOf(model.settings, it).group }
    val (own, others) = ALL_GROUPS.filter { byGroup[it] != null }.partition { display || it !in DISPLAY_GROUPS }
    var query by rememberSaveable { mutableStateOf("") }

    SettingsPage(
        stringResource(R.string.advanced_title),
        stringResource(R.string.advanced_lede, all.size, changed) + " " + (state.readAt?.let { stringResource(R.string.advanced_read_at, timeWords(it)) } ?: ""),
        light = 0.3f,
        actions = {
            if (state.reading) Spinner(Modifier.padding(horizontal = 13.dp))
            else RoundIcon(VisorIcons.Refresh, stringResource(R.string.advanced_read_again), { model.settings.read(full = true) })
        },
    ) {
        item(key = "search") {
            SearchField(query, { query = it }, stringResource(R.string.advanced_search), Modifier.padding(horizontal = 16.dp).padding(bottom = 18.dp))
        }
        if (query.isNotBlank()) {
            item(key = "results") { AdvancedResults(query, all, display, model, open) }
            return@SettingsPage
        }
        item(key = "groups") {
            Rows(*own.map { group -> @Composable { GroupRow(group, byGroup[group].orEmpty(), state, model, open) } }.toTypedArray())
        }
        if (others.isNotEmpty()) {
            item(key = "others") {
                Column {
                    SectionLabel(stringResource(R.string.advanced_other_models))
                    Rows(*others.map { group -> @Composable { GroupRow(group, byGroup[group].orEmpty(), state, model, open, dim = true) } }.toTypedArray())
                    Footnote(stringResource(R.string.advanced_other_models_note))
                }
            }
        }
        item(key = "tools") {
            Column {
                SectionLabel(stringResource(R.string.advanced_tools))
                Rows(
                    { NavRow(VisorIcons.Flag, stringResource(R.string.settings_flags), stringResource(R.string.settings_flags_sub), onClick = { open(FlagsKey) }, onCard = true) },
                    { NavRow(VisorIcons.Pulse, stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_sub), onClick = { open(DeviceKey) }, onCard = true) },
                    { NavRow(VisorIcons.Lines, stringResource(R.string.settings_event_log), stringResource(R.string.settings_event_log_sub), onClick = { open(EventLogKey) }, onCard = true) },
                )
            }
        }
    }
}

/** Every key, group and tool here, best match first: rows work right where they are found. */
@Composable
private fun AdvancedResults(query: String, all: Set<String>, display: Boolean, model: SettingsViewModel, open: (NavKey) -> Unit) {
    val context = LocalContext.current
    val docs = remember(all) {
        all.associateWith { key ->
            val spec = specOf(model.settings, key)
            val options = (spec.control as? Control.Choice)?.options?.filterNot { it.hidden }?.joinToString(" ") { listOfNotNull(it.label, it.detail).joinToString(" ") }
            FuzzyDoc(
                listOf(
                    spec.label to 3f,
                    key to 2.5f,
                    Topic.of(key)?.let { context.getString(it.title) } to 1.5f,
                    options to 1.5f,
                    spec.group to 1.2f,
                    spec.note to 1f,
                ),
            )
        }
    }
    val tools = listOf(
        Triple(R.string.settings_flags, R.string.settings_flags_sub, FlagsKey),
        Triple(R.string.settings_diagnostics, R.string.settings_diagnostics_sub, DeviceKey),
        Triple(R.string.settings_event_log, R.string.settings_event_log_sub, EventLogKey),
    )
    val q = remember(query) { FuzzyQuery(query) }
    val keys = remember(q, docs, display) {
        docs.mapNotNull { (key, doc) -> doc.score(q)?.let { key to it } }
            .sortedWith(compareBy({ specOf(model.settings, it.first).group in DISPLAY_GROUPS && !display }, { -it.second }, { specOf(model.settings, it.first).label }))
            .take(MAX_RESULTS).map { it.first }
    }
    val groups = ALL_GROUPS.filter { g -> FuzzyDoc(listOf(g to 3f)).score(q) != null }
    val toolHits = tools.filter { (title, sub, _) -> FuzzyDoc(listOf(context.getString(title) to 3f, context.getString(sub) to 1f)).score(q) != null }

    if (keys.isEmpty() && groups.isEmpty() && toolHits.isEmpty()) {
        Text(stringResource(R.string.settings_nothing_found), style = Visor.type.body, color = Visor.colors.textSecondary, modifier = Modifier.padding(horizontal = SIDE, vertical = 12.dp))
        return
    }
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val byGroup = all.groupBy { specOf(model.settings, it).group }
    Column {
        if (groups.isNotEmpty() || toolHits.isNotEmpty()) {
            Rows(
                *groups.filter { byGroup[it] != null }.map { g -> @Composable { GroupRow(g, byGroup[g].orEmpty(), state, model, open, dim = g in DISPLAY_GROUPS && !hasDisplay(glasses.info?.build)) } }.toTypedArray(),
                *toolHits.map { (title, sub, key) -> @Composable { NavRow(null, stringResource(title), stringResource(sub), onClick = { open(key) }, onCard = true) } }.toTypedArray(),
            )
        }
        if (keys.isNotEmpty()) {
            if (groups.isNotEmpty() || toolHits.isNotEmpty()) SectionLabel(stringResource(R.string.advanced_search_settings))
            Rows(*keys.map { key -> @Composable { GlassesRow(key, engineering = true, overline = specOf(model.settings, key).group) } }.toTypedArray())
            if (keys.size == MAX_RESULTS) Footnote(stringResource(R.string.advanced_search_more, MAX_RESULTS))
        }
    }
}

private const val MAX_RESULTS = 80

@Composable
private fun GroupRow(group: String, keys: List<String>, state: com.macedonga.visor.core.settings.SettingsState, model: SettingsViewModel, open: (NavKey) -> Unit, dim: Boolean = false) {
    val changed = keys.count { state.changed(it, model.settings.catalogue[it]) }
    NavRow(
        null, group,
        if (changed > 0) stringResource(R.string.advanced_group_sum_changed, keys.size, changed) else stringResource(R.string.advanced_group_sum, keys.size),
        onClick = { open(SettingsGroupKey(group)) }, dim = dim, onCard = true,
    )
}

/** One firmware group, every key in it: what the Meta app shows first, its internal knobs after. */
@Composable
fun SettingsGroupScreen(group: String, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val dim = group in DISPLAY_GROUPS && !hasDisplay(glasses.info?.build)
    val keys = groupKeys(model.settings, state, group).sortedBy { specOf(model.settings, it).label }
    val (shown, internal) = keys.partition { specOf(model.settings, it).userFacing }

    SettingsPage(group, if (dim) stringResource(R.string.advanced_other_models_note) else null, light = 0.3f) {
        if (shown.isNotEmpty()) {
            item(key = "shown") {
                Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                    SectionLabel(stringResource(R.string.advanced_in_meta_app), Modifier.padding(top = 0.dp))
                    Rows(*shown.map { key -> @Composable { GlassesRow(key, engineering = true) } }.toTypedArray())
                }
            }
        }
        if (internal.isNotEmpty()) {
            item(key = "internal") {
                Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                    SectionLabel(stringResource(R.string.advanced_internal))
                    Rows(*internal.map { key -> @Composable { GlassesRow(key, engineering = true) } }.toTypedArray())
                    Footnote(stringResource(R.string.advanced_internal_note))
                }
            }
        }
    }
}
