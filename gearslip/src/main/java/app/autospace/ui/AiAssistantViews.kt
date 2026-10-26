package app.autospace.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.autospace.car.AiAssistant
import app.autospace.car.AiKeyStore
import app.autospace.car.AssistantPreview
import app.autospace.car.GeminiClient
import app.autospace.car.VoiceShortcuts
import app.autospace.speech.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone Settings section for the AI assistant (r25). This is the ONLY place the Gemini
 * API key is entered, tested, or removed — keys are never typed on a head unit, and the
 * car side only ever shows a read-only on/off line. The key field is password-masked,
 * the stored value is never displayed (not even masked — masked forms leak length), and
 * every status line says only whether a key is saved and whether it tested valid.
 */
@Composable
internal fun AiAssistantSection(onOpenPreview: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { AiKeyStore.ensureLoaded(context) }
    val enabled by AiKeyStore.enabled.collectAsState()
    val keyState by AiKeyStore.keyState.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var saveNote by remember { mutableStateOf<String?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    fun enable() {
        if (!AiKeyStore.noticeAcked(context)) {
            showNotice = true
        } else {
            AiKeyStore.setEnabled(context, true)
        }
    }

    SettingsSection(
        "AI assistant",
        footer = "AI answers use your own Gemini API key and are off by default. Local commands " +
            "always run first and never need the network; the AI is only a fallback when no " +
            "local command matches, and it never acts without your confirmation.",
    ) {
        SettingsRow(
            "AI answers",
            subtitle = if (enabled) "On — AI fallback is available" else "Off — the local assistant only",
            trailing = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on -> if (on) enable() else AiKeyStore.setEnabled(context, false) },
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            "Gemini API key",
            subtitle = when (keyState) {
                AiKeyStore.KeyState.NO_KEY -> "No key saved"
                AiKeyStore.KeyState.SAVED_UNTESTED -> "Key saved, not yet tested"
                AiKeyStore.KeyState.VALID -> "Key valid"
                AiKeyStore.KeyState.INVALID -> "Key invalid — check the key"
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; saveNote = null },
                label = { Text("Paste your Gemini API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (AiKeyStore.saveKey(context, keyInput)) {
                        keyInput = ""
                        saveNote = "Key saved. Test it below to confirm it works."
                    } else {
                        saveNote = "Enter a key first."
                    }
                }) { Text("Save key") }
                OutlinedButton(
                    enabled = !testing && keyState != AiKeyStore.KeyState.NO_KEY,
                    onClick = {
                        testing = true
                        testNote = null
                        scope.launch {
                            val verdict = withContext(Dispatchers.IO) { AiKeyStore.testKey(context) }
                            testNote = when (verdict) {
                                GeminiClient.KeyCheck.VALID -> "Key valid."
                                GeminiClient.KeyCheck.INVALID -> "Key invalid — check the key and try again."
                                GeminiClient.KeyCheck.UNKNOWN -> "Couldn't reach Google — check the network and try again."
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test key") }
                OutlinedButton(
                    enabled = keyState != AiKeyStore.KeyState.NO_KEY,
                    onClick = { showRemoveConfirm = true },
                ) { Text("Remove key") }
            }
            saveNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            testNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Model: ${AiAssistant.MODEL_ID} (${AiAssistant.API_VERSION}, ${AiAssistant.API_HOST})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsDivider()
        SettingsRow(
            "Privacy notice",
            subtitle = "What is sent to Google, and what never is",
            Modifier.clickable { showNotice = true },
        )
        SettingsDivider()
        SettingsRow(
            "Assistant preview",
            subtitle = "Test the assistant without a car session — routing traces and a test suite",
            Modifier.clickable { onOpenPreview() },
        )
    }

    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text("AI answers: what happens") },
            text = { Text(AiAssistant.PRIVACY_NOTICE) },
            confirmButton = {
                TextButton(onClick = {
                    AiKeyStore.ackNotice(context)
                    showNotice = false
                    AiKeyStore.setEnabled(context, true)
                }) { Text("Turn on AI answers") }
            },
            dismissButton = { TextButton(onClick = { showNotice = false }) { Text("Not now") } },
        )
    }
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove the AI key?") },
            text = { Text("The stored key is wiped and AI answers turn off. You can paste a key again anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    AiKeyStore.removeKey(context)
                    keyInput = ""
                    testNote = null
                    saveNote = null
                    showRemoveConfirm = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Keep it") } },
        )
    }
}

/**
 * Settings > Assistant preview (r25 §8): the assistant test suite, working without a car
 * session and without a head unit. Everything here is a dry run: a classified utterance
 * shows its routing trace and its would-be spoken text, and Accept/Decline on a would-be
 * confirmation only advance the trace — they never execute. Read-only queries are labeled
 * as runnable, and nothing else is. The screen never shows the API key.
 */
