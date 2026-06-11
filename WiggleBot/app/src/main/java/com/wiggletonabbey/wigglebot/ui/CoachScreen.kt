package com.wiggletonabbey.wigglebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(
    viewModel: CoachViewModel,
    onNavigateBack: () -> Unit,
) {
    val week by viewModel.week.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val replanning by viewModel.replanning.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadWeek() }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Coach", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.replanWeek() }, enabled = !replanning) {
                        Icon(Icons.Default.Refresh, "Replan week", tint = if (replanning) AmberDim else Amber)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Week navigation header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.previousWeek() }) {
                    Icon(Icons.Default.ChevronLeft, "Previous week", tint = TextSecondary)
                }
                Text(
                    week?.weekStart?.let { ws ->
                        val fmt = DateTimeFormatter.ofPattern("MMM d")
                        "Week of ${fmt.format(ws)} – ${fmt.format(ws.plusDays(6))}"
                    } ?: "Loading…",
                    modifier = Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { viewModel.nextWeek() }) {
                    Icon(Icons.Default.ChevronRight, "Next week", tint = TextSecondary)
                }
            }

            error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (replanning) {
                Text(
                    "Asking the coach for a new plan…",
                    color = Amber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val w = week
            if (loading && w == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Amber)
                }
            } else if (w != null) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Upcoming event countdown banner
                    val nextEvent = w.events.minByOrNull { it.date }
                    if (nextEvent != null) {
                        item(key = "event") {
                            EventBanner(nextEvent)
                        }
                    }

                    items((0..6).toList(), key = { it }) { offset ->
                        val day = w.weekStart.plusDays(offset.toLong())
                        DayCard(
                            day = day,
                            suggestion = w.suggestions.find { it.day == day },
                            runs = w.runs.filter { it.day == day },
                            isToday = day == LocalDate.now(),
                        )
                    }

                    if (w.suggestions.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No plan for this week yet — tap ↻ to generate one.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBanner(event: CoachEvent) {
    val daysOut = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), event.date)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Amber.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.DirectionsRun, null, tint = Amber, modifier = Modifier.size(20.dp))
        Column {
            Text(event.name, color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                buildString {
                    append(if (daysOut >= 0) "in $daysOut days" else "${-daysOut} days ago")
                    event.distanceM?.let { append(" · ${"%.1f".format(it / 1000)} km") }
                    event.goal?.let { append(" · goal: $it") }
                },
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DayCard(
    day: LocalDate,
    suggestion: CoachSuggestion?,
    runs: List<CoachRun>,
    isToday: Boolean,
) {
    val ran = runs.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isToday) Surface else Surface.copy(alpha = 0.6f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day.format(DateTimeFormatter.ofPattern("EEE d")),
                color = if (isToday) Amber else TextSecondary,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(64.dp),
            )
            Column(Modifier.weight(1f)) {
                if (suggestion != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeChip(suggestion.type)
                        Text(
                            suggestion.title,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    suggestion.detail?.let {
                        Text(it, color = TextSecondary, fontSize = 12.sp)
                    }
                } else {
                    Text("—", color = TextSecondary, fontSize = 14.sp)
                }
            }
            StatusMark(suggestion = suggestion, ran = ran, day = day)
        }

        runs.forEach { run ->
            Text(
                runSummary(run),
                color = ToolGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (expanded && suggestion?.rationale != null) {
            Text(
                suggestion.rationale,
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TypeChip(type: String) {
    val color = when (type) {
        "rest" -> TextSecondary
        "easy", "recovery" -> ToolGreen
        "long" -> Amber
        else -> Color(0xFFE57373) // tempo/intervals/hills: hard days
    }
    Text(
        type,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun StatusMark(suggestion: CoachSuggestion?, ran: Boolean, day: LocalDate) {
    val past = day.isBefore(LocalDate.now())
    val text = when {
        ran -> "✓"
        suggestion?.type == "rest" && past -> "✓"
        past && suggestion != null -> "✗"
        else -> ""
    }
    val color = when {
        ran -> ToolGreen
        suggestion?.type == "rest" && past -> TextSecondary
        else -> Color(0xFFE57373)
    }
    if (text.isNotEmpty()) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun runSummary(run: CoachRun): String = buildString {
    append("▸ ")
    run.distanceM?.let { append("%.1f km".format(it / 1000)) }
    run.durationS?.let { s ->
        if (isNotEmpty()) append(" · ")
        append("%d:%02d".format(s / 60, s % 60))
        run.distanceM?.takeIf { it > 0 }?.let { d ->
            val paceSecPerKm = s / (d / 1000)
            append(" · %d:%02d/km".format((paceSecPerKm / 60).toInt(), (paceSecPerKm % 60).toInt()))
        }
    }
    run.avgHr?.let { append(" · ♥$it") }
    run.elevationGainM?.takeIf { it > 0 }?.let { append(" · ↗${it.toInt()}m") }
    if (length <= 2) append("run logged")
}
