package com.jixiexiaoge.drivingassist

import android.content.Context
import com.jixiexiaoge.drivingassist.ui.components.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索功能工具函数
 *
 * 从 MainActivityUI.kt 提取，减少主文件行数。
 * 包含导航历史记录读写、地址保存等功能。
 */
class MainActivityUISearch {

    companion object {
        private const val NAV_HISTORY_MAX = 3

        /** 保存导航记录到 SharedPreferences（IO 异步） */
        suspend fun saveNavHistory(context: Context, name: String, lon: Double, lat: Double) {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences("nav_history", Context.MODE_PRIVATE)
                val existing = prefs.getString("history_json", "[]") ?: "[]"
                val arr = try { JSONArray(existing) } catch (_: Exception) { JSONArray() }
                val filtered = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val oName = obj.optString("name", "")
                    val oLat = obj.optDouble("lat", 0.0)
                    val oLon = obj.optDouble("lon", 0.0)
                    if (oName == name || (kotlin.math.abs(oLat - lat) < 0.0005 && kotlin.math.abs(oLon - lon) < 0.0005)) continue
                    filtered.put(obj)
                }
                val newArr = JSONArray()
                newArr.put(JSONObject().put("name", name).put("lon", lon).put("lat", lat))
                for (i in 0 until minOf(filtered.length(), NAV_HISTORY_MAX - 1)) { newArr.put(filtered.getJSONObject(i)) }
                prefs.edit().putString("history_json", newArr.toString()).apply()
            }
        }

        /** 从 SharedPreferences 加载导航历史（在 IO 线程调用） */
        fun loadNavHistory(context: Context): List<SearchResult> {
            val prefs = context.getSharedPreferences("nav_history", Context.MODE_PRIVATE)
            val json = prefs.getString("history_json", "[]") ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
            val results = mutableListOf<SearchResult>()
            for (i in 0 until minOf(arr.length(), NAV_HISTORY_MAX)) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "")
                val lon = obj.optDouble("lon", 0.0)
                val lat = obj.optDouble("lat", 0.0)
                if (name.isNotEmpty() && lon != 0.0 && lat != 0.0) results.add(SearchResult(name, "", lon, lat))
            }
            return results
        }

        /** 保存地址到家/公司（IO 异步） */
        suspend fun saveAddress(context: Context, type: String, name: String, lon: Double, lat: Double) {
            withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences("map_addresses", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putString("${type}_name", name)
                    putFloat("${type}_lat", lat.toFloat())
                    putFloat("${type}_lon", lon.toFloat())
                    apply()
                }
            }
        }
    }
}
