package com.pocketbudget.app.ui.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.pocketbudget.app.R
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.domain.model.AnalysisPeriod
import com.pocketbudget.app.domain.model.Comparison
import com.pocketbudget.app.domain.model.DatePreset
import com.pocketbudget.app.domain.model.DateRange
import com.pocketbudget.app.domain.model.Granularity
import com.pocketbudget.app.domain.model.PricePoint
import com.pocketbudget.app.domain.model.TrendPoint
import com.pocketbudget.app.domain.model.Trends
import com.pocketbudget.app.ui.components.AmountInput
import com.pocketbudget.app.ui.components.EmptyState
import com.pocketbudget.app.ui.components.MoneyText
import com.pocketbudget.app.ui.components.toUtcDate
import com.pocketbudget.app.ui.components.toUtcMillis
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Loading, loaded, or failed: every analysis screen shows one of these. */
sealed interface AnalysisState<out T> {
    data object Loading : AnalysisState<Nothing>
    data class Ready<T>(val value: T) : AnalysisState<T>
    data object Failed : AnalysisState<Nothing>
}

internal val analysisDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val monthLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
private val fullMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

/** Test tag of the spending trend bars. */
const val TREND_CHART_TAG = "trend_chart"

/** "+12.5%" from hundredths of a percent, using integer arithmetic only. */
fun formatBasisPoints(basisPoints: Long): String {
    val sign = if (basisPoints > 0) "+" else if (basisPoints < 0) "−" else ""
    val tenths = (Math.abs(basisPoints) + 5) / 10
    return "$sign${tenths / 10}.${tenths % 10}%"
}

/** "12.3%" from tenths of a percent. */
fun formatPerMille(perMille: Int): String = "${perMille / 10}.${perMille % 10}%"

@Composable
fun presetLabel(preset: DatePreset): String = stringResource(
    when (preset) {
        DatePreset.LAST_7_DAYS -> R.string.period_last_7
        DatePreset.LAST_30_DAYS -> R.string.period_last_30
        DatePreset.LAST_90_DAYS -> R.string.period_last_90
        DatePreset.THIS_MONTH -> R.string.period_this_month
        DatePreset.LAST_MONTH -> R.string.period_last_month
        DatePreset.THIS_YEAR -> R.string.period_this_year
        DatePreset.LAST_YEAR -> R.string.period_last_year
        DatePreset.ALL_TIME -> R.string.period_all_time
        DatePreset.CUSTOM -> R.string.period_custom
    },
)

@Composable
fun rangeLabel(range: DateRange): String =
    stringResource(R.string.period_range, analysisDate.format(range.start), analysisDate.format(range.endInclusive))

/** Preset chips plus a date-range picker for custom periods. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodFilter(period: AnalysisPeriod, onPreset: (DatePreset) -> Unit, onCustom: (DateRange) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatePreset.entries.forEach { preset ->
                FilterChip(
                    selected = period.preset == preset,
                    onClick = { if (preset == DatePreset.CUSTOM) picking = true else onPreset(preset) },
                    label = { Text(presetLabel(preset)) },
                )
            }
        }
        if (period.preset != DatePreset.ALL_TIME) {
            Text(rangeLabel(period.range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = period.range.start.toUtcMillis().takeIf { period.preset != DatePreset.ALL_TIME },
            initialSelectedEndDateMillis = period.range.endInclusive.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = state.selectedStartDateMillis?.toUtcDate()
                        val end = state.selectedEndDateMillis?.toUtcDate() ?: start
                        if (start != null && end != null) onCustom(DateRange(start, end))
                        picking = false
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DateRangePicker(state, Modifier.weight(1f), showModeToggle = false)
        }
    }
}

/** Shows loading, failure, or the content of a loaded state. */
@Composable
fun <T> StateContent(state: AnalysisState<T>, content: @Composable (T) -> Unit) {
    when (state) {
        AnalysisState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        AnalysisState.Failed -> EmptyState(stringResource(R.string.analysis_error), "")
        is AnalysisState.Ready -> content(state.value)
    }
}

@Composable
fun NoSpending(body: String = stringResource(R.string.analysis_empty_body)) {
    EmptyState(stringResource(R.string.analysis_empty_title), body)
}

/** Headline amount with its change against the previous period. */
@Composable
fun TotalCard(label: String, comparison: Comparison, extra: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(comparison.current, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold))
            ChangeText(comparison)
            extra()
        }
    }
}

