package com.aistudio.snoredetector.afkwd.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.aistudio.snoredetector.afkwd.data.ChangeKind
import com.aistudio.snoredetector.afkwd.data.ChangeMarker
import com.aistudio.snoredetector.afkwd.data.NightStats
import com.aistudio.snoredetector.afkwd.data.NightStatus
import com.aistudio.snoredetector.afkwd.data.NightSummary
import com.aistudio.snoredetector.afkwd.data.PeriodComparison
import com.aistudio.snoredetector.afkwd.data.ScoreBreakdown
import com.aistudio.snoredetector.afkwd.data.SnoreScore
import com.aistudio.snoredetector.afkwd.data.TrendPoint
import com.aistudio.snoredetector.afkwd.viewmodel.SnoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class TrendPeriod(val label: String, val days: Int) {
    WEEK("Week", 7),
    MONTH("Month", 30),
    THREE_MONTHS("3 months", 90),
    YEAR("Year", 365)
}

private const val RECENT_NIGHTS_SHOWN = 7

private fun one(value: Double) = String.format(Locale.US, "%.1f", value)

private fun Double.minutesAsHours(): String {
    val minutes = roundToInt()
    return "${minutes / 60} h ${minutes % 60} min"
}

/** Night dates are UTC-based day numbers, so they must be formatted in UTC to stay on that date. */
private fun formatNight(epochDay: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault())
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(NightStats.epochDayToUtcMillis(epochDay)))

@Composable
fun StatisticsTab(viewModel: SnoreViewModel) {
    val nights by viewModel.nights.collectAsState()
    val context = LocalContext.current

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportNightSummaries(uri) { success ->
                val message = if (success) "Nightly summaries saved" else "Failed to save nightly summaries"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    StatisticsContent(
        nights = nights,
        today = NightStats.nightEpochDay(System.currentTimeMillis()),
        onExportCsv = { csvLauncher.launch("snore_nightly_summaries.csv") }
    )
}

/**
 * The tab's content. [today] is the night number of the current date; the trend windows end on it.
 */
@Composable
fun StatisticsContent(
    nights: List<NightSummary>,
    today: Long,
    onExportCsv: () -> Unit
) {
    var period by rememberSaveable { mutableStateOf(TrendPeriod.MONTH) }
    var includeShort by rememberSaveable { mutableStateOf(false) }

    val trend = remember(nights, includeShort) { NightStats.trendNights(nights, includeShort) }
    val average = remember(trend) { NightStats.movingAverage(trend) }
    val markers = remember(nights) { NightStats.changeMarkers(nights) }
    val comparison = remember(trend, today) { NightStats.comparePeriods(trend, today) }
    val startDay = today - period.days + 1
    val range = startDay..today

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("statistics_tab"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        val lastNight = nights.lastOrNull()
        if (lastNight == null) {
            StatsCard {
                Text(
                    text = "No monitoring sessions saved yet. Your score appears here after a night of monitoring.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LastNightCard(lastNight)

            StatsCard {
                SectionTitle("Score over time")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrendPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(option.label) }
                        )
                    }
                }
                ScoreTrendChart(
                    nights = trend.filter { it.epochDay in range },
                    average = average.filter { it.epochDay in range },
                    markers = markers.filter { it.epochDay in range },
                    startDay = startDay,
                    endDay = today
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CaptionText(formatNight(startDay, "d MMM"))
                    CaptionText(formatNight(today, "d MMM"))
                }
                ChartLegend(showMarkerKey = markers.any { it.epochDay in range })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Include nights under ${(NightStats.MIN_RELIABLE_MINUTES / 60).roundToInt()} hours",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeShort,
                        onCheckedChange = { includeShort = it },
                        modifier = Modifier.testTag("include_short_nights")
                    )
                }
                val unscored = nights.count { it.status == NightStatus.NO_MONITORED_TIME }
                if (unscored > 0) {
                    CaptionText(
                        "$unscored earlier nights have no score, because older versions " +
                            "did not record how long monitoring ran."
                    )
                }
            }

            ComparisonCard(comparison)
            RecentNightsCard(nights)

            OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) {
                Text("Export nightly summaries (CSV)")
            }
        }
    }
}