@Composable
internal fun AssistantPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) {
        AiKeyStore.ensureLoaded(context)
        VoiceShortcuts.ensureLoaded(context)
    }
    val keywords by VoiceShortcuts.shortcuts.collectAsState()
    val aiOn by AiKeyStore.enabled.collectAsState()
    val keyState by AiKeyStore.keyState.collectAsState()

    var input by remember { mutableStateOf("") }
    var trace by remember { mutableStateOf<AssistantPreview.Trace?>(null) }
    var traceDecision by remember { mutableStateOf<String?>(null) }
    var micNote by remember { mutableStateOf<String?>(null) }
    var playNote by remember { mutableStateOf<String?>(null) }
    var suite by remember { mutableStateOf<AssistantPreview.SuiteResult?>(null) }
    var confirmResults by remember { mutableStateOf<List<Pair<AssistantPreview.ConfirmationCase, Boolean>>?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) {
                input = heard
                micNote = null
            } else {
                micNote = "Didn't catch that."
            }
        } else {
            micNote = "Voice input canceled."
        }
    }

    fun aiGates(): Boolean =
        aiOn && keyState != AiKeyStore.KeyState.NO_KEY && GeminiClient.networkAvailable(context)

    fun classify() {
        trace = AssistantPreview.classify(input, keywords, aiGates())
        traceDecision = null
        playNote = null
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Assistant preview", style = MaterialTheme.typography.titleLarge)
        }
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
            Text(
                AssistantPreview.DRY_RUN_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text(
            "Type an utterance, or speak one with the phone mic. The trace shows where it " +
                "would route, what it would say, and whether it would ask for confirmation — " +
                "without doing anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Say something") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    micNote = "No speech recognizer on this phone — type instead."
                    return@IconButton
                }
                runCatching {
                    micLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        },
                    )
                }.onFailure { micNote = "Voice input isn't available — type instead." }
            }) { Icon(Icons.Filled.Mic, contentDescription = "Speak an utterance") }
        }
        micNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(enabled = input.isNotBlank(), onClick = ::classify, modifier = Modifier.fillMaxWidth()) {
            Text("Show routing trace")
        }
        trace?.let { t ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Heard: “${t.normalized}”", style = MaterialTheme.typography.bodyMedium)
                    Text("Routes to: ${t.handler} — ${t.detail}", style = MaterialTheme.typography.bodyMedium)
                    if (t.params.isNotBlank()) Text("Parameters: ${t.params}", style = MaterialTheme.typography.bodyMedium)
                    if (t.handler == AssistantPreview.Handler.AI) {
                        Text("AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        if (t.wouldConfirm) "Would ask for confirmation before acting." else "No confirmation needed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Would say: “${t.spoken}”", style = MaterialTheme.typography.bodyMedium)
                    Text("Executes: never from preview.", style = MaterialTheme.typography.bodyMedium)
                    if (t.wouldConfirm) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { traceDecision = "Accepted — trace only, nothing executed." }) { Text("Accept") }
                            OutlinedButton(onClick = { traceDecision = "Declined — nothing executed." }) { Text("Decline") }
                        }
                    }
                    traceDecision?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = {
                        playNote = null
                        val started = VoiceEngine.play(context, t.spoken, onDone = {}, onSilent = {
                            playNote = "Couldn't speak that reply — the voice render produced no audio."
                        })
                        if (!started) playNote = "No voice downloaded yet. Download Kokoro or Piper in Settings first."
                    }) { Text("Play spoken text") }
                    playNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Text("Canned test suite", style = MaterialTheme.typography.titleMedium)
        Text(
            "Utterances with expected routing. AI cases need AI on, a saved key, and a network — " +
                "otherwise they count as skipped, never failed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
            suite = AssistantPreview.runSuite(AssistantPreview.FIXTURE_KEYWORDS, aiGates())
            confirmResults = AssistantPreview.runConfirmationCases()
        }, modifier = Modifier.fillMaxWidth()) { Text("Run all tests") }
        suite?.let { s ->
            Text(
                "${s.passed} passed, ${s.failed} failed, ${s.skipped} skipped, of ${s.total} cases.",
                style = MaterialTheme.typography.bodyMedium,
            )
            s.results.forEach { r ->
                val mark = when {
                    r.skipped -> "SKIPPED"
                    r.passed -> "PASS"
                    else -> "FAIL"
                }
                Text(
                    "$mark — “${r.case.utterance}” (expected ${r.case.expected}" +
                        (if (r.skipped) "" else ", got ${r.actual}") + ") — ${r.case.note}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        confirmResults?.let { results ->
            Text(
                "Confirmation cases: ${results.count { it.second }} of ${results.size} passed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            results.filter { !it.second }.forEach { (case, _) ->
                Text(
                    "FAIL — reply “${case.reply}” expired=${case.expired} (expected ${case.expected})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text("Key test", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = !testing && keyState != AiKeyStore.KeyState.NO_KEY,
            onClick = {
                testing = true
                testNote = null
                scope.launch {
                    val verdict = withContext(Dispatchers.IO) { AiKeyStore.testKey(context) }
                    testNote = when (verdict) {
                        GeminiClient.KeyCheck.VALID -> "Key valid."
                        GeminiClient.KeyCheck.INVALID -> "Key invalid — check the key and try again."
                        GeminiClient.KeyCheck.UNKNOWN -> "Couldn't reach Google — check the network and try again."
                    }
                    testing = false
                }
            },
        ) { Text(if (testing) "Testing…" else "Test key") }
        testNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
