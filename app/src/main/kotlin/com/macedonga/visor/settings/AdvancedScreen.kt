package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.pluralStringResource
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
import com.macedonga.visor.RequestLogKey
import com.macedonga.visor.SettingsGroupKey
import com.macedonga.visor.core.connection.isDisplayBuild
import com.macedonga.visor.core.connection.isDisplayProduct
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GROUPS
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.SettingsState
import com.macedonga.visor.ui.VisorIcons

/** Every setting by firmware group, other models' groups last and dimmed, then the tools. A search over them all sits on top. */
@Composable
fun AdvancedScreen(open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    val state by model.settings.state.collectAsStateWithLifecycle()
    val display = isDisplayProduct(rememberProduct(model.settings, model.lastSeen))
    val byGroup = allKeys(model.settings, state).groupBy { specOf(model.settings, it).group }
    val (own, others) = ALL_GROUPS.filter { byGroup[it] != null }.partition { display || it !in DISPLAY_GROUPS }
    var query by rememberSaveable { mutableStateOf("") }

    SettingsPage(
        stringResource(R.string.advanced_title),
        light = 0.3f,
        actions = {
            if (state.reading) Spinner(Modifier.padding(horizontal = 13.dp))
            else if (glasses.link == LinkState.READY) RoundIcon(VisorIcons.Refresh, stringResource(R.string.advanced_read_again), { model.settings.read(full = true) })
        },
    ) {
        item(key = "search") {
            SearchField(query, { query = it }, stringResource(R.string.advanced_search), Modifier.padding(horizontal = 16.dp).padding(bottom = 18.dp))
        }
        if (query.isNotBlank()) {
            item(key = "results") { AdvancedResults(query, allKeys(model.settings, state).toSet(), display, model, open) }
            return@SettingsPage
        }
        item(key = "groups") {
            Rows(own) { group -> GroupRow(group, byGroup[group].orEmpty(), state, model, open) }
        }
        if (others.isNotEmpty()) {
            item(key = "others") {
                Column {
                    SectionLabel(stringResource(R.string.advanced_other_models))
                    Rows(others) { group -> GroupRow(group, byGroup[group].orEmpty(), state, model, open, dim = true) }
                }
            }
        }
        item(key = "tools") {
            Column {
                SectionLabel(stringResource(R.string.advanced_tools))
                Rows(
                    { NavRow(VisorIcons.Flag, stringResource(R.string.settings_flags), null, onClick = { open(FlagsKey) }, onCard = true) },
                    { NavRow(VisorIcons.Pulse, stringResource(R.string.settings_diagnostics), null, onClick = { open(DeviceKey) }, onCard = true) },
                    { NavRow(VisorIcons.Lines, stringResource(R.string.settings_event_log), null, onClick = { open(EventLogKey) }, onCard = true) },
                    { NavRow(VisorIcons.Globe, stringResource(R.string.requests_title), null, onClick = { open(RequestLogKey) }, onCard = true) },
                    { BugReportRow(model) },
                )
                // The bond needs a Display connected now.
                if (isDisplayBuild(glasses.info?.build) && classicBondOffered) {
                    Spacer(Modifier.height(12.dp))
                    Rows({ ClassicBondRows() })
                }
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
    val state by model.settings.state.collectAsStateWithLifecycle()
    val byGroup = all.groupBy { specOf(model.settings, it).group }
    Column {
        if (groups.isNotEmpty() || toolHits.isNotEmpty()) {
            Rows(
                *groups.filter { byGroup[it] != null }.map { g -> @Composable { GroupRow(g, byGroup[g].orEmpty(), state, model, open, dim = g in DISPLAY_GROUPS && !display) } }.toTypedArray(),
                *toolHits.map { (title, sub, key) -> @Composable { NavRow(null, stringResource(title), stringResource(sub), onClick = { open(key) }, onCard = true) } }.toTypedArray(),
            )
        }
        if (keys.isNotEmpty()) {
            if (groups.isNotEmpty() || toolHits.isNotEmpty()) SectionLabel(stringResource(R.string.advanced_search_settings))
            Rows(*keys.map { key -> @Composable { GlassesRow(key, overline = groupTitle(specOf(model.settings, key).group)) } }.toTypedArray())
            if (keys.size == MAX_RESULTS) Footnote(stringResource(R.string.advanced_search_more, MAX_RESULTS))
        }
    }
}

private const val MAX_RESULTS = 80

/** Asks the glasses for a bug report; it lands in Download/Visor. */
@Composable
private fun BugReportRow(model: SettingsViewModel) {
    val host = LocalHost.current
    val asked = stringResource(R.string.bug_report_asked)
    NavRow(
        VisorIcons.Download, stringResource(R.string.event_log_bug_report), null,
        onClick = {
            model.media.requestBugReport()
            host.notice.show(asked)
        },
        onCard = true, enabled = host.ready, chevron = false,
    )
}

@Composable
private fun GroupRow(group: String, keys: List<String>, state: SettingsState, model: SettingsViewModel, open: (NavKey) -> Unit, dim: Boolean = false) {
    val changed = keys.count { state.changed(it, model.settings.catalogue[it]) }
    NavRow(
        null, groupTitle(group),
        if (changed > 0) pluralStringResource(R.plurals.advanced_group_sum_changed, keys.size, keys.size, changed) else pluralStringResource(R.plurals.advanced_group_sum, keys.size, keys.size),
        onClick = { open(SettingsGroupKey(group)) }, dim = dim, onCard = true,
    )
}

/** One firmware group, every key in it. */
@Composable
fun SettingsGroupScreen(group: String, model: SettingsViewModel = hiltViewModel()) {
    val state by model.settings.state.collectAsStateWithLifecycle()
    val dim = group in DISPLAY_GROUPS && !isDisplayProduct(rememberProduct(model.settings, model.lastSeen))
    val keys = groupKeys(model.settings, state, group).sortedBy { specOf(model.settings, it).label }

    SettingsPage(groupTitle(group), light = 0.3f, engineering = true) {
        item(key = "keys") {
            Column(Modifier.alpha(if (dim) 0.55f else 1f)) {
                Rows(keys) { GlassesRow(it) }
            }
        }
    }
}

/** All of a firmware group's keys. */
internal fun groupKeys(settings: GlassesSettings, state: SettingsState, group: String): List<String> =
    allKeys(settings, state).filter { specOf(settings, it).group == group }

internal val ALL_GROUPS = GROUPS + OTHER

// The catalogue's English group names stay the ids.
private val GROUP_TITLES = mapOf(
    "Glasses" to R.string.group_glasses,
    "Sound" to R.string.group_sound,
    "Touch and gestures" to R.string.group_touch,
    "Camera" to R.string.group_camera,
    "Video and live" to R.string.group_video,
    "Meta AI" to R.string.group_meta_ai,
    "Live AI and translation" to R.string.group_live_ai,
    "Calls and messages" to R.string.group_calls,
    "Notifications" to R.string.group_notifications,
    "Music and media" to R.string.group_media,
    "Connectivity" to R.string.group_connectivity,
    "Health and fitness" to R.string.group_health,
    "Accessibility" to R.string.group_accessibility,
    "Privacy and data" to R.string.group_privacy,
    "Updates and system" to R.string.group_updates,
    "Display" to R.string.group_display,
    "Neural band gestures" to R.string.group_band,
    "Developer and tests" to R.string.group_developer,
    OTHER to R.string.group_other,
)

/** A group's name in the phone's language. */
@Composable
internal fun groupTitle(group: String): String = GROUP_TITLES[group]?.let { stringResource(it) } ?: group
