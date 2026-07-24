package com.example.navipilot.navigation

import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
// NaviDataModels.kt — Carrot Navi v2 协议数据模型
// 对应 carrot_navi.py 中的协议常量和数据结构
// ═══════════════════════════════════════════════════════════════

// ─── 协议常量 ───

object NaviV2Constants {
    const val PROTOCOL_VERSION = 2
    const val CATALOG_REVISION = 1
    const val DEFAULT_PORT = 7714
    const val DISCOVERY_PORT = 7705
    const val CONTROL_HEARTBEAT_S = 15L
    const val STREAM_HEARTBEAT_S = 10L
    const val CONTROL_RECONNECT_DELAY_MS = 2000L
    const val MAX_RECONNECT_DELAY_MS = 30000L
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
    const val STALE_TIMEOUT_MS = 10000L
    const val SEND_INTERVAL_MS = 200L
    const val CHECK_INTERVAL_MS = 1000L
    const val CONTROL_FAIL_RESET_THRESHOLD = 3

    /** 所有 JSON 流名称（按协议顺序） */
    val JSON_NAMES = listOf(
        "vehicle", "guidance_current", "guidance_next", "lane_current",
        "lane_ahead", "speed", "traffic_signal", "crossroad", "route",
        "navigation_status", "app_status", "camera_state", "composition_state"
    )

    /** 所有 IMAGE 流名称（跳过不实现，但 manifest 需要） */
    val IMAGE_NAMES = listOf(
        "tbt_current_compact", "tbt_current_full", "tbt_next",
        "traffic_signal", "lane_top", "lane_bottom",
        "safety_primary", "safety_secondary", "safety_section",
        "crossroad_minimized", "crossroad_expanded",
        "center_tbt_icon", "center_tbt_text", "center_tbt_fee"
    )

    /** 所有 RENDER 流名称 */
    val RENDER_NAMES = listOf("map_main")

    /** 完整 CATALOG（28 项） */
    val CATALOG: List<Pair<String, String>> by lazy {
        val list = mutableListOf<Pair<String, String>>()
        JSON_NAMES.forEach { list.add("json" to it) }
        IMAGE_NAMES.forEach { list.add("image" to it) }
        RENDER_NAMES.forEach { list.add("render" to it) }
        list
    }

    /** 实际会启用的 JSON 流（不包括 image/render） */
    val ENABLED_JSON_STREAMS = JSON_NAMES
}

// ─── 会话状态 ───

/** WebSocket v2 连接状态 */
enum class NaviV2ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONTROL_CONNECTING,
    NEGOTIATING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/** 会话信息 */
data class NaviV2Session(
    val sessionId: String = "",
    val revision: Int = 0,
    val streams: Map<String, StreamConfig> = emptyMap()  // key = "kind:name"
)

/** 单个流的配置 */
data class StreamConfig(
    val kind: String,
    val name: String,
    val schemaVersion: Int = 1,
    val streamHandle: Int = 0,
    val enabled: Boolean = true,
    val params: Map<String, Any> = emptyMap()
)

// ─── 消息信封 ───

/** v2 JSON 消息信封 */
data class JsonEnvelope(
    val type: String = "item_update",
    val protocolVersion: Int = NaviV2Constants.PROTOCOL_VERSION,
    val sessionId: String,
    val kind: String = "json",
    val name: String,
    val manifestRevision: Int,
    val streamHandle: Int,
    val schemaVersion: Int = 1,
    val sequence: Long,
    val sourceTimestampMs: Long = System.currentTimeMillis(),
    val sentAtMs: Long = System.currentTimeMillis(),
    val present: Boolean,
    val value: Any? = null,
    val reason: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("protocol_version", protocolVersion)
        put("session_id", sessionId)
        put("kind", kind)
        put("name", name)
        put("manifest_revision", manifestRevision)
        put("stream_handle", streamHandle)
        put("schema_version", schemaVersion)
        put("sequence", sequence)
        put("source_timestamp_ms", sourceTimestampMs)
        put("sent_at_ms", sentAtMs)
        put("present", present)
        // 始终写入 value 字段（服务端要求 value key 必须存在）
        if (present && value != null) {
            put("value", value)
        } else {
            put("value", JSONObject.NULL)
        }
        // absent 时必须提供非空 reason
        if (!present) {
            put("reason", (reason ?: "source_absent").take(64))
        }
    }
}

// ─── 数据值结构（对应 carrot_navi_cereal.py 中 build_carrot_navi_payload 的输入）───

/** Vehicle 数据 */
data class VehicleData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val headingDeg: Double = 0.0,
    val speedKph: Double = 0.0,
    val roadName: String = "",
    val virtualGps: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        put("heading_deg", headingDeg)
        put("speed_kph", speedKph)
        put("road_name", roadName)
        put("virtual_gps", virtualGps)
    }
}

