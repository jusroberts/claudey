package com.wiggleton.healthactivitywidget

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectRepository(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        private const val STEPS_THRESHOLD = 10_000L

        /**
         * Human-readable name for a Health Connect exercise type.
         * Uses SDK-defined named constants (guaranteed correct values).
         * EXERCISE_TYPE_SKATEBOARDING is omitted — added in a later SDK version.
         */
        fun exerciseTypeName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT                    -> "Other Workout"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON                        -> "Badminton"
            ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL                         -> "Baseball"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL                       -> "Basketball"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING                           -> "Biking"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY                -> "Stationary Bike"
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP                        -> "Boot Camp"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING                           -> "Boxing"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS                     -> "Calisthenics"
            ExerciseSessionRecord.EXERCISE_TYPE_CRICKET                          -> "Cricket"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING                          -> "Dancing"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL                       -> "Elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS                   -> "Exercise Class"
            ExerciseSessionRecord.EXERCISE_TYPE_FENCING                          -> "Fencing"
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN                -> "American Football"
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN              -> "Australian Football"
            ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC                     -> "Frisbee"
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF                             -> "Golf"
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING                 -> "Guided Breathing"
            ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS                       -> "Gymnastics"
            ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL                         -> "Handball"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING                           -> "Hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY                       -> "Ice Hockey"
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING                      -> "Ice Skating"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS                     -> "Martial Arts"
            ExerciseSessionRecord.EXERCISE_TYPE_PADDLING                         -> "Paddling"
            ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING                      -> "Paragliding"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES                          -> "Pilates"
            ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL                      -> "Racquetball"
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING                    -> "Rock Climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY                    -> "Roller Hockey"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING                           -> "Rowing"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE                   -> "Rowing Machine"
            ExerciseSessionRecord.EXERCISE_TYPE_RUGBY                            -> "Rugby"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING                          -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL                -> "Treadmill"
            ExerciseSessionRecord.EXERCISE_TYPE_SAILING                          -> "Sailing"
            ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING                     -> "Scuba Diving"
            ExerciseSessionRecord.EXERCISE_TYPE_SKATING                          -> "Skating"
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING                           -> "Skiing"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING                     -> "Snowboarding"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING                      -> "Snowshoeing"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER                           -> "Soccer"
            ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL                         -> "Softball"
            ExerciseSessionRecord.EXERCISE_TYPE_SQUASH                           -> "Squash"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING                   -> "Stair Climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE           -> "Stair Machine"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING                -> "Strength Training"
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING                       -> "Stretching"
            ExerciseSessionRecord.EXERCISE_TYPE_SURFING                          -> "Surfing"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER              -> "Open Water Swimming"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL                    -> "Swimming"
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS                     -> "Table Tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS                           -> "Tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL                       -> "Volleyball"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING                          -> "Walking"
            ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO                       -> "Water Polo"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING                    -> "Weightlifting"
            ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR                       -> "Wheelchair"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA                             -> "Yoga"
            else                                                                  -> "Exercise (type $type)"
        }
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

    /** Returns the distinct exercise types recorded in the last [weeks] weeks, sorted by name. */
    suspend fun getExerciseTypes(weeks: Int): List<Pair<Int, String>> {
        if (!hasPermissions()) return emptyList()
        val filter = buildFilter(weeks)
        return try {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter))
                .records
                .map { it.exerciseType }
                .toSortedSet()
                .map { type -> type to exerciseTypeName(type) }
                .sortedBy { it.second }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns a map of date → set of activity keys for each day that had qualifying activity.
     * Activity keys: [WidgetPreferences.STEPS_KEY] for steps, [WidgetPreferences.exerciseKey] for exercises.
     * Exercise types in [disabledExerciseTypes] are excluded.
     */
    suspend fun getActivityData(
        weeks: Int,
        showSteps: Boolean = true,
        disabledExerciseTypes: Set<Int> = emptySet(),
    ): Map<LocalDate, Set<String>> {
        if (!hasPermissions()) return emptyMap()

        val zone = ZoneId.systemDefault()
        val filter = buildFilter(weeks)
        val result = mutableMapOf<LocalDate, MutableSet<String>>()

        fun add(date: LocalDate, key: String) =
            result.getOrPut(date) { mutableSetOf() }.add(key)

        try {
            if (showSteps) {
                val dailySteps = mutableMapOf<LocalDate, Long>()
                client.readRecords(ReadRecordsRequest(StepsRecord::class, filter))
                    .records.forEach { record ->
                        val date = record.startTime.atZone(zone).toLocalDate()
                        dailySteps[date] = (dailySteps[date] ?: 0L) + record.count
                    }
                dailySteps.forEach { (date, steps) ->
                    if (steps >= STEPS_THRESHOLD) add(date, WidgetPreferences.STEPS_KEY)
                }
            }

            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, filter))
                .records.forEach { record ->
                    if (record.exerciseType !in disabledExerciseTypes) {
                        val date = record.startTime.atZone(zone).toLocalDate()
                        add(date, WidgetPreferences.exerciseKey(record.exerciseType))
                    }
                }
        } catch (_: Exception) {
            // Return partial results
        }

        return result
    }

    private fun buildFilter(weeks: Int): TimeRangeFilter {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val todayDow = today.dayOfWeek.value % 7
        val startDate = today.minusDays(todayDow.toLong()).minusWeeks((weeks - 1).toLong())
        return TimeRangeFilter.between(
            startDate.atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }
}
