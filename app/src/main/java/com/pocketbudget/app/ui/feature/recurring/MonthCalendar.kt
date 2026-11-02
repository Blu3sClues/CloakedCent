package com.pocketbudget.app.ui.feature.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketbudget.app.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/** What a calendar day carries: how many items fall on it and whether any is overdue. */
data class DayMarker(val count: Int, val overdue: Boolean)

/**
 * The cells of [month] laid out in weeks starting on [firstDayOfWeek]: leading nulls
 * pad the first week, trailing nulls fill the last one.
 */
fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
    val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val cells = List(lead) { null } + days
    return cells + List((7 - cells.size % 7) % 7) { null }
}

@Composable
fun MonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    markers: Map<LocalDate, DayMarker>,
    onSelect: (LocalDate) -> Unit,
    onPreviousMonth: (() -> Unit)?,
    onNextMonth: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDay) { monthGrid(month, firstDay) }
    val titleFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val dayFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPreviousMonth?.invoke() }, enabled = onPreviousMonth != null) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.upcoming_previous_month))
            }
            Text(
                month.format(titleFormat).replaceFirstChar { it.titlecase(locale) },
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onNextMonth?.invoke() }, enabled = onNextMonth != null) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.upcoming_next_month))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            (0L until 7L).forEach { offset ->
                Text(
                    firstDay.plus(offset).getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val marker = markers[date]
                            val count = marker?.count ?: 0
                            val description = buildString {
                                append(date.format(dayFormat))
                                if (count > 0) append(", ").append(pluralStringResource(R.plurals.upcoming_day_count, count, count))
                                if (marker?.overdue == true) append(", ").append(stringResource(R.string.upcoming_state_overdue))
                            }
                            CalendarDay(
                                date = date,
                                isSelected = date == selected,
                                isToday = date == today,
                                marker = marker,
                                description = description,
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    marker: DayMarker?,
    description: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (isSelected) colors.onPrimary else colors.onSurface
    val dot = when {
        marker == null -> Color.Transparent
        isSelected -> colors.onPrimary
        marker.overdue -> colors.error
        else -> colors.primary
    }
    Column(
        Modifier
            .size(44.dp)
            .background(if (isSelected) colors.primary else Color.Transparent, CircleShape)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier)
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
                role = Role.Button
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || marker != null) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
        )
        Spacer(Modifier.height(2.dp))
        Box(Modifier.size(5.dp).background(dot, CircleShape))
    }
}
