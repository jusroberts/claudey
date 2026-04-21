package com.wiggletonabbey.wigglebot.schedule

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val TAG = "HealthConnectHelper"

private val RUNNING_TYPES = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
)

// Minimum probability to call today a likely run day.
private const val RUN_PROBABILITY_THRESHOLD = 0.35

class HealthConnectHelper(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions = setOf(
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(
            ExerciseSessionRecord::class
        )
    )

    suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(): Boolean = runCatching {
        client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    }.getOrDefault(false)

    /**
     * Returns true if today is a likely run day based on the gap distribution in recent history.
     *
     * Algorithm (hazard rate over inter-run gaps):
     *   - Collect all run dates from the last 60 days.
     *   - Compute the list of day-gaps between each consecutive pair of runs.
     *   - Let N = days since the most recent run.
     *   - P(run today) = freq(gap == N) / freq(gap >= N)
     *
     * This adapts to any cadence (every-other-day, MWF, etc.) without needing
     * day-of-week labels. Falls back to true when there's insufficient history.
     */
    suspend fun isLikelyRunDay(): Boolean = runCatching {
        if (!isAvailable() || !hasPermission()) return@runCatching true

        val today = LocalDate.now(ZoneId.systemDefault())
        // Only look at runs that finished before today so "today" isn't contaminated.
        val end = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val start = end.minus(60, ChronoUnit.DAYS)

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )

        val runDates = response.records
            .filter { it.exerciseType in RUNNING_TYPES }
            .map { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSortedSet()

        if (runDates.size < 3) {
            Log.d(TAG, "isLikelyRunDay: too few runs (${runDates.size}) — defaulting to true")
            return@runCatching true
        }

        val lastRun = runDates.last()
        val daysSince = ChronoUnit.DAYS.between(lastRun, today).toInt()

        if (daysSince == 0) return@runCatching false // already ran today

        // Gaps between consecutive run dates (in days).
        val gaps = runDates.toList().zipWithNext { a, b ->
            ChronoUnit.DAYS.between(a, b).toInt()
        }

        val atExactly = gaps.count { it == daysSince }
        val atOrMore  = gaps.count { it >= daysSince }

        val probability = if (atOrMore == 0) {
            // Current gap exceeds every historical gap — very overdue, treat as run day.
            1.0
        } else {
            atExactly.toDouble() / atOrMore
        }

        Log.d(TAG, "isLikelyRunDay: daysSince=$daysSince atExactly=$atExactly " +
            "atOrMore=$atOrMore p=${"%.2f".format(probability)}")

        probability >= RUN_PROBABILITY_THRESHOLD
    }.getOrElse { e ->
        Log.e(TAG, "isLikelyRunDay failed", e)
        true
    }

    /** Returns true if the user has logged a run starting today. */
    suspend fun hasRunToday(): Boolean = runCatching {
        if (!isAvailable() || !hasPermission()) return@runCatching false

        val today = LocalDate.now(ZoneId.systemDefault())
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )

        response.records.any { it.exerciseType in RUNNING_TYPES }
    }.getOrDefault(false)

    /** Returns a human-readable debug summary of recent running data and today's inference. */
    suspend fun debugSummary(): String = runCatching {
        if (!isAvailable()) return@runCatching "Health Connect not available on this device"
        if (!hasPermission()) return@runCatching "Permission not granted — use Settings to grant it"

        val today = LocalDate.now(ZoneId.systemDefault())
        val end = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val start = end.minus(60, ChronoUnit.DAYS)

        val allResponse = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )

        val all = allResponse.records
        val runs = all.filter { it.exerciseType in RUNNING_TYPES }

        val runDates = runs
            .map { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSortedSet()

        val gaps = runDates.toList().zipWithNext { a, b ->
            ChronoUnit.DAYS.between(a, b).toInt()
        }

        val lastRun = runDates.lastOrNull()
        val daysSince = lastRun?.let { ChronoUnit.DAYS.between(it, today).toInt() }

        val isLikely = isLikelyRunDay()
        val hasRunToday = hasRunToday()

        buildString {
            appendLine("Sessions (all types, 60d): ${all.size}")
            appendLine("Running sessions (60d): ${runs.size}")
            if (lastRun != null) {
                appendLine("Last run: $lastRun (${daysSince}d ago)")
            }
            if (gaps.isNotEmpty()) {
                val gapDist = gaps.groupingBy { it }.eachCount().entries
                    .sortedBy { it.key }
                    .joinToString { "${it.key}d×${it.value}" }
                appendLine("Gap distribution: $gapDist")
                appendLine("Median gap: ${gaps.sorted()[gaps.size / 2]}d")
            }
            appendLine("Likely run day today: $isLikely")
            append("Has run today: $hasRunToday")
        }
    }.getOrElse { "Error: ${it.message}" }
}
