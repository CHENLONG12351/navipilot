package com.example.navipilot.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

private const val TAG = "MapSearchService"

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
private fun amapGeocodeUrl(query: String): String =
    "https://restapi.amap.com/v3/place/text?keywords=${java.net.URLEncoder.encode(query, "UTF-8")}&key=de0a8f5c4b5dc69d0692ef1efb8601ee&output=json&offset=10"

private fun tencentGeocodeUrl(query: String): String =
    "https://apis.map.qq.com/ws/place/v1/suggestion?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&key=2NFBZ-YKG3W-TI4RJ-R4PJG-CGSSL-S2BRB&output=json&region=全国"

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
    when (provider) {
        SearchProvider.GAODE -> searchPlacesAmap(query)
        SearchProvider.TENCENT -> searchPlacesTencent(query)
    }
}

/**
 * 高德地图 POI 搜索
 */
private suspend fun searchPlacesAmap(query: String): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    try {
        val url = URL(amapGeocodeUrl(query))
        val conn = url.openConnection()
        conn.setRequestProperty("User-Agent", "Navipilot/1.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val jsonStr = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(jsonStr)
        val pois = json.optJSONArray("pois") ?: JSONObject().optJSONArray("pois") ?: return@withContext SearchResponse(results, "高德地图")
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
private suspend fun searchPlacesTencent(query: String): SearchResponse = withContext(Dispatchers.IO) {
    val results = mutableListOf<SearchResult>()
    try {
        val url = URL(tencentGeocodeUrl(query))
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