@Composable
private fun StatsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CaptionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun statusNote(status: NightStatus): String? = when (status) {
    NightStatus.OK -> null
    NightStatus.TOO_SHORT -> "Too short for a reliable score"
    NightStatus.NO_MONITORED_TIME -> "No score: monitored time was not recorded"
}

@Composable
private fun LastNightCard(night: NightSummary) {
    var showFormula by rememberSaveable { mutableStateOf(false) }

    StatsCard {
        SectionTitle("Last night, ${formatNight(night.epochDay, "EEE d MMM")}")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = night.score?.toString() ?: "–",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("last_night_score")
            )
            Spacer(modifier = Modifier.width(8.dp))
            CaptionText("out of 100. Lower means less snoring was recorded.")
        }
        statusNote(night.status)?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        night.breakdown?.let { ValueRow("Snore index", "${one(it.snoreIndex)} min per hour") }
        ValueRow("Monitored", if (night.status == NightStatus.NO_MONITORED_TIME) "Not recorded" else night.monitoredMinutes.minutesAsHours())
        ValueRow("Episodes", night.snoreEventCount.toString())
        ValueRow("Longest episode", "${one(night.longestEpisodeSeconds)} s")
        ValueRow(
            "Loudness",
            night.breakdown?.loudnessAboveBackgroundDb?.let { "${one(it)} dB above background" } ?: "Not available"
        )

        night.breakdown?.let { breakdown ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFormula = !showFormula }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "How is this calculated?",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showFormula) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = showFormula) {
                FormulaPanel(breakdown)
            }
        }

        CaptionText(
            "This score only summarises sounds the app recorded. " +
                "It is not a medical diagnosis and cannot detect sleep apnea."
        )
    }
}

@Composable
private fun FormulaPanel(breakdown: ScoreBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptionText(
            "The score adds up four parts, each capped at a fixed value. Your detection settings " +
                "do not change these caps, so nights stay comparable."
        )
        FormulaPart(
            label = "Snoring time",
            points = breakdown.snoreIndexPoints,
            weight = SnoreScore.SNORE_INDEX_WEIGHT,
            detail = "${one(breakdown.snoreIndex)} min per hour, full points at " +
                "${SnoreScore.FULL_SNORE_INDEX.roundToInt()}"
        )
        FormulaPart(
            label = "Episodes",
            points = breakdown.episodePoints,
            weight = SnoreScore.EPISODE_WEIGHT,
            detail = "${one(breakdown.episodesPerHour)} per hour, full points at " +
                "${SnoreScore.FULL_EPISODES_PER_HOUR.roundToInt()}"
        )
        FormulaPart(
            label = "Longest episode",
            points = breakdown.longestEpisodePoints,
            weight = SnoreScore.LONGEST_EPISODE_WEIGHT,
            detail = "${one(breakdown.longestEpisodeSeconds)} s, full points at " +
                "${SnoreScore.FULL_LONGEST_EPISODE_SECONDS.roundToInt()} s"
        )
        FormulaPart(
            label = "Loudness",
            points = breakdown.loudnessPoints,
            weight = SnoreScore.LOUDNESS_WEIGHT,
            detail = (breakdown.loudnessAboveBackgroundDb?.let { "${one(it)} dB" } ?: "Not available") +
                " above background, full points at ${SnoreScore.FULL_LOUDNESS_DB.roundToInt()} dB"
        )
    }
}

@Composable
private fun FormulaPart(label: String, points: Double, weight: Double, detail: String) {
    Column {
        ValueRow(label, "${one(points)} of ${weight.roundToInt()} points")
        CaptionText(detail)
    }
}