/** 导航引导数据 */
data class GuidanceData(
    val distanceM: Int = 0,
    val turnType: Int = -1,
    val mainText: String = "",
    val nearDirection: String = "",
    val farDirection: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("distance_m", distanceM)
        put("turn_type", turnType)
        put("main_text", mainText)
        put("near_direction", nearDirection)
        put("far_direction", farDirection)
    }
}

/** Speed 数据 */
data class SpeedData(
    val currentKph: Double = 0.0,
    val roadLimitKph: Int? = null,
    val sdiType: Int = -1,
    val sdiDistanceM: Int = 0,
    val sdiSpeedLimitKph: Int = 0,
    val sdiSectionType: Int = -1,
    val sdiBlockType: Int = -1,
    val sdiBlockSpeedKph: Int = 0,
    val sdiBlockDistanceM: Int = 0,
    val sectionActive: Boolean = false,
    val sectionSpeedLimitKph: Int = 0,
    val sectionAverageKph: Double = 0.0,
    val sectionRemainingDistanceM: Double = 0.0,
    val sectionRemainingTimeSec: Int = 0,
    val sectionProgress: Double = 0.0,
    val sectionSuspended: Boolean = false,
    val sectionOffRoute: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("current_kph", currentKph)
        roadLimitKph?.let { put("road_limit_kph", it) }
        put("sdi", JSONObject().apply {
            put("type", sdiType)
            put("distance_m", sdiDistanceM)
            put("speed_limit_kph", sdiSpeedLimitKph)
            put("section_type", sdiSectionType)
            put("block_type", sdiBlockType)
            put("block_speed_kph", sdiBlockSpeedKph)
            put("block_distance_m", sdiBlockDistanceM)
        })
        put("section", JSONObject().apply {
            put("active", sectionActive)
            put("speed_limit_kph", sectionSpeedLimitKph)
            put("average_kph", sectionAverageKph)
            put("remaining_distance_m", sectionRemainingDistanceM)
            put("remaining_time_sec", sectionRemainingTimeSec)
            put("progress", sectionProgress)
            put("suspended", sectionSuspended)
            put("off_route", sectionOffRoute)
        })
    }
}

/** TrafficSignal 数据 */
data class TrafficSignalData(
    val visible: Boolean = false,
    val distanceM: Int = 0,
    val redValid: Boolean = false,
    val redOn: Boolean = false,
    val redRemainSec: Int = 0,
    val greenValid: Boolean = false,
    val greenOn: Boolean = false,
    val greenRemainSec: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("visible", visible)
        put("distance_m", distanceM)
        put("source", "navigation")
        put("lights", JSONObject().apply {
            put("red", JSONObject().apply {
                put("valid", redValid)
                put("on", redOn)
                put("remain_sec", redRemainSec)
            })
            put("green", JSONObject().apply {
                put("valid", greenValid)
                put("on", greenOn)
                put("remain_sec", greenRemainSec)
            })
            // 其他方向灯标记为无效
            listOf("left", "right", "uturn").forEach { dir ->
                put(dir, JSONObject().apply { put("valid", false) })
            }
        })
        put("ui_counter", JSONObject().apply {
            put("remain_sec", redRemainSec)
        })
    }
}

/** Route 数据 */
data class RouteData(
    val remainDistanceM: Int = 0,
    val remainTimeSec: Int = 0,
    val movedDistanceM: Int = 0,
    val movedTimeSec: Int = 0,
    val totalDistanceM: Int = 0,
    val polyline: List<Pair<Double, Double>> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("remain_distance_m", remainDistanceM)
        put("remain_time_sec", remainTimeSec)
        put("moved_distance_m", movedDistanceM)
        put("moved_time_sec", movedTimeSec)
        put("total_distance_m", totalDistanceM)
        put("polyline", JSONArray().apply {
            polyline.forEach { (lat, lon) ->
                put(JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                })
            }
        })
    }
}

/** NavigationStatus 数据 */
data class NavigationStatusData(
    val mode: String = "idle",
    val guidanceActive: Boolean = false,
    val offRoute: Boolean = false,
    val routePresent: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("guidance_active", guidanceActive)
        put("off_route", offRoute)
        put("route_present", routePresent)
    }
}

/** Lane 数据 */
data class LaneData(
    val laneCount: Int = 0,
    val lanes: List<LaneItem> = emptyList(),
    val recommendedLane: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lane_count", laneCount)
        put("recommended_lane", recommendedLane)
        put("lanes", JSONArray().apply {
            lanes.forEach { lane ->
                put(JSONObject().apply {
                    put("index", lane.index)
                    put("direction", lane.direction)
                    put("is_recommended", lane.isRecommended)
                    put("is_available", lane.isAvailable)
                })
            }
        })
    }
}

data class LaneItem(
    val index: Int = 0,
    val direction: String = "",
    val isRecommended: Boolean = false,
    val isAvailable: Boolean = true
)

/** AppStatus 数据 */
data class AppStatusData(
    val foreground: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("foreground", foreground)
    }
}
