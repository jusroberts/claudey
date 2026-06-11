package com.wiggletonabbey.wigglebot.schedule

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
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

/** One run session with metrics, ready to upload to the server. */
data class RunForSync(
    val externalId: String,
    val startedAt: Instant,
    val durationS: Long,
    val distanceM: Double?,
    val avgHr: Long?,
    val maxHr: Long?,
    val elevationGainM: Double?,
    val calories: Double?,
    val title: String?,
)

class HealthConnectHelper(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions = setOf(
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(
            ExerciseSessionRecord::class
        ),
        androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
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
        if (!isAvailable() || !hasPermission()) return@runCatching false

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
        false
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

    /**
     * Reads running sessions from the last [days] days with per-session
     * aggregates (distance, HR, elevation, calories) for upload to the
     * server's run store. Metric reads degrade to null individually when
     * their Health Connect permissions aren't granted.
     */
    suspend fun recentRunsForSync(days: Long = 14): List<RunForSync> = runCatching {
        if (!isAvailable() || !hasPermission()) return@runCatching emptyList()

        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)

        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        ).records.filter { it.exerciseType in RUNNING_TYPES }

        sessions.map { session ->
            val metrics = runCatching {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            DistanceRecord.DISTANCE_TOTAL,
                            HeartRateRecord.BPM_AVG,
                            HeartRateRecord.BPM_MAX,
                            ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
                            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ),
                        timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                    )
                )
            }.getOrNull()

            RunForSync(
                externalId = session.metadata.id,
                startedAt = session.startTime,
                durationS = ChronoUnit.SECONDS.between(session.startTime, session.endTime),
                distanceM = metrics?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                avgHr = metrics?.get(HeartRateRecord.BPM_AVG),
                maxHr = metrics?.get(HeartRateRecord.BPM_MAX),
                elevationGainM = metrics?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
                calories = metrics?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
                title = session.title,
            )
        }
    }.getOrElse { e ->
        Log.e(TAG, "recentRunsForSync failed", e)
        emptyList()
    }

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
