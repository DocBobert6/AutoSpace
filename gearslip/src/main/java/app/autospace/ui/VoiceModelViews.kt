package app.autospace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.autospace.speech.SpeechModels
import app.autospace.speech.SpeechModels.Model
import app.autospace.speech.SpeechModels.State
import androidx.compose.ui.text.font.FontWeight
import app.autospace.car.CarSettings
import app.autospace.speech.VoiceEngine
import app.autospace.speech.VoiceModels

/**
 * Which speech model the voice assistant listens with, and downloading it. Each is fetched once
 * from sherpa-onnx's author ([SpeechModels] says where); nothing is sent anywhere while listening.
 */
@Composable
internal fun VoiceModelSection() {
    val context = LocalContext.current
    remember { SpeechModels.init(context) }
    val selected by SpeechModels.selected.collectAsState()
    val final by SpeechModels.final.collectAsState()
    SettingsSection(title = "Voice: live words") {
        Text(
            "The voice assistant turns speech into text on this phone, and nothing you say leaves it. " +
                "It needs two models, about 270 MB together, downloaded once. " +
                "This one writes your words as you speak.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Model.entries.filter { it.role == SpeechModels.Role.LIVE }.forEachIndexed { i, model ->
            if (i > 0) SettingsDivider()
            ModelRow(model, model == selected)
        }
    }
    SettingsSection(title = "Voice: final words") {
        Text(
            "When you stop talking, this model reads the whole phrase again. It catches names and " +
                "places the live model misses.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        val context = LocalContext.current
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(final == null, role = Role.RadioButton) { SpeechModels.selectFinal(context, null) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(final == null, onClick = null, modifier = Modifier.padding(8.dp))
            Column {
                Text("None", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep the live words. Fastest, least accurate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Model.entries.filter { it.role == SpeechModels.Role.FINAL }.forEach { model ->
            SettingsDivider()
            ModelRow(model, model == final)
        }
    }
}

/**
 * The voice AutoSpace speaks WITH (text into speech) — the opposite direction from
 * [VoiceModelSection] above, which is the voice it listens with (speech into text).
 * This is the phone-side twin of the car Settings "Voice" section: same
 * [VoiceModels] state, same downloads, same picker, so a choice or download in
 * either place shows in the other. Kokoro speaks first, in the voice picked
 * below; Piper (Amy) speaks only when Kokoro isn't downloaded or fails.
 * Nothing here touches the listening models, and nothing in the listening
 * sections touches this one.
 */
@Composable
internal fun SpokenVoiceSection() {
    val context = LocalContext.current
    remember { VoiceModels.init(context) }
    val kokoroState by VoiceModels.state(VoiceModels.Engine.KOKORO).collectAsState()
    val piperState by VoiceModels.state(VoiceModels.Engine.PIPER).collectAsState()
    val selectedVoice by VoiceModels.selectedVoice.collectAsState()
    // Voice preview: same rules as the car picker — the key of the voice
    // currently previewing ("kokoro:<id>" or "piper"), or null; previewing
    // never writes the selection, only plays the fixed GPS phrase.
    var previewing by remember { mutableStateOf<String?>(null) }
    val previewingNow = rememberUpdatedState(previewing)
    // Round 3: a failed preview used to end as pure silence, so the
    // row shows the recorded failure stage instead of going quiet.
    var previewError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { if (previewingNow.value != null) VoiceEngine.stop() }
    }
    fun togglePreview(key: String, voice: VoiceModels.KokoroVoice?) {
        if (previewing == key) {
            VoiceEngine.stop()
            previewing = null
            return
        }
        previewError = null
        val started = VoiceEngine.playPreview(
            context, VoiceModels.PREVIEW_PHRASE, voice,
            onDone = { if (previewing == key) previewing = null },
            onError = { reason ->
                previewError = reason
                if (previewing == key) previewing = null
            },
        )
        previewing = if (started) key else null
    }
    SettingsSection(title = "Voice: spoken voice") {
        Text(
            "The voice AutoSpace speaks with, entirely on this phone — no system voice, " +
                "no other engines. Kokoro speaks first, in the voice picked below; Piper " +
                "(Amy) speaks only when Kokoro isn't downloaded or fails. Kokoro is " +
                "Apache-2.0 (hexgrad); Piper's voice is MIT (rhasspy/piper-voices). " +
                "Downloads are one-time and live in the app's own storage, not the APK.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        SpokenEngineRow(VoiceModels.Engine.KOKORO, kokoroState, previewing = null, onPreview = null)
        SettingsDivider()
        SpokenEngineRow(
            VoiceModels.Engine.PIPER, piperState,
            previewing = previewing,
            onPreview = { togglePreview("piper", null) },
        )
        previewError?.let { reason ->
            Text(
                "Preview failed: $reason.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (kokoroState is VoiceModels.State.Missing && piperState is VoiceModels.State.Missing) {
            Text(
                "No voice downloaded yet: assistant replies and spoken directions will stay silent " +
                    "until Kokoro or Piper is downloaded above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    SettingsSection(title = "Kokoro voice") {
        Text(
            "Applies to Kokoro speech; Piper always speaks as Amy. A new choice takes " +
                "effect on the next spoken prompt. Every voice previews with " +
                "“${VoiceModels.PREVIEW_PHRASE}” without changing your choice; " +
                "previews need Kokoro downloaded, so a missing download leaves " +
                "its Preview button disabled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Featured page first, More voices page second — same list, same
        // selection, no removal; the default (am_adam) is on the second page.
        var showMore by remember { mutableStateOf(false) }
        if (!showMore) {
            VoiceModels.FEATURED_VOICES.forEach { voice ->
                KokoroVoiceRowPhone(
                    voice = voice,
                    selected = voice == selectedVoice,
                    previewing = previewing == "kokoro:${voice.id}",
                    previewEnabled = kokoroState is VoiceModels.State.Ready,
                    onSelect = { VoiceModels.selectVoice(context, voice) },
                    onPreview = { togglePreview("kokoro:${voice.id}", voice) },
                )
            }
            OutlinedButton(
                onClick = { showMore = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("More voices")
            }
            Text(
                "49 more Kokoro voices, including the default.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            OutlinedButton(
                onClick = { showMore = false },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Back to featured voices")
            }
            // Grouped by language, in the model's sid order — same grouping
            // as car Settings.
            var lastLanguage: String? = null
            VoiceModels.MORE_VOICES.forEach { voice ->
                if (voice.language != lastLanguage) {
                    lastLanguage = voice.language
                    Text(
                        voice.language,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                KokoroVoiceRowPhone(
                    voice = voice,
                    selected = voice == selectedVoice,
                    previewing = previewing == "kokoro:${voice.id}",
                    previewEnabled = kokoroState is VoiceModels.State.Ready,
                    onSelect = { VoiceModels.selectVoice(context, voice) },
                    onPreview = { togglePreview("kokoro:${voice.id}", voice) },
                )
            }
        }
    }
    // Hotword: the same persisted switch as car Settings, so either place reflects
    // the other. The listener itself enforces car-session-only listening and
    // suspension while busy or on a call; this is only the switch.
    SettingsSection(title = "Voice: hotword") {
        val hotword by CarSettings.hotword.collectAsState()
        val hotwordPhoneMic by CarSettings.hotwordPhoneMic.collectAsState()
        val liveModel by SpeechModels.selected.collectAsState()
        val liveState by SpeechModels.state(liveModel).collectAsState()
        if (hotword && liveState !is State.Ready) {
            Text(
                "The Kroko listening model isn't downloaded yet, so hotword listening " +
                    "is waiting — it starts on its own once Kroko is downloaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Listen for “Hey AutoSpace” on the phone microphone",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Hotword listening only: uses the phone's microphone even when " +
                        "the car has one. Turn this on if the car's microphone doesn't " +
                        "work and “Hey AutoSpace” never answers. Button-press voice " +
                        "commands, calls, and everything else are unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = hotwordPhoneMic, onCheckedChange = { CarSettings.setHotwordPhoneMic(it) })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Listen for “Hey AutoSpace”", style = MaterialTheme.typography.titleMedium)
                Text(
                    "While connected to the car, AutoSpace listens for “Hey AutoSpace” " +
                        "(or “Hey Gearslip”) and opens the assistant without a button press. " +
                        "Off by default. It needs the Kroko listening model downloaded, " +
                        "and nothing you say leaves the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = hotword, onCheckedChange = { CarSettings.setHotword(it) })
        }
    }
}

/** One Kokoro voice row: radio selection plus a Preview/Stop button. */
@Composable
private fun KokoroVoiceRowPhone(
    voice: VoiceModels.KokoroVoice,
    selected: Boolean,
    previewing: Boolean,
    previewEnabled: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton) { onSelect() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(
            voice.id + if (voice == VoiceModels.DEFAULT_VOICE) " (default)" else "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPreview, enabled = previewEnabled) {
            Text(if (previewing) "Stop" else "Preview")
        }
    }
}
/**
 * Call speaker: the same persisted switch as car Settings, so either place reflects the
 * other. Calls only — this section is about a call-audio choice, not a voice model, and
 * it deliberately sits apart from the listening/spoken sections for that reason.
 */
@Composable
internal fun CallSpeakerSection() {
    SettingsSection(title = "Calls: speaker") {
        val callSpeaker by CarSettings.callSpeaker.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Answer calls on the phone speaker", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When a call starts, its audio goes to the phone's speaker instead " +
                        "of the car's Bluetooth route, so the caller hears you through " +
                        "the phone's microphone. Phone calls only — not voice commands, " +
                        "the assistant, or music. Off by default, applies from the next " +
                        "call, and the speaker is restored when the call ends. If the " +
                        "car's Bluetooth takes the call back, this cannot override it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = callSpeaker, onCheckedChange = { CarSettings.setCallSpeaker(it) })
        }
    }
}

@Composable
private fun SpokenEngineRow(
    engine: VoiceModels.Engine,
    state: VoiceModels.State,
    previewing: String?,
    onPreview: (() -> Unit)?,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${engine.label} · ${engine.downloadMb} MB", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val s = state) {
                        VoiceModels.State.Ready -> "Downloaded. ${engine.detail}"
                        is VoiceModels.State.Downloading -> "Downloading, ${s.percent}%" + (if (s.detail.isNotEmpty()) " — ${s.detail}" else "")
                        is VoiceModels.State.Failed -> "Download failed: ${s.reason}"
                        VoiceModels.State.Missing -> engine.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Piper's only preview button: its engine row is its voice row
                // (Kokoro's previews live on each voice row instead).
                if (onPreview != null) {
                    TextButton(onClick = onPreview, enabled = state is VoiceModels.State.Ready) {
                        Text(if (previewing == "piper") "Stop" else "Preview")
                    }
                }
                when (state) {
                    VoiceModels.State.Ready -> TextButton(onClick = { VoiceModels.delete(context, engine) }) { Text("Delete") }
                    is VoiceModels.State.Downloading -> TextButton(onClick = { VoiceModels.delete(context, engine) }) { Text("Cancel") }
                    else -> OutlinedButton(onClick = { VoiceModels.download(context, engine) }) { Text("Download") }
                }
            }
        }
        (state as? VoiceModels.State.Downloading)?.let {
            LinearProgressIndicator(
                progress = { it.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(model: Model, chosen: Boolean) {
    val context = LocalContext.current
    val state by SpeechModels.state(model).collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .selectable(chosen, role = Role.RadioButton) { SpeechModels.select(context, model) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(chosen, onClick = null, modifier = Modifier.padding(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${model.label} · ${model.downloadMb} MB", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val s = state) {
                        State.Ready -> "Downloaded. ${model.detail}"
                        is State.Downloading -> "Downloading, ${s.percent}%"
                        is State.Failed -> "Download failed: ${s.reason}"
                        State.Missing -> model.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    State.Ready -> TextButton(onClick = { SpeechModels.delete(context, model) }) { Text("Delete") }
                    is State.Downloading -> TextButton(onClick = { SpeechModels.delete(context, model) }) { Text("Cancel") }
                    else -> OutlinedButton(onClick = { SpeechModels.download(context, model) }) { Text("Download") }
                }
            }
        }
        (state as? State.Downloading)?.let {
            LinearProgressIndicator(
                progress = { it.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, top = 4.dp),
            )
        }
    }
}
