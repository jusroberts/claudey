package com.wiggletonabbey.wigglebot.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TmuxChannelService"

class TmuxChannelService(
    private val serverUrl: String,
    private val sessionName: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refCounter = AtomicInteger(0)
    private fun nextRef() = refCounter.incrementAndGet().toString()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var joinRef: String? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val topic = "tmux:$sessionName"

    fun connect() {
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/socket/websocket?vsn=1.0.0"

        Log.d(TAG, "Connecting to $wsUrl topic=$topic")
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        ws?.close(1000, "done")
        ws = null
        _connected.value = false
    }

    fun sendInput(text: String) {
        val jRef = joinRef ?: return
        val msg = buildJsonObject {
            put("join_ref", jRef)
            put("ref", nextRef())
            put("topic", topic)
            put("event", "send_input")
            put("payload", buildJsonObject { put("text", text) })
        }
        ws?.send(msg.toString())
    }

    private fun sendJoin(webSocket: WebSocket) {
        val ref = nextRef()
        joinRef = ref
        val msg = buildJsonObject {
            put("join_ref", ref)
            put("ref", ref)
            put("topic", topic)
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

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS opened")
            sendJoin(webSocket)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val event = obj["event"]?.jsonPrimitive?.content ?: return
            val payload = obj["payload"]?.jsonObject ?: return

            when (event) {
                "phx_reply" -> {
                    val status = payload["status"]?.jsonPrimitive?.content
                    if (status == "ok") {
                        // initial snapshot may be in the reply payload response
                        val response = payload["response"]?.jsonObject
                        val snapshot = response?.get("snapshot")?.jsonPrimitive?.content
                        if (snapshot != null) {
                            _output.value = snapshot
                            _connected.value = true
                        } else {
                            _connected.value = true
                        }
                    } else if (status == "error") {
                        val reason = payload["response"]?.jsonObject
                            ?.get("reason")?.jsonPrimitive?.content ?: "unknown"
                        Log.e(TAG, "Join failed: $reason")
                    }
                }
                "output" -> {
                    val content = payload["content"]?.jsonPrimitive?.content ?: return
                    _output.value = content
                }
                "session_ended" -> {
                    Log.d(TAG, "Session ended")
                    _connected.value = false
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure", t)
            heartbeatJob?.cancel()
            _connected.value = false
            reconnectJob = scope.launch {
                delay(3_000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed $code $reason")
            heartbeatJob?.cancel()
            _connected.value = false
        }
    }
}
