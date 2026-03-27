package com.example.healthactivitywidget

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectRepository(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        // Minimum daily steps to count as an active day (10k step goal)
        private const val STEPS_THRESHOLD = 10_000L
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    suspend fun hasPermissions(): Boolean {
        if (!isAvailable()) return false
        return client.permissionController
            .getGrantedPermissions()
            .containsAll(REQUIRED_PERMISSIONS)
    }

    /**
     * Returns the set of [LocalDate]s in the last [weeks] weeks on which the user
     * recorded steps above the threshold or logged at least one exercise session.
     *
     * The date range starts on the Sunday of the week that was [weeks]-1 weeks ago
     * and ends today, matching the GitHub-style contribution calendar layout.
     */
    suspend fun getActiveDays(weeks: Int): Set<LocalDate> {
        if (!hasPermissions()) return emptySet()

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        // Align start to the Sunday of the earliest week so the grid columns are full weeks
        val todayDow = today.dayOfWeek.value % 7 // Sunday=0 … Saturday=6
        val currentWeekSunday = today.minusDays(todayDow.toLong())
        val startDate = currentWeekSunday.minusWeeks((weeks - 1).toLong())

        val startInstant = startDate.atStartOfDay(zone).toInstant()
        val endInstant = today.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(startInstant, endInstant)

        val activeDays = mutableSetOf<LocalDate>()

        try {
            // Aggregate steps per calendar day; multiple records on the same day are summed
            val dailySteps = mutableMapOf<LocalDate, Long>()
            val stepsResponse = client.readRecords(ReadRecordsRequest(StepsRecord::class, filter))
            for (record in stepsResponse.records) {
                val date = record.startTime.atZone(zone).toLocalDate()
                dailySteps[date] = (dailySteps[date] ?: 0L) + record.count
            }
            dailySteps.forEach { (date, steps) ->
                if (steps >= STEPS_THRESHOLD) activeDays.add(date)
            }

            // Any exercise session also marks a day as active regardless of step count
            val exerciseResponse = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, filter)
            )
            for (record in exerciseResponse.records) {
                activeDays.add(record.startTime.atZone(zone).toLocalDate())
            }
        } catch (_: Exception) {
            // Return whatever was collected so far; an empty set on total failure is fine
        }

        return activeDays
    }
}
