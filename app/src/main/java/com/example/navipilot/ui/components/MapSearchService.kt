package com.example.navipilot.ui.components

import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "MapSearchService"

// ==================== 数据模型 ====================

/** 搜索结果 */
data class SearchResult(val name: String, val address: String, val lon: Double, val lat: Double)

/** 搜索响应（含使用的服务名称） */
data class SearchResponse(val results: List<SearchResult>, val serviceName: String)

/** 可手动选择的搜索模式 */
enum class SearchProvider(val label: String) {
    AUTO("默认"),
    GAODE("高德")
}

// ==================== 工具函数 ====================

/** 判断坐标是否在中国境内（粗略矩形范围） */
fun isInChina(lat: Double, lon: Double): Boolean {
    return lat in 3.86..53.55 && lon in 73.66..135.05
}

/** GCJ-02 转 WGS-84（高德坐标 → WGS-84） */
fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
    val a = 6378245.0
    val ee = 0.00669342162296594323
    fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }
    fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
    val dx = gcjLon - 105.0
    val dy = gcjLat - 35.0
    var dLat = transformLat(dx, dy)
    var dLon = transformLon(dx, dy)
    val radLat = gcjLat / 180.0 * Math.PI
    var magic = Math.sin(radLat)
    magic = 1 - ee * magic * magic
    val sqrtMagic = Math.sqrt(magic)
    dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
    dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
    return Pair(gcjLat - dLat, gcjLon - dLon)
}

