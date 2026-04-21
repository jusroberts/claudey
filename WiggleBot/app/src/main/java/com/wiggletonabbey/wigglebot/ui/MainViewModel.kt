package com.wiggletonabbey.wigglebot.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import com.wiggletonabbey.wigglebot.schedule.HealthConnectHelper
import com.wiggletonabbey.wigglebot.service.AgentSettings
import com.wiggletonabbey.wigglebot.service.ChannelEvent
import com.wiggletonabbey.wigglebot.workers.CommuteWorker
import com.wiggletonabbey.wigglebot.workers.RunReminderWorker
import com.wiggletonabbey.wigglebot.workers.RunningWeatherWorker
import com.wiggletonabbey.wigglebot.service.PhoenixChannelService
import com.wiggletonabbey.wigglebot.service.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UiMessage {
    val id: Long
    data class User(override val id: Long, val text: String) : UiMessage
    data class Assistant(override val id: Long, val text: String) : UiMessage
    data class ToolActivity(override val id: Long, val summary: String) : UiMessage
    data class Error(override val id: Long, val message: String) : UiMessage
    data class Thinking(override val id: Long) : UiMessage
}

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isThinking: Boolean = false,
    val inputText: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Unknown,
    val connectionError: String? = null,
)

enum class ConnectionStatus { Unknown, Checking, Connected, Failed }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val channelService = PhoenixChannelService(application)
    private val settingsRepo = SettingsRepository(application)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val _directionsTestResult = MutableStateFlow<String?>(null)
    val directionsTestResult = _directionsTestResult.asStateFlow()

    private val _healthConnectTestResult = MutableStateFlow<String?>(null)
    val healthConnectTestResult = _healthConnectTestResult.asStateFlow()

    val settings = settingsRepo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgentSettings(),
    )

    private var messageIdCounter = 0L
    private fun nextId() = ++messageIdCounter

    init {
        channelService.connect()
        viewModelScope.launch {
            channelService.events.collect { event ->
                when (event) {
                    is ChannelEvent.Thinking -> Unit // spinner already shown on send
                    is ChannelEvent.AssistantMessage -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages
                                .filterNot { it is UiMessage.Thinking }
                                + UiMessage.Assistant(nextId(), event.text),
                            isThinking = false,
                            connectionError = null,
                        )
                    }
                    is ChannelEvent.ToolActivity -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages
                                + UiMessage.ToolActivity(nextId(), "🔧 ${event.name}"),
                            connectionError = null,
                        )
                    }
                    is ChannelEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages
                                .filterNot { it is UiMessage.Thinking },
                            isThinking = false,
                            connectionError = event.message,
                        )
                    }
                }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun send() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isThinking) return

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages
                + UiMessage.User(nextId(), text)
                + UiMessage.Thinking(nextId()),
            inputText = "",
            isThinking = true,
        )

        channelService.sendMessage(text)
    }

    fun clearHistory() {
        channelService.clearHistory()
        _uiState.value = _uiState.value.copy(messages = emptyList(), isThinking = false)
    }

    fun checkConnection() {
        _uiState.value = _uiState.value.copy(connectionStatus = ConnectionStatus.Checking)
        viewModelScope.launch {
            val result = channelService.ping()
            _uiState.value = _uiState.value.copy(
                connectionStatus = if (result.isSuccess) ConnectionStatus.Connected else ConnectionStatus.Failed,
            )
        }
    }

    fun testDirections() {
        _directionsTestResult.value = "Testing…"
        viewModelScope.launch {
            val result = runCatching {
                channelService.testDirections("Grand Central Terminal, New York")
            }
            _directionsTestResult.value = result.fold(
                onSuccess = { it },
                onFailure = { "Error: ${it.message}" },
            )
        }
    }

    private val _scheduleStatus = MutableStateFlow<String?>(null)
    val scheduleStatus = _scheduleStatus.asStateFlow()

    fun checkSchedule() {
        val wm = WorkManager.getInstance(getApplication())
        val names = listOf("run_brief", "commute_brief", "run_reminder")
        viewModelScope.launch {
            _scheduleStatus.value = names.map { name ->
                val infos = wm.getWorkInfosForUniqueWorkFlow(name).first()
                if (infos.isEmpty()) {
                    "$name: NOT SCHEDULED"
                } else {
                    val info = infos.first()
                    val stateLabel = when (info.state) {
                        WorkInfo.State.ENQUEUED  -> "ENQUEUED"
                        WorkInfo.State.RUNNING   -> "RUNNING"
                        WorkInfo.State.SUCCEEDED -> "SUCCEEDED"
                        WorkInfo.State.FAILED    -> "FAILED"
                        WorkInfo.State.BLOCKED   -> "BLOCKED"
                        WorkInfo.State.CANCELLED -> "CANCELLED"
                    }
                    "$name: $stateLabel"
                }
            }.joinToString("\n")
        }
    }

    fun fireRunBriefNow() {
        WorkManager.getInstance(getApplication())
            .enqueue(OneTimeWorkRequestBuilder<RunningWeatherWorker>().build())
    }

    fun fireRunReminderNow() {
        WorkManager.getInstance(getApplication())
            .enqueue(OneTimeWorkRequestBuilder<RunReminderWorker>().build())
    }

    fun fireCommuteNow() {
        WorkManager.getInstance(getApplication())
            .enqueue(OneTimeWorkRequestBuilder<CommuteWorker>().build())
    }

    fun testHealthConnect() {
        _healthConnectTestResult.value = "Reading…"
        viewModelScope.launch {
            _healthConnectTestResult.value = HealthConnectHelper(getApplication()).debugSummary()
        }
    }

    fun saveSettings(new: AgentSettings) {
        viewModelScope.launch {
            settingsRepo.save(new)
            channelService.connect()
        }
    }

    // ── OTA builds ───────────────────────────────────────────────────────────

    private val _buildStatus = MutableStateFlow<String?>(null)
    val buildStatus = _buildStatus.asStateFlow()

    fun triggerBuild() {
        viewModelScope.launch {
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            _buildStatus.value = "Starting build…"
            runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder()
                            .url("$serverUrl/api/build/trigger")
                            .post("".toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute().close()
                }
            }.onFailure {
                _buildStatus.value = "Error: ${it.message}"
                return@launch
            }
            // Poll status until done or error
            repeat(120) {
                delay(3_000)
                val body = runCatching {
                    withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url("$serverUrl/api/build/status").build())
                            .execute().use { it.body?.string() ?: "" }
                    }
                }.getOrElse { return@repeat }

                val obj = json.parseToJsonElement(body).jsonObject
                val status = obj["status"]?.jsonPrimitive?.content ?: return@repeat
                val message = obj["message"]?.jsonPrimitive?.content ?: ""
                _buildStatus.value = "[$status] $message"
                if (status == "done" || status == "error") return@launch
            }
        }
    }

    fun checkBuilds() {
        viewModelScope.launch {
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            runCatching {
                val body = withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder().url("$serverUrl/api/build/list").build()
                    ).execute().use { it.body?.string() ?: "[]" }
                }

                val arr = json.parseToJsonElement(body).jsonArray
                if (arr.isEmpty()) {
                    _buildStatus.value = "No builds available"
                    return@launch
                }
                _buildStatus.value = arr.joinToString("\n") { el ->
                    val obj = el.jsonObject
                    val filename = obj["filename"]?.jsonPrimitive?.content ?: ""
                    val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val url = obj["url"]?.jsonPrimitive?.content ?: ""
                    "$filename  (${size / 1024}KB)  $url"
                }
            }.onFailure { _buildStatus.value = "Error: ${it.message}" }
        }
    }

    fun downloadAndInstall(filename: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val serverUrl = settingsRepo.settings.first().serverUrl.trimEnd('/')
            _buildStatus.value = "Downloading $filename…"
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = http.newCall(
                        Request.Builder().url("$serverUrl/builds/$filename").build()
                    ).execute().use { it.body?.bytes() ?: error("Empty response") }

                    val dest = File(app.cacheDir, "wigglebot-update.apk")
                    java.io.FileOutputStream(dest).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                        fos.fd.sync()
                    }
                    val uri = FileProvider.getUriForFile(
                        app, "${app.packageName}.fileprovider", dest
                    )
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    }
                    app.startActivity(intent)
                    _buildStatus.value = "Install prompt launched"
                }.onFailure { _buildStatus.value = "Download failed: ${it.message}" }
            }
        }
    }
}
