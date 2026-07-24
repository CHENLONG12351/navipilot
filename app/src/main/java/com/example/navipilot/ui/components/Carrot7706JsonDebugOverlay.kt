package com.example.navipilot.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.navipilot.CarrotManFields
import com.example.navipilot.CarrotManNetworkClient
import com.example.navipilot.navigation.NaviV2Constants
import com.example.navipilot.ui.utils.localized
import org.json.JSONObject

private fun jsonScalarToDisplayString(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Number, is Boolean -> value.toString()
    else -> value.toString()
}

/** 将 JSONObject 拍平成 key→value 列表，按键排序 */
private fun flattenJson(obj: JSONObject, prefix: String = ""): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    obj.keys().asSequence().sorted().forEach { key ->
        val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
        val value = obj.opt(key)
        if (value is JSONObject) {
            // 嵌套对象递归展开
            result.addAll(flattenJson(value, fullKey))
        } else {
            result.add(fullKey to jsonScalarToDisplayString(value))
        }
    }
    return result
}

/** 带颜色标签的值（用于 stream 状态显示） */
private data class LabeledValue(
    val label: String,
    val value: String,
    val color: Color = Color(0xFFE2E8F0)
)

/**
 * Carrot7706JsonDebugOverlay — 双标签页调试面板
 *
 * Tab1 "UDP 7706"：显示旧版 UDP 44 字段（原逻辑不变）
 * Tab2 "WS v2"：显示 v2 WebSocket 客户端状态 + 13 流数据摘要
 */
@Composable
fun Carrot7706JsonDebugOverlay(
    fields: CarrotManFields,
    networkClient: CarrotManNetworkClient?,
    v2ClientSnapshot: JSONObject? = null,    // NaviWebSocketV2Client.debugSnapshot()
    v2StreamSnapshot: JSONObject? = null,    // NaviStreamManager.debugSnapshot()
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)

    // 旧版 UDP 7706 字段列表
    val udpPairs = remember(fields, networkClient) {
        runCatching {
            val obj: JSONObject = networkClient?.preview7706Json(fields)
                ?: CarrotManNetworkClient.build7706Payload(
                    fields,
                    packetCarrotIndex = if (fields.carrotIndex > 0L) fields.carrotIndex else 1L,
                )
            val sourceLast = fields.source_last
            val speedLimitSource = when (sourceLast) {
                "AMAP" -> "  (高德车机)"; "amap_mobile" -> "  (高德手机)"
                else -> ""
            }
            obj.keys().asSequence().sorted().map { key ->
                val value = jsonScalarToDisplayString(obj.opt(key))
                key to if (key == "nRoadLimitSpeed" && (obj.optInt(key, 0) > 0)) "$value$speedLimitSource" else value
            }.toList()
        }.getOrElse { e ->
            listOf("_exception" to (e.message ?: e.toString()))
        }
    }

    // v2 调试数据列表
    val v2Pairs = remember(v2ClientSnapshot, v2StreamSnapshot) {
        buildV2DebugPairs(v2ClientSnapshot, v2StreamSnapshot)
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                // 标题栏 + 标签切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tab1: UDP 7706
                    TabButton(
                        text = "UDP 7706",
                        count = udpPairs.size,
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    Spacer(Modifier.width(4.dp))
                    // Tab2: WS v2
                    TabButton(
                        text = "WS v2",
                        count = v2Pairs.size,
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (networkClient == null && selectedTab == 0) {
                        Text(localized("未连接", "Offline"), color = Color(0xFFFBBF24), fontSize = 9.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(0.dp)) {
                        Icon(Icons.Default.Close, localized("关闭", "Close"), tint = Color(0xFF94A3B8), modifier = Modifier.padding(4.dp))
                    }
                }

                // 分隔线
                HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)

                // 内容区
                Box(modifier = Modifier.fillMaxSize().weight(1f).padding(top = 4.dp)) {
                    when (selectedTab) {
                        0 -> UdpFieldList(pairs = udpPairs)
                        1 -> V2DebugPanel(pairs = v2Pairs)
                    }
                }
            }
        }
    }
}

/* ─── 标签按钮 ─── */

@Composable
private fun TabButton(text: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF1E3A5F) else Color(0xFF1E293B)
    val fg = if (selected) Color.White else Color(0xFF94A3B8)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = fg, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.width(3.dp))
            Text("($count)", color = Color(0xFF64748B), fontSize = 9.sp)
        }
    }
}

/* ─── Tab 1: UDP 7706 字段列表（原逻辑） ─── */

