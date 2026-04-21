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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "TmuxViewModel"

data class TmuxSession(val name: String, val createdAt: Long)

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

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
