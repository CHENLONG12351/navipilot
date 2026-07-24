package com.example.navipilot.navigation

import android.util.Log
import com.example.navipilot.CarrotManFields
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * NaviStreamManager — v2 JSON 数据流管理器
 *
 * 职责：
 * 1. 管理所有 13 路 JSON 数据流的发送逻辑
 * 2. 变化检测（dirty checking）— 仅数据变化时发送（on_change 模式）
 * 3. Absent 信号管理 — 数据过期/不可用时发送 present=false
 * 4. 将高德广播数据映射为 v2 协议的数据结构
 *
 * 每个流在 on_change 模式下周期性检查数据变化，
 * 检测到变化或心跳超时时发送数据。
 */
class NaviStreamManager(
    private val client: NaviWebSocketV2Client,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "NaviStreamMgr"
        const val CHECK_INTERVAL_MS = 200L       // 数据检查间隔
        private const val STALE_TIMEOUT_MS = 5000L       // 数据过期超时
        private const val ABSENT_CHECK_INTERVAL_MS = 3000L // absent 信号检查间隔
    }

    // ─── 缓存的上次发送值（用于变化检测） ───
    private val lastSentValues = mutableMapOf<String, String>()
    private val lastSentTimestamps = mutableMapOf<String, Long>()
    private val streamStates = mutableMapOf<String, StreamState>()

    // ─── 协程任务 ───
    private var checkJob: Job? = null
    private var absentJob: Job? = null

    // ─── 是否启用 absent 信号发送 ───
    private var streamEnabledFlags = NaviV2Constants.ENABLED_JSON_STREAMS
        .filter { it !in listOf("camera_state", "composition_state", "crossroad") }
        .associateWith { true }
        .toMutableMap()

    /** 流状态 */
    data class StreamState(
        var present: Boolean = false,
        var lastUpdateMs: Long = 0L,
        var isDirty: Boolean = false
    )

    // ═══════════════════════════════════════════════════════════════
    // 公共 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动流管理器
     */
    fun start() {
        Log.i(TAG, "启动流管理器")
        startCheckJob()
        startAbsentJob()
    }

    /**
     * 停止流管理器
     */
    fun stop() {
        checkJob?.cancel()
        absentJob?.cancel()
        lastSentValues.clear()
        lastSentTimestamps.clear()
        streamStates.clear()
    }

    /**
     * 设置特定流是否启用
     */
    fun setStreamEnabled(name: String, enabled: Boolean) {
        streamEnabledFlags[name] = enabled
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据更新入口（由外部调用触发）
    // ═══════════════════════════════════════════════════════════════

    /** 标记流数据已更新 */
    fun markUpdated(name: String) {
        val state = streamStates.getOrPut(name) { StreamState() }
        state.present = true
        state.lastUpdateMs = System.currentTimeMillis()
        state.isDirty = true
    }

    // ═══════════════════════════════════════════════════════════════
    // 各数据流的映射与发送（由外部线程安全调用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送 vehicle 数据
     */
    fun sendVehicle(fields: CarrotManFields) {
        val data = VehicleData(
            lat = fields.vpPosPointLat,
            lon = fields.vpPosPointLon,
            headingDeg = fields.nPosAngle,
            speedKph = fields.nPosSpeed,
            roadName = fields.szPosRoadName.ifEmpty { "" },
            virtualGps = false
        )
        sendWithCheck("vehicle", data.toJson())
        markUpdated("vehicle")
    }

    /**
     * 发送 guidance_current 数据
     */
    fun sendGuidanceCurrent(fields: CarrotManFields) {
        val data = GuidanceData(
            distanceM = fields.nTBTDist.coerceAtLeast(0),
            turnType = fields.nTBTTurnType,
            mainText = fields.szTBTMainText.ifEmpty { "" },
            nearDirection = fields.szNearDirName.ifEmpty { "" },
            farDirection = fields.szFarDirName.ifEmpty { "" }
        )
        sendWithCheck("guidance_current", data.toJson())
        markUpdated("guidance_current")
    }

    /**
     * 发送 guidance_next 数据
     */
    fun sendGuidanceNext(fields: CarrotManFields) {
        val data = GuidanceData(
            distanceM = fields.nTBTDistNext.coerceAtLeast(0),
            turnType = fields.nTBTTurnTypeNext,
            mainText = fields.szTBTMainTextNext.ifEmpty { "" },
            nearDirection = "",
            farDirection = ""
        )
        sendWithCheck("guidance_next", data.toJson())
        markUpdated("guidance_next")
    }

    /**
     * 发送 navigation_status 数据
     */
    fun sendNavigationStatus(fields: CarrotManFields) {
        val mode = when {
            !fields.isNavigating -> "idle"
            fields.isOffRoute -> "off_route"
            else -> "guiding"
        }
        val data = NavigationStatusData(
            mode = mode,
            guidanceActive = fields.isNavigating,
            offRoute = fields.isOffRoute,
            routePresent = fields.nGoPosDist > 0
        )
        sendWithCheck("navigation_status", data.toJson())
        markUpdated("navigation_status")
    }

    /**
     * 发送 route 数据
     * 注意：高德广播不提供 polyline，发送空数组
     */
    fun sendRoute(fields: CarrotManFields) {
        val data = RouteData(
            remainDistanceM = fields.nGoPosDist.coerceAtLeast(0),
            remainTimeSec = fields.nGoPosTime.coerceAtLeast(0),
            movedDistanceM = 0,
            movedTimeSec = 0,
            totalDistanceM = fields.nGoPosDist.coerceAtLeast(0),
            polyline = emptyList()
        )
        sendWithCheck("route", data.toJson())
        markUpdated("route")
    }

    /**
     * 发送 speed 数据
     */
    fun sendSpeed(fields: CarrotManFields) {
        val hasSection = fields.nSdiBlockType > 0
        val data = SpeedData(
            currentKph = fields.nPosSpeed,
            roadLimitKph = fields.nRoadLimitSpeed.takeIf { it > 0 },
            sdiType = fields.nSdiType,
            sdiDistanceM = fields.nSdiDist.coerceAtLeast(0),
            sdiSpeedLimitKph = fields.nSdiSpeedLimit.coerceAtLeast(0),
            sdiSectionType = fields.nSdiSection,
            sdiBlockType = fields.nSdiBlockType,
            sdiBlockSpeedKph = fields.nSdiBlockSpeed.coerceAtLeast(0),
            sdiBlockDistanceM = fields.nSdiBlockDist.coerceAtLeast(0),
            sectionActive = hasSection,
            sectionSpeedLimitKph = fields.nSdiBlockSpeed.coerceAtLeast(0),
            sectionAverageKph = fields.nSdiAverageSpeed.toDouble().coerceAtLeast(0.0),
            sectionRemainingDistanceM = fields.nSdiBlockDist.toDouble().coerceAtLeast(0.0),
            sectionRemainingTimeSec = 0,
            sectionProgress = 0.0,
            sectionSuspended = false,
            sectionOffRoute = false
        )
        sendWithCheck("speed", data.toJson())
        markUpdated("speed")
    }

    /**
     * 发送 traffic_signal 数据
     */
    fun sendTrafficSignal(fields: CarrotManFields) {
        val visible = fields.trafficLightState >= 0
        val data = TrafficSignalData(
            visible = visible,
            distanceM = fields.nTBTDist.coerceAtLeast(0),
            redValid = visible && fields.trafficLightState == 1,
            redOn = fields.trafficLightState == 1,
            redRemainSec = fields.trafficLightCountdown.coerceAtLeast(0),
            greenValid = visible && fields.trafficLightState == 2,
            greenOn = fields.trafficLightState == 2,
            greenRemainSec = 0
        )
        sendWithCheck("traffic_signal", data.toJson())
        markUpdated("traffic_signal")
    }

    /**
     * 发送 lane_current 数据
     */
    fun sendLaneCurrent(fields: CarrotManFields) {
        val lanes = fields.laneInfoList.mapIndexed { index, lane ->
            LaneItem(
                index = index,
                direction = lane.id,
                isRecommended = lane.isRecommended,
                isAvailable = true
            )
        }
        val data = LaneData(
            laneCount = fields.nLaneCount.coerceAtLeast(0),
            lanes = lanes,
            recommendedLane = lanes.indexOfFirst { it.isRecommended }.coerceAtLeast(0)
        )
        sendWithCheck("lane_current", data.toJson())
        markUpdated("lane_current")
    }

    /**
     * 发送 lane_ahead 数据（服务端要求 value 必须是数组）
     */
    fun sendLaneAhead(fields: CarrotManFields) {
        // 高德广播不区分当前/前方车道，复用 lane_current 数据
        val lanes = fields.laneInfoList.mapIndexed { index, lane ->
            LaneItem(
                index = index,
                direction = lane.id,
                isRecommended = lane.isRecommended,
                isAvailable = true
            )
        }
        val jsonArray = org.json.JSONArray()
        lanes.forEach { lane ->
            jsonArray.put(org.json.JSONObject().apply {
                put("index", lane.index)
                put("direction", lane.direction)
                put("is_recommended", lane.isRecommended)
                put("is_available", lane.isAvailable)
            })
        }
        val jsonStr = jsonArray.toString()
        val lastJson = lastSentValues["lane_ahead"]
        if (jsonStr == lastJson) return
        val success = client.sendJsonData("lane_ahead", present = true, value = jsonArray)
        if (success) {
            lastSentValues["lane_ahead"] = jsonStr
            lastSentTimestamps["lane_ahead"] = System.currentTimeMillis()
        }
        markUpdated("lane_ahead")
    }

    /**
     * 发送 app_status 数据
     */
    fun sendAppStatus(foreground: Boolean) {
        val data = AppStatusData(foreground = foreground)
        sendWithCheck("app_status", data.toJson())
        markUpdated("app_status")
    }

    /**
     * 发送 crossroad 数据（始终 absent）
     */
    fun sendCrossroadAbsent() {
        client.sendJsonData("crossroad", present = false, null, reason = "source_absent")
    }

    /**
     * 发送 camera_state 数据（始终 absent）
     */
    fun sendCameraStateAbsent() {
        client.sendJsonData("camera_state", present = false, null, reason = "source_absent")
    }

    /**
     * 发送 composition_state 数据（始终 absent）
     */
    fun sendCompositionStateAbsent() {
        client.sendJsonData("composition_state", present = false, null, reason = "source_absent")
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 带变化检测的发送
     */
    private fun sendWithCheck(name: String, json: JSONObject) {
        if (!isStreamEnabled(name)) return
        val jsonStr = json.toString()
        val lastJson = lastSentValues[name]
        // on_change 模式：仅当数据有变化时发送
        if (jsonStr == lastJson) return
        val success = client.sendJsonData(name, present = true, value = jsonStr.toJsonValue())
        if (success) {
            lastSentValues[name] = jsonStr
            lastSentTimestamps[name] = System.currentTimeMillis()
        }
    }

    private fun isStreamEnabled(name: String): Boolean {
        return streamEnabledFlags.getOrDefault(name, false)
    }

    /**
     * 将 JSON 字符串转为可序列化的对象
     */
    private fun String.toJsonValue(): Any {
        return try {
            JSONObject(this)
        } catch (e: Exception) {
            this
        }
    }

    /**
     * 启动数据检查协程
     */
    private fun startCheckJob() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkAndSendAbsent()
            }
        }
    }

    /**
     * 启动 absent 信号检查协程
     */
    private fun startAbsentJob() {
        absentJob?.cancel()
        absentJob = scope.launch {
            while (isActive) {
                delay(ABSENT_CHECK_INTERVAL_MS)
                checkAndSendAbsent()
            }
        }
    }

    /**
     * 检查过期的流并发送 absent 信号
     */
    private fun checkAndSendAbsent() {
        if (!client.isReady()) return
        val now = System.currentTimeMillis()

        // 始终发送 camera_state / composition_state / crossroad 为 absent
        sendCrossroadAbsent()
        sendCameraStateAbsent()
        sendCompositionStateAbsent()

        // 检查其他流是否过期
        NaviV2Constants.ENABLED_JSON_STREAMS
            .filter { it !in listOf("camera_state", "composition_state", "crossroad") }
            .forEach { name ->
                val state = streamStates[name]
                val lastSent = lastSentTimestamps[name] ?: 0L
                if (state != null && state.present && (now - state.lastUpdateMs > STALE_TIMEOUT_MS)) {
                    // 数据过期，发送 absent
                    if (now - lastSent > STALE_TIMEOUT_MS) {
                        client.sendJsonData(name, present = false, null, reason = "stale")
                        state.present = false
                        state.isDirty = false
                    }
                }
            }
    }

    /**
     * 调试快照：返回每个流当前状态摘要（用于 7706 调试面板）
     */
    fun debugSnapshot(): org.json.JSONObject = org.json.JSONObject().apply {
        put("streamCount", lastSentValues.size)
        put("presentCount", streamStates.count { it.value.present })
        NaviV2Constants.ENABLED_JSON_STREAMS.forEach { name ->
            val json = org.json.JSONObject()
            val state = streamStates[name]
            val lastVal = lastSentValues[name]
            val lastTs = lastSentTimestamps[name] ?: 0L
            val now = System.currentTimeMillis()
            json.put("present", state?.present ?: false)
            json.put("ageMs", if (lastTs > 0) (now - lastTs) else -1)
            json.put("value", if (lastVal != null && lastVal.length <= 120) lastVal else "${(lastVal?.length ?: 0)} chars")
            put(name, json)
        }
    }
}
