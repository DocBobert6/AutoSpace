package app.autospace.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The text field the car keyboard is typing into, shared with the phone so a passenger can type
 * on the phone's own keyboard instead. Whichever [CarKeyboard] is on screen registers here; the
 * phone app shows the same text while one is open, and its edits go back through the car field's
 * own callbacks, so the car screen can't tell which keyboard typed.
 */
object PhoneTyping {

    class Field internal constructor(
        val id: Int,
        val text: String,
        internal val onTextChange: (String) -> Unit,
        internal val onSubmit: () -> Unit,
        internal val onDismiss: (() -> Unit)?,
    )

    private val fieldFlow = MutableStateFlow<Field?>(null)

    /** The car's open field, or null when no car keyboard is showing. */
    val field: StateFlow<Field?> = fieldFlow.asStateFlow()

    private var nextId = 0

    internal fun open(text: String, onTextChange: (String) -> Unit, onSubmit: () -> Unit, onDismiss: (() -> Unit)?): Int {
        val id = ++nextId
        fieldFlow.value = Field(id, text, onTextChange, onSubmit, onDismiss)
        return id
    }

    /** The car field's text changed, from either keyboard. */
    internal fun carText(id: Int, text: String) {
        val f = fieldFlow.value ?: return
        if (f.id == id && f.text != text) fieldFlow.value = Field(id, text, f.onTextChange, f.onSubmit, f.onDismiss)
    }

    internal fun close(id: Int) {
        if (fieldFlow.value?.id == id) fieldFlow.value = null
    }

    /** From the phone: typed text goes to the car field as if typed there. */
    fun type(text: String) {
        fieldFlow.value?.onTextChange?.invoke(text)
    }

    fun submit() {
        fieldFlow.value?.onSubmit?.invoke()
    }

    /** Closes the car keyboard where it can be closed; elsewhere only the phone's field goes. */
    fun dismiss() {
        val f = fieldFlow.value ?: return
        f.onDismiss?.invoke() ?: close(f.id)
    }
}
