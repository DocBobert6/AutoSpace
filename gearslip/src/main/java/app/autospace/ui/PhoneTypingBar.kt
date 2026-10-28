package app.autospace.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import app.autospace.car.theme.GsIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.autospace.car.PhoneTyping

/**
 * While the car screen shows its keyboard, the phone shows the same field with its own keyboard
 * up, so a passenger can type comfortably. Each keystroke lands on the car screen at once.
 */
@Composable
fun PhoneTypingBar(modifier: Modifier = Modifier) {
    val field by PhoneTyping.field.collectAsState()
    val open = field ?: return
    var value by remember(open.id) { mutableStateOf(TextFieldValue(open.text, TextRange(open.text.length))) }
    // The car keyboard typed too: take its text, cursor at the end.
    if (open.text != value.text) value = TextFieldValue(open.text, TextRange(open.text.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(open.id) { runCatching { focus.requestFocus() } }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().imePadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GsIconButton(Icons.Filled.Close, "Close the car keyboard", PhoneTyping::dismiss)
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    PhoneTyping.type(it.text)
                },
                label = { Text("Type for the car screen") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { PhoneTyping.submit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            GsIconButton(Icons.Filled.Search, "Search on the car screen", PhoneTyping::submit)
        }
    }
}
