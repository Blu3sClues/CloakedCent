package com.cloakedcent.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.cloakedcent.app.core.money.MoneyFormatter
import java.text.DecimalFormatSymbols
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.cloakedcent.app.R
import com.cloakedcent.app.core.money.Money
import com.cloakedcent.app.domain.model.LedgerException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Converts between what a user types and exact cents, for the device locale.
 * Rules about the amount itself (e.g. must be positive) live in the ledger.
 */
object AmountInput {

    /**
     * Accepts "38", "38.5", "1,234.56", "$1,234.56", "-250" and, when no decimal
     * separator is present, a single separator followed by 1-2 digits as the decimal
     * part (so "12,50" works on a US keyboard and "12.50" on a German one).
     */
    fun parse(text: String, locale: Locale = Locale.getDefault()): Money? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimal = symbols.decimalSeparator
        val grouping = symbols.groupingSeparator.takeUnless { it.isWhitespace() } ?: if (decimal == ',') '.' else ','
        val compact = stripForEditing(text, locale).removePrefix("+")
        val normalized = when {
            decimal in compact -> compact.replace(grouping.toString(), "").replace(decimal, '.')
            compact.count { it == grouping } == 1 && compact.substringAfter(grouping).length in 1..2 ->
                compact.replace(grouping, '.')
            else -> compact.replace(grouping.toString(), "")
        }
        return Money.parseOrNull(normalized)
    }

    /** Display form used when a field is not being edited, e.g. "$1,234.50". */
    fun format(money: Money, locale: Locale = Locale.getDefault()): String = MoneyFormatter(locale).format(money)

    /** Removes the currency symbol/code and spaces so the number can be edited directly. */
    fun stripForEditing(text: String, locale: Locale = Locale.getDefault()): String {
        val currency = MoneyFormatter.defaultCurrency(locale)
        return text.replace(currency.getSymbol(locale), "")
            .replace(currency.currencyCode, "")
            .filterNot { it.isWhitespace() }
    }
}

/**
 * Amount entry. While focused the text is left exactly as typed (no reformatting,
 * no cursor jumps); on leaving the field a valid amount is shown as currency, and
 * on returning the symbol is removed for easy editing.
 */
@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Adopt changes made outside the field (prefill, focus formatting) with the cursor at the end.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length)).also { field = it }
    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        label = { Text(label) },
        isError = isError,
        enabled = enabled,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                val updated = if (focus.isFocused) {
                    AmountInput.stripForEditing(value)
                } else {
                    AmountInput.parse(value)?.let(AmountInput::format) ?: value
                }
                if (updated != value) onValueChange(updated)
            },
    )
}

/** User-facing message for a rule violation reported by the ledger. */
@StringRes
fun ledgerErrorMessage(error: Throwable): Int = when (error) {
    is LedgerException.InvalidAmount -> R.string.error_amount_positive
    is LedgerException.NameRequired -> R.string.error_name_required
    is LedgerException.AccountUnavailable -> R.string.error_account_unavailable
    is LedgerException.CategoryMismatch -> R.string.error_category_mismatch
    is LedgerException.SameAccountTransfer -> R.string.error_same_account
    is LedgerException.CreditCardTransferSource -> R.string.error_credit_card_transfer_source
    is LedgerException.AccountHasBalance -> R.string.error_account_has_balance
    is LedgerException.AdjustmentKindMismatch -> R.string.error_adjustment_kind
    is LedgerException.NotFound -> R.string.error_not_found
    is LedgerException.CategoryTooDeep -> R.string.error_category_too_deep
    is LedgerException.CategoryNameTaken -> R.string.error_category_name_taken
    is LedgerException.CategoryInUse -> R.string.error_category_in_use
    is LedgerException.InvalidOperation -> R.string.error_not_allowed
    else -> R.string.error_unexpected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            // Top-level destinations reached from the navigation bar have no back arrow.
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = { actions() },
    )
}

/** Read-only field that opens a menu of [options]. */
@Composable
fun <T> PickerField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) {
            Box(Modifier.matchParentSize().clickable(role = Role.DropdownList) { expanded = true })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onDateChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true })
    }
    if (open) {
        // DatePicker works in UTC midnight millis.
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(it.toUtcDate()) }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state) }
    }
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toUtcDate(): LocalDate = java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Confirmation for destructive or irreversible actions. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Confirmation for a destructive action on something with a linked record: the user
 * chooses to include the linked record, leave it, or cancel. Labels are stacked, as
 * they are usually too long to sit side by side.
 */
@Composable
fun LinkedDeleteDialog(
    title: String,
    text: String,
    bothLabel: String,
    onlyLabel: String,
    onBoth: () -> Unit,
    onOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onBoth) { Text(bothLabel, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onOnly) { Text(onlyLabel, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
