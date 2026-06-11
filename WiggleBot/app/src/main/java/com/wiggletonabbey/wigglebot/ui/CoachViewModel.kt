package com.wiggletonabbey.wigglebot.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "CoachViewModel"

data class CoachSuggestion(
    val day: LocalDate,
    val type: String,
    val title: String,
    val detail: String?,
    val rationale: String?,
)

data class CoachRun(
    val startedAt: String,
    val day: LocalDate?,
    val durationS: Int?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val elevationGainM: Double?,
    val source: String?,
)

data class CoachEvent(
    val name: String,
    val date: LocalDate,
    val distanceM: Double?,
    val goal: String?,
)

data class CoachWeek(
    val weekStart: LocalDate,
    val suggestions: List<CoachSuggestion>,
    val runs: List<CoachRun>,
    val events: List<CoachEvent>,
)

class CoachViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _week = MutableStateFlow<CoachWeek?>(null)
    val week: StateFlow<CoachWeek?> = _week.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _replanning = MutableStateFlow(false)
    val replanning: StateFlow<Boolean> = _replanning.asStateFlow()

    fun loadWeek(start: LocalDate? = null) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
                val url = buildString {
                    append("$serverUrl/api/coach/week")
                    if (start != null) append("?start=").append(start)
                }
                val body = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).build())
                        .execute().use { it.body?.string() ?: "{}" }
                }
                _week.value = parseWeek(body)
            }.onFailure {
                Log.e(TAG, "loadWeek failed", it)
                _error.value = it.message ?: "Could not reach server"
            }
            _loading.value = false
        }
    }

    fun previousWeek() {
        val current = _week.value?.weekStart ?: return
        loadWeek(current.minusWeeks(1))
    }

    fun nextWeek() {
        val current = _week.value?.weekStart ?: return
        loadWeek(current.plusWeeks(1))
    }

    fun replanWeek() {
        viewModelScope.launch {
            _replanning.value = true
            runCatching {
                val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
                withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder()
                            .url("$serverUrl/api/coach/replan")
                            .post("".toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute().close()
                }
                loadWeek(_week.value?.weekStart)
            }.onFailure {
                Log.e(TAG, "replan failed", it)
                _error.value = it.message ?: "Replan failed"
            }
            _replanning.value = false
        }
    }

    private fun parseWeek(body: String): CoachWeek {
        val obj = json.parseToJsonElement(body).jsonObject
        val weekStart = LocalDate.parse(obj["week_start"]!!.jsonPrimitive.content)

        val suggestions = obj["suggestions"]?.jsonArray?.mapNotNull { el ->
            val s = el.jsonObject
            CoachSuggestion(
                day = LocalDate.parse(s["day"]?.jsonPrimitive?.content ?: return@mapNotNull null),
                type = s["type"]?.jsonPrimitive?.content ?: "easy",
                title = s["title"]?.jsonPrimitive?.content ?: "",
                detail = s["detail"]?.jsonPrimitive?.contentOrNull,
                rationale = s["rationale"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()

        val runs = obj["runs"]?.jsonArray?.mapNotNull { el ->
            val r = el.jsonObject
            val startedAt = r["started_at"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CoachRun(
                startedAt = startedAt,
                day = runCatching { LocalDate.parse(startedAt.substring(0, 10)) }.getOrNull(),
                durationS = r["duration_s"]?.jsonPrimitive?.intOrNull,
                distanceM = r["distance_m"]?.jsonPrimitive?.doubleOrNull,
                avgHr = r["avg_hr"]?.jsonPrimitive?.intOrNull,
                maxHr = r["max_hr"]?.jsonPrimitive?.intOrNull,
                elevationGainM = r["elevation_gain_m"]?.jsonPrimitive?.doubleOrNull,
                source = r["source"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()

        val events = obj["events"]?.jsonArray?.mapNotNull { el ->
            val e = el.jsonObject
            CoachEvent(
                name = e["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                date = LocalDate.parse(e["date"]?.jsonPrimitive?.content ?: return@mapNotNull null),
                distanceM = e["distance_m"]?.jsonPrimitive?.doubleOrNull,
                goal = e["goal"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()

        return CoachWeek(weekStart, suggestions, runs, events)
    }
}
