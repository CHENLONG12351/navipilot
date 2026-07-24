package com.example.navipilot.navigation

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * NaviWebSocketV2Client — Carrot Navi v2 WebSocket 客户端
 *
 * 职责：
 * 1. 控制 WebSocket 连接（会话协商、心跳、重连）
 * 2. 管理 13 路 JSON 数据流 WebSocket
 * 3. 自动发现设备 IP、会话生命周期管理
 *
 * 对应服务端 carrot_navi.py 的 ws_control / ws_json 处理逻辑
 */
class NaviWebSocketV2Client(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "NaviV2Client"
        private const val WS_CONTROL_PATH = "/api/navi/ws/v2/control/%s"
        private const val WS_JSON_PATH = "/api/navi/ws/v2/json/%s/%s"
        private const val APP_VERSION = "amap_auto_1.0"
    }

    // ─── OkHttp 客户端（复用现有配置） ───
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(NaviV2Constants.CONTROL_HEARTBEAT_S, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ─── 状态 ───
    private val _connectionState = MutableStateFlow(NaviV2ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    // 当前连接的目标 IP
    private var targetHost: String = ""
    private var targetPort: Int = NaviV2Constants.DEFAULT_PORT

    // 会话信息
    private var currentSession: NaviV2Session? = null

    // WebSocket 实例
    private var controlWs: WebSocket? = null
    private val jsonStreams: ConcurrentHashMap<String, WebSocket> = ConcurrentHashMap()

    // 每个流的序列号
    private val streamSequences: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

    // 控制重连
    private var controlReconnectJob: Job? = null
    private var controlFailCount = 0
    private var discoveryReceived = false

    // 数据发送协程
    private var sendJob: Job? = null

    // ─── 回调接口 ───
    /** 会话建立成功回调 */
    var onSessionReady: ((NaviV2Session) -> Unit)? = null
    /** 连接状态变化回调 */
    var onStateChanged: ((NaviV2ConnectionState) -> Unit)? = null
    /** 错误回调 */
    var onError: ((String) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════
    // 公共 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置目标设备 IP（来自 UDP 发现或用户配置）
     */
    fun setTarget(host: String, port: Int = NaviV2Constants.DEFAULT_PORT) {
        targetHost = host
        targetPort = port
        discoveryReceived = true
        Log.i(TAG, "目标设备已设置: $host:$port")
    }

    /**
     * 启动连接流程
     */
    fun start() {
        if (targetHost.isBlank()) {
            Log.w(TAG, "未设置目标 IP，等待发现...")
            _connectionState.value = NaviV2ConnectionState.DISCOVERING
            return
        }
        connectControl()
    }

    /**
     * 停止所有连接
     */
    fun stop() {
        Log.i(TAG, "停止所有连接")
        controlReconnectJob?.cancel()
        sendJob?.cancel()
        closeAllStreams()
        closeControlWs()
        currentSession = null
        _connectionState.value = NaviV2ConnectionState.DISCONNECTED
        discoveryReceived = false
    }

    /**
     * 获取流的下一个序列号
     */
    fun nextSequence(kind: String, name: String): Long {
        val key = "$kind:$name"
        return streamSequences.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
    }

    /**
     * 获取当前会话
     */
    fun getSession(): NaviV2Session? = currentSession

    /**
     * 获取流配置
     */
    fun getStreamConfig(kind: String, name: String): StreamConfig? {
        return currentSession?.streams?.get("$kind:$name")
    }

    /**
     * 检查会话是否就绪
     */
    fun isReady(): Boolean = currentSession != null && controlWs != null

    // ═══════════════════════════════════════════════════════════════
    // 控制 WebSocket 管理
    // ═══════════════════════════════════════════════════════════════

    private fun connectControl() {
        if (targetHost.isBlank()) {
            Log.e(TAG, "无法连接控制 WS：目标 IP 为空")
            return
        }

        _connectionState.value = NaviV2ConnectionState.CONTROL_CONNECTING
        val url = "ws://$targetHost:$targetPort${WS_CONTROL_PATH.format(APP_VERSION)}"
        Log.i(TAG, "连接控制 WS: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        controlWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "控制 WS 已打开")
                controlFailCount = 0
                // 连接成功后立即发送协商请求
                sendRequirementsQuery(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleControlMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "控制 WS 关闭中: code=$code reason=$reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "控制 WS 已关闭: code=$code reason=$reason")
                handleControlDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "控制 WS 失败: ${t.message}", t)
                handleControlDisconnected()
            }
        })
    }

    private fun closeControlWs() {
        controlWs?.close(1000, "client stop")
        controlWs = null
    }

    private fun handleControlDisconnected() {
        controlWs = null
        controlFailCount++
        if (controlFailCount >= NaviV2Constants.CONTROL_FAIL_RESET_THRESHOLD) {
            Log.w(TAG, "控制 WS 连续失败 $controlFailCount 次，重置发现状态")
            discoveryReceived = false
            _connectionState.value = NaviV2ConnectionState.DISCOVERING
        } else {
            _connectionState.value = NaviV2ConnectionState.RECONNECTING
            scheduleControlReconnect()
        }
    }

    private fun scheduleControlReconnect() {
        controlReconnectJob?.cancel()
        val delay = (NaviV2Constants.CONTROL_RECONNECT_DELAY_MS *
                (1 shl (controlFailCount.coerceAtMost(4) - 1)))
            .coerceAtMost(NaviV2Constants.MAX_RECONNECT_DELAY_MS)
        controlReconnectJob = scope.launch {
            delay(delay)
            connectControl()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 会话协商
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送 requirements_query 进行会话协商
     */
    private fun sendRequirementsQuery(ws: WebSocket) {
        _connectionState.value = NaviV2ConnectionState.NEGOTIATING
        Log.i(TAG, "发送会话协商请求...")

        val query = JSONObject().apply {
            put("type", "requirements_query")
            put("protocol_version", NaviV2Constants.PROTOCOL_VERSION)
            put("timestamp_ms", System.currentTimeMillis())
            put("app_version", APP_VERSION)
            put("catalog_revision", NaviV2Constants.CATALOG_REVISION)
            put("limits", JSONObject().apply {
                put("max_binary_frame_bytes", NaviV2Constants.MAX_MESSAGE_BYTES)
                put("max_total_bitrate_kbps", 12000)
            })
            put("streams", JSONArray().apply {
                NaviV2Constants.CATALOG.forEach { (kind, name) ->
                    put(JSONObject().apply {
                        put("kind", kind)
                        put("name", name)
                        put("schema_version", 1)
                        put("nullable", true)
                        put("supported_params", JSONObject().apply {
                            put("delivery_mode", JSONArray().apply {
                                put("on_change")
                                put("periodic")
                                put("on_change_with_heartbeat")
                            })
                            put("interval_ms", JSONObject().apply {
                                put("min", 200)
                                put("max", 5000)
                                put("default", 1000)
                            })
                        })
                    })
                }
            })
        }

        Log.d(TAG, "发送 requirements_query: ${query.toString().take(200)}...")
        ws.send(query.toString())
    }

    /**
     * 处理控制消息
     */
    private fun handleControlMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "subscription_manifest" -> handleManifest(json)
                "protocol_error" -> handleProtocolError(json)
                else -> Log.d(TAG, "收到控制消息: type=$type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析控制消息失败: ${e.message}", e)
        }
    }

    /**
     * 处理 subscription_manifest
     */
    private fun handleManifest(json: JSONObject) {
        val sessionId = json.optString("session_id", "")
        val revision = json.optInt("revision", 0)

        if (sessionId.isBlank()) {
            Log.e(TAG, "manifest 缺少 session_id")
            onError?.invoke("manifest 无效: 缺少 session_id")
            return
        }

        Log.i(TAG, "收到 subscription_manifest: session_id=$sessionId revision=$revision")

        // 解析流配置
        val streams = mutableMapOf<String, StreamConfig>()
        val streamsArray = json.optJSONArray("streams")
        if (streamsArray != null) {
            for (i in 0 until streamsArray.length()) {
                val streamJson = streamsArray.getJSONObject(i)
                val kind = streamJson.optString("kind", "")
                val name = streamJson.optString("name", "")
                val key = "$kind:$name"
                streams[key] = StreamConfig(
                    kind = kind,
                    name = name,
                    schemaVersion = streamJson.optInt("schema_version", 1),
                    streamHandle = streamJson.optInt("stream_handle", 0),
                    enabled = streamJson.optBoolean("enabled", true),
                    params = parseParams(streamJson.optJSONObject("params"))
                )
            }
        }

        currentSession = NaviV2Session(
            sessionId = sessionId,
            revision = revision,
            streams = streams
        )

        // 发送 manifest_applied 确认
        sendManifestApplied(sessionId, revision, streams)

        // 关闭旧流的连接（防止会话重建时泄漏）
        closeAllStreams()

        // 打开所有启用的 JSON 流
        openJsonStreams()

        _connectionState.value = NaviV2ConnectionState.CONNECTED
        onSessionReady?.let { it(currentSession!!) }
    }

    /**
     * 发送 manifest_applied 确认
     */
    private fun sendManifestApplied(sessionId: String, revision: Int, streams: Map<String, StreamConfig>) {
        val applied = JSONObject().apply {
            put("type", "manifest_applied")
            put("protocol_version", NaviV2Constants.PROTOCOL_VERSION)
            put("timestamp_ms", System.currentTimeMillis())
            put("session_id", sessionId)
            put("revision", revision)
            put("effective_config", JSONArray().apply {
                streams.values.forEach { config ->
                    put(JSONObject().apply {
                        put("kind", config.kind)
                        put("name", config.name)
                        put("schema_version", config.schemaVersion)
                        put("stream_handle", config.streamHandle)
                        put("enabled", config.enabled)
                        put("params", JSONObject(config.params as Map<*, *>))
                    })
                }
            })
        }
        controlWs?.send(applied.toString())
        Log.i(TAG, "已发送 manifest_applied")
    }

    /**
     * 处理协议错误
     */
    private fun handleProtocolError(json: JSONObject) {
        val code = json.optString("code", "")
        val message = json.optString("message", "")
        val recoverable = json.optBoolean("recoverable", true)
        Log.e(TAG, "协议错误: code=$code message=$message recoverable=$recoverable")
        onError?.invoke("协议错误: $message")
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON 流管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 打开所有启用的 JSON 数据流
     */
    private fun openJsonStreams() {
        val session = currentSession ?: return
        Log.i(TAG, "打开 JSON 数据流...")

        NaviV2Constants.ENABLED_JSON_STREAMS.forEach { name ->
            val config = session.streams["json:$name"]
            if (config != null && config.enabled) {
                openJsonStream(session.sessionId, config)
            }
        }
    }

    /**
     * 打开单个 JSON 数据流
     */
    private fun openJsonStream(sessionId: String, config: StreamConfig) {
        val key = "json:${config.name}"
        val url = "ws://$targetHost:$targetPort${WS_JSON_PATH.format(sessionId, config.name)}"
        Log.d(TAG, "打开 JSON 流: ${config.name} -> $url")

        val request = Request.Builder()
            .url(url)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "JSON 流已打开: ${config.name}")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "JSON 流失败: ${config.name} - ${t.message}")
                // 失败时从 map 移除
                jsonStreams.remove(key)
                // 计划重连
                scope.launch {
                    delay(NaviV2Constants.CONTROL_RECONNECT_DELAY_MS)
                    if (currentSession != null) {
                        openJsonStream(currentSession!!.sessionId, config)
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "JSON 流关闭: ${config.name} code=$code")
                jsonStreams.remove(key)
            }
        })

        jsonStreams[key] = ws
    }

    /**
     * 发送 JSON 数据到指定流
     * 其他线程安全调用
     */
    fun sendJsonData(name: String, present: Boolean, value: Any?, reason: String? = null): Boolean {
        val session = currentSession ?: return false
        val config = session.streams["json:$name"] ?: return false
        val ws = jsonStreams["json:$name"] ?: return false
        val sequence = nextSequence("json", name)

        val envelope = JsonEnvelope(
            sessionId = session.sessionId,
            name = name,
            manifestRevision = session.revision,
            streamHandle = config.streamHandle,
            schemaVersion = config.schemaVersion,
            sequence = sequence,
            present = present,
            value = value,
            reason = reason
        )

        val jsonStr = envelope.toJson().toString()
        return ws.send(jsonStr)
    }

    /**
     * 关闭所有流
     */
    private fun closeAllStreams() {
        jsonStreams.values.forEach { it.close(1000, "session end") }
        jsonStreams.clear()
        streamSequences.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    private fun parseParams(paramsObj: JSONObject?): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        if (paramsObj != null) {
            paramsObj.keys().forEach { key ->
                paramsObj.opt(key)?.let { result[key] = it }
            }
        }
        return result
    }

    /**
     * 获取当前目标主机
     */
    fun getTargetHost(): String = targetHost

    /**
     * 调试快照：返回 v2 客户端当前状态摘要（用于 7706 调试面板）
     */
    fun debugSnapshot(): JSONObject = JSONObject().apply {
        put("state", _connectionState.value.name)
        put("target", targetHost.ifEmpty { "-" })
        put("port", targetPort)
        put("discoveryReceived", discoveryReceived)
        put("controlFailCount", controlFailCount)
        val session = currentSession
        if (session != null) {
            put("sessionId", session.sessionId)
            put("revision", session.revision)
            put("streamCount", session.streams.size)
        } else {
            put("sessionId", JSONObject.NULL)
        }
        // 各流序列号
        put("sequences", JSONObject().apply {
            streamSequences.forEach { (key, seq) ->
                put(key, seq.get())
            }
        })
        // 流连接状态
        put("streams", JSONObject().apply {
            NaviV2Constants.JSON_NAMES.forEach { name ->
                val key = "json:$name"
                val ws = jsonStreams[key]
                put(name, if (ws != null) "connected" else "closed")
            }
        })
    }
}
