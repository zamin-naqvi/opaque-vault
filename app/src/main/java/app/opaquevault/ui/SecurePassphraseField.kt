package app.opaquevault.ui

import android.graphics.Typeface
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import java.lang.ref.WeakReference
import kotlin.math.ln
import kotlin.math.max

/**
 * Keeps passphrases out of Compose String state. The Editable is copied straight into a CharArray,
 * then cleared. Android/IME internals can still copy input; no widget can defend against a
 * malicious keyboard or a fully compromised OS.
 */
class SecureTextHandle internal constructor() {
    private var field = WeakReference<EditText>(null)

    internal fun attach(editText: EditText) {
        field = WeakReference(editText)
    }

    internal fun detach(editText: EditText) {
        if (field.get() === editText) field.clear()
    }

    fun takeAndClear(): CharArray {
        val editText = field.get() ?: return CharArray(0)
        val editable = editText.text ?: return CharArray(0)
        val result = CharArray(editable.length) { index -> editable[index] }
        editable.clear()
        editText.clearComposingText()
        return result
    }

    fun clear() {
        field.get()?.let { editText ->
            editText.text?.clear()
            editText.clearComposingText()
        }
    }

    fun insert(character: Char) {
        val editText = field.get() ?: return
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceIn(0, editable.length)
        val end = editText.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(start, end), maxOf(start, end), character.toString())
    }

    fun backspace() {
        val editText = field.get() ?: return
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceIn(0, editable.length)
        val end = editText.selectionEnd.coerceIn(0, editable.length)
        when {
            start != end -> editable.delete(minOf(start, end), maxOf(start, end))
            start > 0 -> editable.delete(start - 1, start)
        }
    }

    fun finishEditing() {
        field.get()?.clearFocus()
    }
}

@Composable
fun rememberSecureTextHandle(): SecureTextHandle = remember { SecureTextHandle() }

enum class PassphraseStrength(val label: String, val fraction: Float) {
    EMPTY("", 0f),
    WEAK("Weak", 0.25f),
    FAIR("Fair", 0.5f),
    STRONG("Strong", 0.75f),
    EXCELLENT("Excellent", 1f),
}

