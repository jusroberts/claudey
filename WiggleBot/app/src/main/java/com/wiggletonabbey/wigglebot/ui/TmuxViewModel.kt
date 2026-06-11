package com.wiggletonabbey.wigglebot.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import com.wiggletonabbey.wigglebot.service.TmuxChannelService
import com.wiggletonabbey.wigglebot.service.TmuxPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "TmuxViewModel"

data class TmuxSession(val name: String, val createdAt: Long)

data class DirListing(
    val path: String,
    val parent: String?,
    val home: String,
    val dirs: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _sessions = MutableStateFlow<List<TmuxSession>>(emptyList())
    val sessions: StateFlow<List<TmuxSession>> = _sessions.asStateFlow()

    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    private var channelService: TmuxChannelService? = null
    private val _serviceOutput = MutableStateFlow<TmuxChannelService?>(null)

    val output: StateFlow<String> = _serviceOutput
        .flatMapLatest { svc -> svc?.output ?: flowOf("") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val connected: StateFlow<Boolean> = _serviceOutput
        .flatMapLatest { svc -> svc?.connected ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val prompt: StateFlow<TmuxPrompt?> = _serviceOutput
        .flatMapLatest { svc -> svc?.prompt ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _dirListing = MutableStateFlow<DirListing?>(null)
    val dirListing: StateFlow<DirListing?> = _dirListing.asStateFlow()

    private val _createError = MutableStateFlow<String?>(null)
    val createError: StateFlow<String?> = _createError.asStateFlow()

    fun loadSessions() {
        viewModelScope.launch {
            _sessionsLoading.value = true
            runCatching {
                val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
                val body = withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder().url("$serverUrl/api/tmux/sessions").build()
                    ).execute().use { it.body?.string() ?: "[]" }
                }
                val arr = json.parseToJsonElement(body).jsonArray
                _sessions.value = arr.map { el ->
                    val obj = el.jsonObject
                    TmuxSession(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }
            }.onFailure { Log.e(TAG, "loadSessions failed", it) }
            _sessionsLoading.value = false
        }
    }

    fun connect(sessionName: String) {
        viewModelScope.launch {
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            channelService?.disconnect()
            val svc = TmuxChannelService(serverUrl, sessionName)
            channelService = svc
            _serviceOutput.value = svc
            svc.connect()
        }
    }

    fun disconnect() {
        channelService?.disconnect()
        channelService = null
        _serviceOutput.value = null
    }

    fun sendInput(text: String) {
        channelService?.sendInput(text)
    }

    fun sendKey(key: String) {
        channelService?.sendKey(key)
    }

    fun answerPrompt(key: String) {
        channelService?.answerPrompt(key)
    }

    /** Loads the home-constrained directory listing for the new-session picker. */
    fun loadDirs(path: String? = null) {
        viewModelScope.launch {
            runCatching {
                val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
                val url = buildString {
                    append("$serverUrl/api/fs/dirs")
                    if (path != null) {
                        append("?path=").append(java.net.URLEncoder.encode(path, "UTF-8"))
                    }
                }
                val body = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).build())
                        .execute().use { it.body?.string() ?: "{}" }
                }
                val obj = json.parseToJsonElement(body).jsonObject
                val listingPath = obj["path"]?.jsonPrimitive?.content
                if (listingPath != null) {
                    _dirListing.value = DirListing(
                        path = listingPath,
                        parent = obj["parent"]?.jsonPrimitive?.contentOrNull,
                        home = obj["home"]?.jsonPrimitive?.content ?: "",
                        dirs = obj["dirs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    )
                }
            }.onFailure { Log.e(TAG, "loadDirs failed", it) }
        }
    }

    fun createSession(name: String, cwd: String, runClaude: Boolean, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _createError.value = null
            runCatching {
                val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
                val payload = buildJsonObject {
                    put("name", name)
                    put("cwd", cwd)
                    put("run_claude", runClaude)
                }.toString()
                val (code, body) = withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder()
                            .url("$serverUrl/api/tmux/sessions")
                            .post(payload.toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute().use { it.code to (it.body?.string() ?: "{}") }
                }
                if (code == 200) {
                    loadSessions()
                    onCreated(name)
                } else {
                    val err = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    _createError.value = err ?: "Server error (HTTP $code)"
                }
            }.onFailure {
                Log.e(TAG, "createSession failed", it)
                _createError.value = it.message ?: "Request failed"
            }
        }
    }

    fun clearCreateError() {
        _createError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
