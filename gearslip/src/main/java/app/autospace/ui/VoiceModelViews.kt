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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.autospace.speech.SpeechModels
import app.autospace.speech.SpeechModels.Model
import app.autospace.speech.SpeechModels.State
import androidx.compose.ui.text.font.FontWeight
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
        SpokenEngineRow(VoiceModels.Engine.KOKORO, kokoroState)
        SettingsDivider()
        SpokenEngineRow(VoiceModels.Engine.PIPER, piperState)
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
                "effect on the next spoken prompt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Grouped by language, in the model's sid order, so the list reads as
        // voices rather than 53 undifferentiated ids — same grouping as car Settings.
        var lastLanguage: String? = null
        VoiceModels.KOKORO_VOICES.forEach { voice ->
            if (voice.language != lastLanguage) {
                lastLanguage = voice.language
                Text(
                    voice.language,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(voice == selectedVoice, role = Role.RadioButton) { VoiceModels.selectVoice(context, voice) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(voice == selectedVoice, onClick = null, modifier = Modifier.padding(8.dp))
                Text(
                    voice.id + if (voice == VoiceModels.DEFAULT_VOICE) " (default)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (voice == selectedVoice) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SpokenEngineRow(engine: VoiceModels.Engine, state: VoiceModels.State) {
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
                        is VoiceModels.State.Downloading -> "Downloading, ${s.percent}%"
                        is VoiceModels.State.Failed -> "Download failed: ${s.reason}"
                        VoiceModels.State.Missing -> engine.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