@Composable
private fun UdpFieldList(pairs: List<Pair<String, String>>) {
    if (pairs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无数据", color = Color(0xFF64748B), fontSize = 12.sp)
        }
        return
    }
    val leftCol = pairs.filterIndexed { i, _ -> i % 2 == 0 }
    val rightCol = pairs.filterIndexed { i, _ -> i % 2 == 1 }
    Row(modifier = Modifier.fillMaxSize()) {
        ColumnList(pairs = leftCol, modifier = Modifier.weight(1f).fillMaxHeight())
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF334155)).padding(horizontal = 2.dp))
        ColumnList(pairs = rightCol, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ColumnList(pairs: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val keyColor = Color(0xFF94A3B8)
    val valColor = Color(0xFFE2E8F0)
    LazyColumn(
        modifier = modifier.padding(horizontal = 2.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(pairs) { _, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = key,
                    color = if (key == "_exception") Color(0xFFF87171) else keyColor,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.45f),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = value,
                    color = valColor,
                    fontSize = 9.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.55f),
                )
            }
            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.4f), thickness = 0.3.dp)
        }
    }
}

/* ─── Tab 2: WS v2 调试面板 ─── */

@Composable
private fun V2DebugPanel(pairs: List<LabeledValue>) {
    if (pairs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📡", fontSize = 28.sp)
                Spacer(Modifier.height(6.dp))
                Text(localized("未连接", "Not connected"), color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(pairs) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.label,
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.38f),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = item.value,
                    color = item.color,
                    fontSize = 9.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.62f),
                )
            }
            HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.3f), thickness = 0.3.dp)
        }
    }
}

/* ─── 构建 v2 调试数据 ─── */

private fun buildV2DebugPairs(
    clientSnapshot: JSONObject?,
    streamSnapshot: JSONObject?
): List<LabeledValue> {
    if (clientSnapshot == null) return emptyList()
    val result = mutableListOf<LabeledValue>()

    // ── 连接状态 ──
    val state = clientSnapshot.optString("state", "?")
    val stateColor = when (state) {
        "CONNECTED" -> Color(0xFF22C55E)
        "NEGOTIATING", "CONTROL_CONNECTING" -> Color(0xFFFBBF24)
        "RECONNECTING", "DISCOVERING" -> Color(0xFFF97316)
        else -> Color(0xFFEF4444)
    }
    result.add(LabeledValue("state", state, stateColor))
    result.add(LabeledValue("target", clientSnapshot.optString("target", "-")))
    val sid = clientSnapshot.opt("sessionId")
    result.add(LabeledValue("session_id", if (sid == JSONObject.NULL) "-" else sid.toString(), if (sid != JSONObject.NULL) Color(0xFF22C55E) else Color(0xFF64748B)))
    result.add(LabeledValue("revision", clientSnapshot.optString("revision", "0")))
    result.add(LabeledValue("controlFailCount", clientSnapshot.optString("controlFailCount", "0")))
    result.add(LabeledValue("discovery", if (clientSnapshot.optBoolean("discoveryReceived", false)) "✅" else "⏳"))

    // ── 流连接状态 ──
    val streams = clientSnapshot.optJSONObject("streams")
    if (streams != null) {
        result.add(LabeledValue("── streams ──", "", Color(0xFF64748B)))
        streams.keys().asSequence().sorted().forEach { name ->
            val st = streams.optString(name, "?")
            val isOk = st == "connected"
            result.add(LabeledValue("  $name", st, if (isOk) Color(0xFF22C55E) else Color(0xFFEF4444)))
        }
    }

    // ── 流序列号 ──
    val seqs = clientSnapshot.optJSONObject("sequences")
    if (seqs != null && seqs.length() > 0) {
        result.add(LabeledValue("── sequences ──", "", Color(0xFF64748B)))
        seqs.keys().asSequence().sorted().forEach { key ->
            result.add(LabeledValue("  $key", seqs.optString(key, "0")))
        }
    }

    // ── 流数据摘要 ──
    if (streamSnapshot != null) {
        val streamCount = streamSnapshot.optInt("streamCount", 0)
        val presentCount = streamSnapshot.optInt("presentCount", 0)
        result.add(LabeledValue("── data (${presentCount}/${streamCount} present) ──", "", Color(0xFF64748B)))
        NaviV2Constants.ENABLED_JSON_STREAMS.forEach { name ->
            val s = streamSnapshot.optJSONObject(name)
            if (s != null) {
                val present = s.optBoolean("present", false)
                val ageMs = s.optLong("ageMs", -1)
                val valueStr = s.optString("value", "")
                val icon = if (present) "✅" else "❌"
                val ageStr = if (ageMs >= 0) "${ageMs}ms" else "-"
                val summary = if (present) valueStr.take(100) else "absent"
                result.add(LabeledValue("  $name", "$icon $summary  [${ageStr}]", if (present) Color(0xFFE2E8F0) else Color(0xFF64748B)))
            }
        }
    }

    return result
}
