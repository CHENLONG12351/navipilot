package com.jixiexiaoge.drivingassist.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.Properties

private const val TAG = "MapSearchService"

/** 从 secrets.properties 读取 API Key */
private fun loadApiKey(context: Context, keyName: String): String {
    try {
        val props = Properties()
        context.assets.open("secrets.properties").use { props.load(it) }
        return props.getProperty(keyName, "")
    } catch (e: Exception) {
        android.util.Log.w(TAG, "无法加载 secrets.properties，使用空 Key")
        return ""
    }
}

/** 搜索结果 */
data class SearchResult(
    val name: String,
    val address: String,
    val lon: Double,
    val lat: Double
)

/** 搜索响应 */
data class SearchResponse(
    val results: List<SearchResult>,
    val serviceName: String
)

/** 搜索提供商 —— 用户可手动选择 */
enum class SearchProvider(val label: String, val labelCn: String) {
    GAODE("Amap", "高德地图"),
    TENCENT("Tencent", "腾讯地图"),
}

/** 搜索提供商对应的地理编码 API */
private fun amapGeocodeUrl(query: String, context: Context): String =
    "https://restapi.amap.com/v3/place/text?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}&key=${loadApiKey(context, "AMAP_KEY")}&output=json&offset=10"

private fun tencentGeocodeUrl(query: String, context: Context): String =
    "https://apis.map.qq.com/ws/place/v1/suggestion?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&key=${loadApiKey(context, "TENCENT_KEY")}&output=json&region=全国"

/**
 * 统一搜索入口
 * @param query 搜索关键词
 * @param provider 用户选择的搜索引擎
 * @param context Android Context
 */
suspend fun searchPlaces(
    query: String,
    provider: SearchProvider = SearchProvider.GAODE,
    context: Context
): SearchResponse = withContext(Dispatchers.IO) {
    android.util.Log.i(TAG, "搜索请求: query=\"$query\" provider=$provider")
    when (provider) {
        SearchProvider.GAODE -> searchPlacesAmap(query, context)
        SearchProvider.TENCENT -> searchPlacesTencent(query, context)
    }
}

/**
 * 高德地图 POI 搜索
 */
private suspend fun searchPlacesAmap(query: String, context: Context): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    try {
        val url = URL(amapGeocodeUrl(query, context))
        val conn = url.openConnection()
        conn.setRequestProperty("User-Agent", "Navipilot/1.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val jsonStr = conn.inputStream.bufferedReader().readText()
        android.util.Log.i(TAG, "高德 API 原始响应: $jsonStr")
        val json = JSONObject(jsonStr)
        // 检查 API 状态
        val status = json.optString("status", "")
        if (status == "0") {
            val info = json.optString("info", "未知错误")
            android.util.Log.w(TAG, "高德 API 返回错误: info=$info")
            return@withContext SearchResponse(results, "高德地图")
        }
        val pois = json.optJSONArray("pois")
        if (pois == null) {
            android.util.Log.w(TAG, "高德 API 响应中无 pois 字段，原始响应: ${jsonStr.take(500)}")
            return@withContext SearchResponse(results, "高德地图")
        }
        for (i in 0 until pois.length()) {
            val poi = pois.getJSONObject(i)
            val name = poi.optString("name", "")
            val address = poi.optString("address", "")
            val location = poi.optString("location", "")
            if (name.isNotEmpty() && location.isNotEmpty()) {
                val parts = location.split(",")
                if (parts.size == 2) {
                    val lon = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    results.add(SearchResult(name, address, lon, lat))
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "高德搜索失败: ${e.message}")
    }
    SearchResponse(results, "高德地图")
}

/**
 * 腾讯地图搜索
 */
private suspend fun searchPlacesTencent(query: String, context: Context): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    try {
        val url = URL(tencentGeocodeUrl(query, context))
        val conn = url.openConnection()
        conn.setRequestProperty("User-Agent", "Navipilot/1.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val jsonStr = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(jsonStr)
        val data = json.optJSONArray("data") ?: return@withContext SearchResponse(results, "腾讯地图")
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val title = item.optString("title", "")
            val address = item.optString("address", "")
            val location = item.optJSONObject("location")
            if (title.isNotEmpty() && location != null) {
                val lat = location.optDouble("lat", 0.0)
                val lon = location.optDouble("lng", 0.0)
                if (lat != 0.0 && lon != 0.0) {
                    results.add(SearchResult(title, address, lon, lat))
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "腾讯搜索失败: ${e.message}")
    }
    SearchResponse(results, "腾讯地图")
}
