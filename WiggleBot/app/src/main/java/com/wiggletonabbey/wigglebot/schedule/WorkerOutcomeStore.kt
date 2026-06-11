package com.wiggletonabbey.wigglebot.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.workerOutcomeDataStore: DataStore<Preferences> by preferencesDataStore(name = "worker_outcomes")

data class WorkerOutcome(
    val outcome: String,
    val timestampMs: Long,
)

/**
 * Records the last outcome of each background worker so silent failures
 * become visible in Settings → Notification diagnostics.
 *
 * Outcomes are short human-readable strings, e.g. "fired",
 * "skipped: already ran today", "suppressed: predictor says rest day",
 * "fired (default content — server unreachable)", "error: <message>".
 */
object WorkerOutcomeStore {

    const val KEY_RUN_BRIEF    = "run_brief"
    const val KEY_RUN_REMINDER = "run_reminder"
    const val KEY_COMMUTE      = "commute"

    val ALL_KEYS = listOf(KEY_RUN_BRIEF, KEY_RUN_REMINDER, KEY_COMMUTE)

    suspend fun record(context: Context, worker: String, outcome: String) {
        context.workerOutcomeDataStore.edit { prefs ->
            prefs[stringPreferencesKey("${worker}_outcome")] = outcome
            prefs[longPreferencesKey("${worker}_timestamp")] = System.currentTimeMillis()
        }
    }

    fun outcomes(context: Context): Flow<Map<String, WorkerOutcome>> =
        context.workerOutcomeDataStore.data.map { prefs ->
            ALL_KEYS.mapNotNull { key ->
                val outcome = prefs[stringPreferencesKey("${key}_outcome")] ?: return@mapNotNull null
                val ts = prefs[longPreferencesKey("${key}_timestamp")] ?: return@mapNotNull null
                key to WorkerOutcome(outcome, ts)
            }.toMap()
        }
}