@Composable
private fun ScoreTrendChart(
    nights: List<NightSummary>,
    average: List<TrendPoint>,
    markers: List<ChangeMarker>,
    startDay: Long,
    endDay: Long
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val markerColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()

    val summary = if (nights.isEmpty()) {
        "Snore score chart: no nights in this period."
    } else {
        "Snore score chart: ${nights.size} nights, average " +
            "${nights.mapNotNull { it.score }.average().roundToInt()} out of 100."
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .testTag("score_trend_chart")
            .semantics { contentDescription = summary }
    ) {
        val dotRadius = 3.dp.toPx()
        val left = 28.dp.toPx()
        val top = 16.dp.toPx()
        val right = size.width - dotRadius
        val bottom = size.height - dotRadius
        val daySpan = (endDay - startDay).coerceAtLeast(1L).toFloat()

        fun xOf(day: Long) = left + (day - startDay) / daySpan * (right - left)
        fun yOf(score: Double) = bottom - (score / 100.0).toFloat() * (bottom - top)

        for (gridScore in listOf(0, 50, 100)) {
            val y = yOf(gridScore.toDouble())
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val label = textMeasurer.measure(gridScore.toString(), labelStyle)
            drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (marker in markers) {
            val x = xOf(marker.epochDay)
            drawLine(markerColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val isUpdate = marker.kind == ChangeKind.APP_UPDATE
            val label = textMeasurer.measure(if (isUpdate) "A" else "S", labelStyle)
            val labelX = if (isUpdate) x - label.size.width - 2.dp.toPx() else x + 2.dp.toPx()
            drawText(label, topLeft = Offset(labelX, 0f))
        }

        // The line breaks where no night falls inside the averaging window
        val line = Path()
        average.forEachIndexed { index, point ->
            val isGap = index == 0 || point.epochDay - average[index - 1].epochDay > NightStats.MOVING_AVERAGE_DAYS
            val x = xOf(point.epochDay)
            val y = yOf(point.value)
            if (isGap) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        for (night in nights) {
            val score = night.score ?: continue
            val center = Offset(xOf(night.epochDay), yOf(score.toDouble()))
            if (night.status == NightStatus.OK) {
                drawCircle(dotColor, radius = dotRadius, center = center)
            } else {
                drawCircle(dotColor, radius = dotRadius, center = center, style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ChartLegend(showMarkerKey: Boolean) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = lineColor.copy(alpha = 0.45f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(dotColor, radius = 3.dp.toPx())
            }
            Spacer(modifier = Modifier.width(6.dp))
            CaptionText("Each night")
            Spacer(modifier = Modifier.width(16.dp))
            Canvas(modifier = Modifier.size(width = 18.dp, height = 12.dp)) {
                val y = size.height / 2
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            }
            Spacer(modifier = Modifier.width(6.dp))
            CaptionText("${NightStats.MOVING_AVERAGE_DAYS}-day average")
        }
        if (showMarkerKey) {
            CaptionText("A: app updated. S: detection settings changed.")
        }
    }
}

private fun signed(value: Double): String {
    val text = one(abs(value))
    return when {
        text == "0.0" -> text
        value < 0 -> "−$text"
        else -> "+$text"
    }
}

@Composable
private fun ComparisonCard(comparison: PeriodComparison) {
    val days = NightStats.COMPARISON_DAYS

    StatsCard {
        SectionTitle("Compare periods")
        ValueRow(
            "Last $days days",
            comparison.recentAverage?.let { "${one(it)} (${comparison.recentNights} nights)" } ?: "No nights"
        )
        ValueRow(
            "The $days days before",
            comparison.previousAverage?.let { "${one(it)} (${comparison.previousNights} nights)" } ?: "No nights"
        )
        val change = comparison.change
        if (change == null) {
            CaptionText("A comparison needs at least one scored night in both periods.")
        } else {
            val percent = comparison.percentChange?.let { " (${signed(it)}%)" }.orEmpty()
            ValueRow("Change", "${signed(change)} points$percent")
            CaptionText("A lower score means less snoring was recorded.")
        }
    }
}

@Composable
private fun RecentNightsCard(nights: List<NightSummary>) {
    StatsCard {
        SectionTitle("Recent nights")
        nights.takeLast(RECENT_NIGHTS_SHOWN).asReversed().forEach { night ->
            Column {
                ValueRow(formatNight(night.epochDay, "EEE d MMM"), night.score?.toString() ?: "–")
                statusNote(night.status)?.let { CaptionText(it) }
            }
        }
    }
}