@Composable
fun ChangeText(comparison: Comparison) {
    val previous = comparison.previous ?: return
    val change = comparison.changeBasisPoints
    if (change == null) {
        Text(stringResource(R.string.analysis_no_previous), style = MaterialTheme.typography.bodySmall)
        return
    }
    // More spending is shown in the error colour, less in the primary colour.
    val color = when {
        change > 0 -> MaterialTheme.colorScheme.error
        change < 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        stringResource(R.string.analysis_vs_previous, formatBasisPoints(change), AmountInput.format(previous)),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** A labelled amount with a proportional bar; [share] in tenths of a percent. */
@Composable
fun AmountBarRow(
    title: String,
    subtitle: String?,
    amount: Money,
    share: Int,
    modifier: Modifier = Modifier,
    indent: Int = 0,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = (indent * 16).dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            MoneyText(amount)
        }
        LinearProgressIndicator(progress = { share / 1000f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun bucketLabel(date: LocalDate, granularity: Granularity): String =
    if (granularity == Granularity.MONTH) monthLabel.format(date) else shortDate.format(date)

/** The days a trend bar covers: one date, a full month by name, or the first and last day. */
@Composable
private fun trendPeriodLabel(point: TrendPoint, granularity: Granularity, range: DateRange): String {
    val days = Trends.bucketRange(point.start, granularity, range)
    return when {
        days.start == days.endInclusive -> analysisDate.format(days.start)
        granularity == Granularity.MONTH && days.start == point.start && days.endInclusive.plusDays(1).dayOfMonth == 1 ->
            fullMonth.format(days.start)
        else -> stringResource(R.string.analysis_period_span, analysisDate.format(days.start), analysisDate.format(days.endInclusive))
    }
}

/**
 * Bar chart of spending per day, week or month. Pixel positions only; amounts stay exact.
 * Tapping a bar shows its period and amount over the chart; touching anywhere else hides them.
 */
@Composable
fun TrendChart(points: List<TrendPoint>, range: DateRange) {
    if (points.isEmpty()) return
    val granularity = Trends.granularity(DateRange(points.first().start, range.endInclusive))
    val max = points.maxOf { it.amount.cents }.coerceAtLeast(1)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val bar = MaterialTheme.colorScheme.primary
    val dimmed = bar.copy(alpha = 0.45f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = pluralStringResource(
        R.plurals.analysis_chart_description,
        points.size,
        points.size,
        bucketLabel(points.first().start, granularity),
        bucketLabel(points.last().start, granularity),
    )
    Column {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .testTag(TREND_CHART_TAG)
                    .semantics { contentDescription = description }
                    .pointerInput(points) {
                        detectTapGestures { tap ->
                            val index = (tap.x / (size.width.toFloat() / points.size)).toInt().coerceIn(0, points.lastIndex)
                            selected = if (selected == index) null else index
                        }
                    },
            ) {
                val slot = size.width / points.size
                val width = (slot * 0.7f).coerceAtLeast(1f)
                points.forEachIndexed { i, point ->
                    val height = size.height * point.amount.cents.toFloat() / max
                    val left = i * slot + (slot - width) / 2
                    drawRoundRect(track, Offset(left, 0f), Size(width, size.height), CornerRadius(3f))
                    val color = if (selected == null || selected == i) bar else dimmed
                    if (height > 0f) drawRoundRect(color, Offset(left, size.height - height), Size(width, height), CornerRadius(3f))
                }
            }
            selected?.let { index ->
                val point = points[index]
                // Focusable, so any touch off the tooltip (or Back) only hides it.
                Popup(
                    popupPositionProvider = TrendTooltipPosition(index, points.size),
                    onDismissRequest = { selected = null },
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(
                        onClick = { selected = null },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 2.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(trendPeriodLabel(point, granularity, range), style = MaterialTheme.typography.labelMedium)
                            MoneyText(point.amount, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(bucketLabel(points.first().start, granularity), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(bucketLabel(points.last().start, granularity), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Line chart of unit price over time for purchases in one dimension. */
@Composable
fun PriceChart(points: List<PricePoint>) {
    val priced = points.filter { it.normalized != null }
    val dimension = priced.groupingBy { it.normalized!!.dimension }.eachCount().maxByOrNull { it.value }?.key ?: return
    val series = priced.filter { it.normalized!!.dimension == dimension }.sortedBy { it.date }
    if (series.size < 2) return
    val first = series.first().date.toEpochDay()
    val span = (series.last().date.toEpochDay() - first).coerceAtLeast(1)
    val low = series.minOf { it.normalized!!.milliCentsPerUnit }
    val high = series.maxOf { it.normalized!!.milliCentsPerUnit }
    val spread = (high - low).coerceAtLeast(1)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = pluralStringResource(
        R.plurals.analysis_chart_description,
        series.size,
        series.size,
        analysisDate.format(series.first().date),
        analysisDate.format(series.last().date),
    )
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp).semantics { contentDescription = description }) {
            fun position(p: PricePoint) = Offset(
                size.width * (p.date.toEpochDay() - first) / span,
                size.height - size.height * (p.normalized!!.milliCentsPerUnit - low) / spread,
            )
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f))
            val path = Path()
            series.forEachIndexed { i, p ->
                val o = position(p)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
            series.forEach { drawCircle(line, 4.dp.toPx(), position(it)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(analysisDate.format(series.first().date), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(analysisDate.format(series.last().date), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Places the trend tooltip at the top of the chart, centred over bar [index], inside the chart's edges. */
private class TrendTooltipPosition(private val index: Int, private val count: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val centre = anchorBounds.left + anchorBounds.width * (index + 0.5f) / count
        val maxLeft = (anchorBounds.right - popupContentSize.width).coerceAtLeast(anchorBounds.left)
        return IntOffset((centre - popupContentSize.width / 2f).roundToInt().coerceIn(anchorBounds.left, maxLeft), anchorBounds.top)
    }
}