/** 高德 Web 服务数字签名：MD5(按 key 升序拼接的 key=value&... + 安全密钥)，小写十六进制 */
private fun calcAmapSig(params: Map<String, String>, secret: String): String {
    val sorted = TreeMap(params).entries.joinToString("&") { "${it.key}=${it.value}" }
    val raw = sorted + secret
    val md5 = java.security.MessageDigest.getInstance("MD5")
    return md5.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

// ==================== 搜索服务 ====================

private val searchHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
    .build()

// 高德 Web 服务 API Key（需在 local.properties 中配置 AMAP_WEB_KEY / AMAP_WEB_SECRET，不配置则 REST 搜索不可用）
private const val AMAP_WEB_KEY = ""
private const val AMAP_WEB_SECRET = ""

/** 高德 REST Key 是否已标记为无效（如 10009 Key 不匹配），后续跳过 REST 调用 */
private var amapRestKeyInvalid: Boolean = false

/**
 * 高德输入提示 REST API
 * 需要「Web服务」类型 Key；JS API Key 会返回 USERKEY_PLAT_NOMATCH (10009)。
 * 一旦收到 10009 错误，标记该 Key 为无效，后续请求跳过 REST 路径。
 */
private suspend fun searchPlacesAmap(
    query: String,
    biasGcjLat: Double?,
    biasGcjLon: Double?,
): List<SearchResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    if (amapRestKeyInvalid) {
        Log.d(TAG, "高德 REST Key 已标记无效，跳过 REST 调用")
        return@withContext results
    }
    if (AMAP_WEB_KEY.isBlank()) {
        Log.d(TAG, "高德 Web Key 未配置，跳过 REST 调用")
        return@withContext results
    }
    try {
        val params = TreeMap<String, String>()
        params["keywords"] = query
        params["key"] = AMAP_WEB_KEY
        if (biasGcjLat != null && biasGcjLon != null &&
            biasGcjLat != 0.0 && biasGcjLon != 0.0
        ) {
            params["location"] = "${biasGcjLon},${biasGcjLat}"
        }
        val urlBuilder = ("https://restapi.amap.com/v3/assistant/inputtips").toHttpUrlOrNull()!!.newBuilder()
        for ((k, v) in params) {
            urlBuilder.addQueryParameter(k, v)
        }
        if (AMAP_WEB_SECRET.isNotBlank()) {
            urlBuilder.addQueryParameter("sig", calcAmapSig(params, AMAP_WEB_SECRET))
        }
        val url = urlBuilder.build().toString()
        Log.d(TAG, "高德地图搜索(REST): keyword=$query")
        val response = searchHttpClient.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body?.string()
        if (body != null) {
            val json = JSONObject(body)
            val ok = json.optString("status") == "1" || json.optInt("status", 0) == 1
            if (ok) {
                val tips = json.optJSONArray("tips")
                if (tips != null) {
                    for (i in 0 until tips.length()) {
                        val tip = tips.getJSONObject(i)
                        val title = tip.optString("name", "")
                        val district = tip.optString("district", "")
                        val addr = tip.optString("address", "")
                        val address = when {
                            addr.isNotEmpty() -> addr
                            district.isNotEmpty() -> district
                            else -> ""
                        }
                        val locStr = tip.optString("location", "")
                        if (title.isEmpty() || locStr.isBlank()) continue
                        val parts = locStr.split(",")
                        if (parts.size < 2) continue
                        val gcjLon = parts[0].trim().toDoubleOrNull() ?: continue
                        val gcjLat = parts[1].trim().toDoubleOrNull() ?: continue
                        if (gcjLat == 0.0 && gcjLon == 0.0) continue
                        val (wgsLat, wgsLon) = gcj02ToWgs84(gcjLat, gcjLon)
                        results.add(SearchResult(title, address, wgsLon, wgsLat))
                    }
                }
                Log.i(TAG, "高德 REST 搜索成功: ${results.size}条结果")
            } else {
                val infocode = json.optString("infocode")
                Log.w(TAG, "高德 REST 失败: status=${json.optString("status")}, info=${json.optString("info")}, infocode=$infocode")
                if (infocode == "10009") {
                    Log.w(TAG, "10009=Key 与平台不匹配。已标记无效，不再重试。")
                    amapRestKeyInvalid = true
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "高德 REST 搜索异常: ${e.message}")
    }
    results
}

/**
 * 统一搜索入口：
 * 1. 用户显式指定引擎 → 直接调用
 * 2. AUTO（国内且有定位）→ 高德 REST API
 * 3. AUTO（海外或无定位）→ 返回空结果
 *
 * @param androidContext 保留参数（不再使用）
 */
suspend fun searchPlaces(
    token: String,
    query: String,
    proximity: String,
    mapServiceType: String = "OSM",
    preferredProvider: SearchProvider = SearchProvider.AUTO,
    androidContext: Context? = null,
): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    var serviceName = "高德地图"

    val proxParts = proximity.split(",")
    val proxLon = proxParts.getOrNull(0)?.toDoubleOrNull()
    val proxLat = proxParts.getOrNull(1)?.toDoubleOrNull()

    // ===== 用户指定了搜索引擎 → 直接调用 =====
    if (preferredProvider != SearchProvider.AUTO) {
        val providerResults = when (preferredProvider) {
            SearchProvider.GAODE -> {
                serviceName = "高德地图"
                if (proxLat != null && proxLon != null && isInChina(proxLat, proxLon)) {
                    val gcj = com.example.navipilot.navigation.CoordinateConverter.wgs84ToGcj02(proxLat, proxLon)
                    searchPlacesAmap(query, gcj.first, gcj.second)
                } else {
                    searchPlacesAmap(query, null, null)
                }
            }
            SearchProvider.AUTO -> emptyList()
        }
        results.addAll(providerResults)
        return@withContext SearchResponse(
            results.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
            serviceName
        )
    }

    // ===== AUTO 模式 =====
    val isChineseLocale = Locale.getDefault().language.startsWith("zh")
    if (isChineseLocale) {
        // 中文用户：国内高德搜索，海外无搜索结果
        if (proxLat != null && proxLon != null && isInChina(proxLat, proxLon)) {
            val gcjCoords = com.example.navipilot.navigation.CoordinateConverter.wgs84ToGcj02(proxLat, proxLon)
            val amapResults = searchPlacesAmap(query, gcjCoords.first, gcjCoords.second)
            if (amapResults.isNotEmpty()) {
                return@withContext SearchResponse(
                    amapResults.distinctBy { "${it.lat.toFloat()},${it.lon.toFloat()}" }.take(8),
                    "高德地图"
                )
            }
            Log.w(TAG, "高德地图无结果")
        } else {
            Log.d(TAG, "海外地区无可用搜索服务")
        }
    } else {
        Log.d(TAG, "非中文用户，无可用搜索服务")
    }

    SearchResponse(emptyList(), "")
}
