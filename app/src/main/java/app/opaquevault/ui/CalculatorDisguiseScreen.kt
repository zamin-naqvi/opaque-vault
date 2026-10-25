package app.opaquevault.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import kotlin.math.abs

/** A fully local calculator. Holding the equals key only reveals the real authentication screen. */
@Composable
fun CalculatorDisguiseScreen(onRevealAuthentication: () -> Unit) {
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var pendingOperation by remember { mutableStateOf<String?>(null) }
    var replaceInput by remember { mutableStateOf(true) }

    fun currentValue(): Double = display.toDoubleOrNull() ?: 0.0

    fun formatted(value: Double): String {
        if (!value.isFinite()) return "Error"
        val plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return when {
            plain.length <= 16 -> plain
            abs(value) < 1e14 -> plain.take(16)
            else -> "%.8e".format(value)
        }
    }

    fun calculate(left: Double, operation: String, right: Double): Double = when (operation) {
        "+" -> left + right
        "−" -> left - right
        "×" -> left * right
        "÷" -> if (right == 0.0) Double.NaN else left / right
        else -> right
    }

    fun inputDigit(digit: String) {
        if (display == "Error" || replaceInput) {
            display = if (digit == ".") "0." else digit
            replaceInput = false
            return
        }
        if (digit == "." && display.contains('.')) return
        if (display.length < 16) display += digit
    }

    fun selectOperation(operation: String) {
        val right = currentValue()
        val left = accumulator
        val prior = pendingOperation
        accumulator = if (left != null && prior != null && !replaceInput) {
            calculate(left, prior, right).also { display = formatted(it) }
        } else {
            right
        }
        pendingOperation = operation
        replaceInput = true
    }

    fun equals() {
        val left = accumulator
        val operation = pendingOperation
        if (left != null && operation != null) {
            display = formatted(calculate(left, operation, currentValue()))
        }
        accumulator = null
        pendingOperation = null
        replaceInput = true
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Calculator",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Text(
                    display,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CalculatorRow(
                listOf(
                    CalculatorButton("C", Modifier.weight(1f)) {
                        display = "0"; accumulator = null; pendingOperation = null; replaceInput = true
                    },
                    CalculatorButton("±", Modifier.weight(1f)) {
                        display = formatted(-currentValue()); replaceInput = true
                    },
                    CalculatorButton("%", Modifier.weight(1f)) {
                        display = formatted(currentValue() / 100.0); replaceInput = true
                    },
                    CalculatorButton("÷", Modifier.weight(1f), operation = true) { selectOperation("÷") },
                ),
            )
            CalculatorRow(
                listOf("7", "8", "9").map { digit ->
                    CalculatorButton(digit, Modifier.weight(1f)) { inputDigit(digit) }
                } + CalculatorButton("×", Modifier.weight(1f), operation = true) { selectOperation("×") },
            )
            CalculatorRow(
                listOf("4", "5", "6").map { digit ->
                    CalculatorButton(digit, Modifier.weight(1f)) { inputDigit(digit) }
                } + CalculatorButton("−", Modifier.weight(1f), operation = true) { selectOperation("−") },
            )
            CalculatorRow(
                listOf("1", "2", "3").map { digit ->
                    CalculatorButton(digit, Modifier.weight(1f)) { inputDigit(digit) }
                } + CalculatorButton("+", Modifier.weight(1f), operation = true) { selectOperation("+") },
            )
            val haptics = LocalHapticFeedback.current
            CalculatorRow(
                listOf(
                    CalculatorButton("0", Modifier.weight(2f)) { inputDigit("0") },
                    CalculatorButton(".", Modifier.weight(1f)) { inputDigit(".") },
                    CalculatorButton(
                        label = "=",
                        modifier = Modifier.weight(1f),
                        operation = true,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRevealAuthentication()
                        },
                        onClick = ::equals,
                    ),
                ),
            )
        }
    }
}

private data class CalculatorButton(
    val label: String,
    val modifier: Modifier,
    val operation: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
private fun CalculatorRow(buttons: List<CalculatorButton>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        buttons.forEach { button ->
            CalculatorKey(
                label = button.label,
                operation = button.operation,
                onClick = button.onClick,
                onLongClick = button.onLongClick,
                modifier = button.modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalculatorKey(
    label: String,
    operation: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (operation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (operation) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = label,
        ),
    ) {
        Box(modifier = Modifier.padding(vertical = 19.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        }
    }
}
