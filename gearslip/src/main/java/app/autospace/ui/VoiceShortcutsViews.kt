package app.autospace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.autospace.car.VoiceShortcut
import app.autospace.car.VoiceShortcutType
import app.autospace.car.VoiceShortcuts
import app.autospace.car.rememberAllEntries

/**
 * Phone Settings section for custom voice shortcuts (r19). This is the only place
 * shortcuts are added or edited — typing an address on the car keyboard is miserable,
 * so the car side only lists them read-only. Stored on this phone only, in the same
 * prefs as the other car settings; nothing syncs anywhere.
 */
@Composable
internal fun VoiceShortcutsSection() {
    val context = LocalContext.current
    LaunchedEffect(context) { VoiceShortcuts.ensureLoaded(context) }
    val shortcuts by VoiceShortcuts.shortcuts.collectAsState()
    var editing by remember { mutableStateOf<EditingShortcut?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }

    SettingsSection(
        "Voice shortcuts",
        footer = "Say a keyword after a navigate, open, or search phrasing — " +
            "for example, “navigate to home” for a saved address, or just the keyword " +
            "itself for an app or a search. A keyword never beats a built-in command, " +
            "and matching is exact: a near-miss is handled normally, not as a shortcut.",
    ) {
        if (shortcuts.isEmpty()) {
            SettingsRow(
                "No voice shortcuts yet",
                subtitle = "Add one below. Navigation keywords need a saved address; " +
                    "app keywords pick an app; search keywords save a search term.",
            )
        } else {
            shortcuts.forEachIndexed { index, shortcut ->
                if (index > 0) SettingsDivider()
                val shownTarget = if (shortcut.type == VoiceShortcutType.OPEN_APP && shortcut.label.isNotBlank()) {
                    shortcut.label
                } else {
                    shortcut.target
                }
                val typeLabel = when (shortcut.type) {
                    VoiceShortcutType.NAVIGATION -> "Navigation"
                    VoiceShortcutType.OPEN_APP -> "Open app"
                    VoiceShortcutType.BROWSER_SEARCH -> "Browser search"
                }
                SettingsRow(
                    shortcut.keyword,
                    subtitle = "$typeLabel → $shownTarget",
                    trailing = {
                        Row {
                            IconButton(onClick = { editing = EditingShortcut(index, shortcut) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${shortcut.keyword}")
                            }
                            IconButton(onClick = { deleting = index }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${shortcut.keyword}")
                            }
                        }
                    },
                )
            }
            SettingsDivider()
        }
        SettingsRow(
            "Add voice shortcut",
            Modifier.clickable { editing = EditingShortcut(null, null) },
            icon = Icons.Filled.Add,
        )
    }

    editing?.let { edit ->
        ShortcutEditor(
            initial = edit.shortcut,
            current = shortcuts,
            existingIndex = edit.index,
            onDismiss = { editing = null },
            onSave = { entry ->
                VoiceShortcuts.save(context, entry, edit.index)
                editing = null
            },
        )
    }
    deleting?.let { index ->
        val victim = shortcuts.getOrNull(index)
        if (victim != null) {
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete voice shortcut?") },
                text = { Text("“${victim.keyword}” will stop working until you add it again.") },
                confirmButton = {
                    TextButton(onClick = { VoiceShortcuts.delete(context, index); deleting = null }) {
                        Text("Delete")
                    }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
            )
        } else {
            deleting = null
        }
    }
}

private data class EditingShortcut(val index: Int?, val shortcut: VoiceShortcut?)

@Composable
private fun ShortcutEditor(
    initial: VoiceShortcut?,
    current: List<VoiceShortcut>,
    existingIndex: Int?,
    onDismiss: () -> Unit,
    onSave: (VoiceShortcut) -> Unit,
) {
    var keyword by remember { mutableStateOf(initial?.keyword ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: VoiceShortcutType.NAVIGATION) }
    var target by remember { mutableStateOf(if (initial?.type == VoiceShortcutType.OPEN_APP) "" else initial?.target ?: "") }
    var appLabel by remember { mutableStateOf(initial?.label ?: "") }
    var appTarget by remember { mutableStateOf(initial?.target ?: "") }
    var pickingApp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add voice shortcut" else "Edit voice shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it; error = null },
                    label = { Text("Keyword (say this)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                VoiceShortcutType.entries.forEach { candidate ->
                    val label = when (candidate) {
                        VoiceShortcutType.NAVIGATION -> "Navigation — a saved address"
                        VoiceShortcutType.OPEN_APP -> "Open app — pick an app"
                        VoiceShortcutType.BROWSER_SEARCH -> "Browser search — a saved search term"
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(
                                selected = type == candidate,
                                onClick = { type = candidate; error = null },
                                role = Role.RadioButton,
                            ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == candidate, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                when (type) {
                    VoiceShortcutType.NAVIGATION -> OutlinedTextField(
                        value = target,
                        onValueChange = { target = it; error = null },
                        label = { Text("Address or place") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VoiceShortcutType.BROWSER_SEARCH -> OutlinedTextField(
                        value = target,
                        onValueChange = { target = it; error = null },
                        label = { Text("Search term") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VoiceShortcutType.OPEN_APP -> {
                        TextButton(onClick = { pickingApp = true }) {
                            Text(if (appLabel.isNotBlank()) "App: $appLabel (change)" else "Pick an app")
                        }
                        if (appTarget.isBlank()) {
                            Text(
                                "Pick an app to open — typing a package name is not offered.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = when (type) {
                    VoiceShortcutType.OPEN_APP -> VoiceShortcut(
                        VoiceShortcuts.normalize(keyword), type, appTarget, appLabel,
                    )
                    else -> VoiceShortcut(VoiceShortcuts.normalize(keyword), type, target.trim())
                }
                val problem = VoiceShortcuts.validate(keyword, entry.target, current, existingIndex)
                if (problem != null) {
                    error = problem
                } else {
                    onSave(entry)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingApp) {
        val entries = rememberAllEntries().filter { it.componentId != null }
        AlertDialog(
            onDismissRequest = { pickingApp = false },
            title = { Text("Pick an app") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (entries.isEmpty()) {
                        Text("No apps found yet.")
                    } else {
                        entries.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable {
                                        appTarget = entry.componentId ?: ""
                                        appLabel = entry.label
                                        pickingApp = false
                                    }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingApp = false }) { Text("Cancel") } },
        )
    }
}
