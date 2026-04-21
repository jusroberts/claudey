package com.wiggletonabbey.wigglebot.service

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.wiggletonabbey.wigglebot.BuildConfig
import com.wiggletonabbey.wigglebot.tools.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PhoenixChannelService"

sealed interface ChannelEvent {
    data object Thinking : ChannelEvent
    data class AssistantMessage(val text: String) : ChannelEvent
    data class ToolActivity(val name: String) : ChannelEvent
    data class Error(val message: String) : ChannelEvent
}

class PhoenixChannelService(private val context: Context) {

    private val settingsRepo = SettingsRepository(context)
    private val dispatcher = ToolDispatcher(context).also {
        it.googleMapsApiKey = BuildConfig.GOOGLE_MAPS_API_KEY
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val clientId = UUID.randomUUID().toString()
    private val refCounter = AtomicInteger(0)
    private fun nextRef() = refCounter.incrementAndGet().toString()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ChannelEvent> = _events.asSharedFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var joinRef: String? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect() {
        scope.launch {
            val settings = settingsRepo.settings.first()
            connectWithSettings(settings)
        }
    }

    private fun connectWithSettings(settings: AgentSettings) {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        ws?.close(1000, "reconnecting")
        ws = null
        joinRef = null

        val wsUrl = settings.serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/socket/websocket?vsn=1.0.0"

        Log.d(TAG, "Connecting to $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(request, PhoenixWebSocketListener(settings))
    }

    fun sendMessage(text: String) {
        val jRef = joinRef ?: return
        val loc = lastKnownLocation()
        val msg = buildJsonObject {
            put("join_ref", jRef)
            put("ref", nextRef())
            put("topic", "agent:$clientId")
            put("event", "user_message")
            put("payload", buildJsonObject {
                put("text", text)
                if (loc != null) {
                    put("lat", loc.first)
                    put("lon", loc.second)
                }
            })
        }
        ws?.send(msg.toString())
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            ?.let { it.latitude to it.longitude }
    }

    fun clearHistory() {
        val jRef = joinRef ?: return
        val msg = buildJsonObject {
            put("join_ref", jRef)
            put("ref", nextRef())
            put("topic", "agent:$clientId")
            put("event", "clear_history")
            put("payload", buildJsonObject {})
        }
        ws?.send(msg.toString())
    }

    suspend fun ping(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsRepo.settings.first()
            val url = settings.serverUrl.trimEnd('/') + "/health"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) listOf("ok")
                else error("HTTP ${response.code}")
            }
        }
    }

    suspend fun testDirections(destination: String): String =
        dispatcher.testTransit(destination)

    private fun sendJoin(webSocket: WebSocket) {
        val ref = nextRef()
        joinRef = ref
        val msg = buildJsonObject {
            put("join_ref", ref)
            put("ref", ref)
            put("topic", "agent:$clientId")
            put("event", "phx_join")
            put("payload", buildJsonObject {})
        }
        webSocket.send(msg.toString())
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(30_000)
                val msg = buildJsonObject {
                    put("join_ref", JsonNull)
                    put("ref", nextRef())
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", buildJsonObject {})
                }
                webSocket.send(msg.toString())
            }
        }
    }

    private fun scheduleReconnect(settings: AgentSettings) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5_000)
            connectWithSettings(settings)
        }
    }

    private inner class PhoenixWebSocketListener(
        private val settings: AgentSettings,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS opened")
            sendJoin(webSocket)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "WS msg: $text")
            val obj = runCatching {
                json.parseToJsonElement(text).jsonObject
            }.getOrNull() ?: return

            val event = obj["event"]?.jsonPrimitive?.content ?: return
            val payload = obj["payload"]?.jsonObject ?: return

            when (event) {
                "phx_reply" -> Unit
                "thinking" -> scope.launch { _events.emit(ChannelEvent.Thinking) }
                "assistant_message" -> {
                    val msgText = payload["text"]?.jsonPrimitive?.content ?: ""
                    scope.launch { _events.emit(ChannelEvent.AssistantMessage(msgText)) }
                }
                "tool_request" -> {
                    val toolCallId = payload["tool_call_id"]?.jsonPrimitive?.content ?: return
                    val name = payload["name"]?.jsonPrimitive?.content ?: return
                    val args = payload["args"]?.jsonObject
                    scope.launch {
                        _events.emit(ChannelEvent.ToolActivity(name))
                        val result = dispatcher.dispatch(name, args)
                        val jRef = joinRef ?: return@launch
                        val reply = buildJsonObject {
                            put("join_ref", jRef)
                            put("ref", nextRef())
                            put("topic", "agent:$clientId")
                            put("event", "tool_result")
                            put("payload", buildJsonObject {
                                put("tool_call_id", toolCallId)
                                put("result", result)
                            })
                        }
                        webSocket.send(reply.toString())
                    }
                }
                "error" -> {
                    val msg = payload["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    scope.launch { _events.emit(ChannelEvent.Error(msg)) }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure", t)
            heartbeatJob?.cancel()
            scope.launch { _events.emit(ChannelEvent.Error("Disconnected: ${t.message}")) }
            scheduleReconnect(settings)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed $code $reason")
            heartbeatJob?.cancel()
            if (code != 1000) scheduleReconnect(settings)
        }
    }
}