@Composable
fun SecurePassphraseField(
    label: String,
    handle: SecureTextHandle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    showStrength: Boolean = false,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    onImeAction: () -> Unit = {},
    onLengthChanged: (Int) -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var strength by remember { mutableStateOf(PassphraseStrength.EMPTY) }
    val currentOnImeAction by rememberUpdatedState(onImeAction)
    val currentOnLengthChanged by rememberUpdatedState(onLengthChanged)

    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val borderColor = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    val labelColor = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val actionModeBlocker = remember {
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) = Unit
        }
    }
    var attachedField by remember { mutableStateOf<EditText?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .border(if (focused || isError) 2.dp else 1.dp, borderColor, OutlinedTextFieldDefaults.shape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor)
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        object : EditText(context) {
                            override fun onTextContextMenuItem(id: Int): Boolean = false

                            // Not a text editor as far as Android is concerned: the system keyboard
                            // can never open for it or receive a single character. Input comes only
                            // from the private keyboard below.
                            override fun onCheckIsTextEditor(): Boolean = false

                            override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? = null
                        }.apply {
                            attachedField = this
                            handle.attach(this)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setPadding(0, 4, 0, 0)
                            setSingleLine(true)
                            showSoftInputOnFocus = false
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                            applyVisibility(this, visible = false)
                            var options = imeAction or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                // Incognito keyboard: ask the IME not to learn or store what is typed.
                                options = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            }
                            imeOptions = options
                            isSaveEnabled = false
                            isLongClickable = false
                            setTextIsSelectable(false)
                            customSelectionActionModeCallback = actionModeBlocker
                            customInsertionActionModeCallback = actionModeBlocker
                            filters = arrayOf(InputFilter.LengthFilter(MAX_LENGTH))
                            setOnFocusChangeListener { _, hasFocus -> focused = hasFocus }
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == imeAction) {
                                    currentOnImeAction()
                                    true
                                } else {
                                    false
                                }
                            }
                            doAfterTextChanged { text ->
                                currentOnLengthChanged(text?.length ?: 0)
                                if (showStrength) strength = estimateStrength(text ?: "")
                            }
                        }
                    },
                    update = { field ->
                        field.isEnabled = enabled
                        field.setTextColor(textColor)
                        if (field.tag != visible) applyVisibility(field, visible)
                    },
                )
            }
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                )
            }
        }
        if (showStrength && strength != PassphraseStrength.EMPTY) {
            LinearProgressIndicator(
                progress = { strength.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
                color = strengthColor(strength),
                strokeCap = StrokeCap.Round,
            )
            Text(
                text = "Strength: ${strength.label}",
                style = MaterialTheme.typography.labelMedium,
                color = strengthColor(strength),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            )
        }
        if (focused) {
            SecureBuiltInKeyboard(
                handle = handle,
                actionLabel = if (imeAction == EditorInfo.IME_ACTION_NEXT) "Next" else "Done",
                onAction = {
                    currentOnImeAction()
                    handle.finishEditing()
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    DisposableEffect(handle) {
        onDispose {
            attachedField?.let(handle::detach)
            handle.clear()
        }
    }
}

@Composable
private fun SecureBuiltInKeyboard(
    handle: SecureTextHandle,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var uppercase by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val letterRows = if (symbols) {
        listOf("1234567890", "!@#$%^&*()", "-_=+.,?/\"")
    } else {
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "Private keyboard · input is not sent to the installed system keyboard",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        letterRows.forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!symbols && rowIndex == 2) {
                    SecureKeyboardIconKey(
                        icon = Icons.Rounded.ArrowUpward,
                        contentDescription = "Shift",
                        selected = uppercase,
                        onClick = { uppercase = !uppercase },
                        modifier = Modifier.weight(1.35f),
                    )
                }
                row.forEach { raw ->
                    val character = if (uppercase && raw.isLetter()) raw.uppercaseChar() else raw
                    SecureKeyboardTextKey(
                        label = character.toString(),
                        onClick = {
                            handle.insert(character)
                            if (uppercase) uppercase = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowIndex == 2) {
                    SecureKeyboardIconKey(
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = "Backspace",
                        onClick = handle::backspace,
                        modifier = Modifier.weight(1.35f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SecureKeyboardTextKey(
                label = if (symbols) "ABC" else "123",
                onClick = { symbols = !symbols; uppercase = false },
                modifier = Modifier.weight(1.5f),
            )
            SecureKeyboardTextKey(
                label = "space",
                onClick = { handle.insert(' ') },
                modifier = Modifier.weight(4.5f),
            )
            SecureKeyboardIconKey(
                icon = Icons.Rounded.KeyboardHide,
                contentDescription = actionLabel,
                onClick = onAction,
                modifier = Modifier.weight(1.5f),
            )
        }
    }
}

@Composable
private fun SecureKeyboardTextKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun SecureKeyboardIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun strengthColor(strength: PassphraseStrength): Color = when (strength) {
    PassphraseStrength.WEAK -> MaterialTheme.colorScheme.error
    PassphraseStrength.FAIR -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun applyVisibility(field: EditText, visible: Boolean) {
    val selectionStart = field.selectionStart
    val selectionEnd = field.selectionEnd
    // TYPE_NULL: no IME connection is ever offered; focus and the private keyboard still work.
    field.inputType = InputType.TYPE_NULL
    field.isFocusable = true
    field.isFocusableInTouchMode = true
    field.transformationMethod = if (visible) {
        HideReturnsTransformationMethod.getInstance()
    } else {
        PasswordTransformationMethod.getInstance()
    }
    field.typeface = Typeface.DEFAULT
    if (selectionStart >= 0) field.setSelection(selectionStart, selectionEnd)
    field.tag = visible
}

/**
 * Rough guidance only, computed straight from the Editable so no String copy is made. Random words
 * are scored per word; other input by character pool, discounted for human-chosen patterns.
 */
private fun estimateStrength(text: CharSequence): PassphraseStrength {
    if (text.isEmpty()) return PassphraseStrength.EMPTY
    var lower = false
    var upper = false
    var digit = false
    var symbol = false
    var spaces = 0
    for (char in text) {
        when {
            char == ' ' -> spaces++
            char.isLowerCase() -> lower = true
            char.isUpperCase() -> upper = true
            char.isDigit() -> digit = true
            else -> symbol = true
        }
    }
    val pool = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digit) 10 else 0) +
        (if (symbol) 33 else 0) + (if (spaces > 0) 1 else 0)
    val characterBits = text.length * (ln(max(pool, 10).toDouble()) / ln(2.0)) * 0.6
    val wordBits = if (spaces >= 3) (spaces + 1) * 11.0 else 0.0
    val bits = max(characterBits, wordBits)
    return when {
        text.length < 16 || bits < 50 -> PassphraseStrength.WEAK
        bits < 70 -> PassphraseStrength.FAIR
        bits < 90 -> PassphraseStrength.STRONG
        else -> PassphraseStrength.EXCELLENT
    }
}

private const val MAX_LENGTH = 256
