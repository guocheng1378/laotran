package com.eta.laotrans

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryRecord(
    val id: Long,
    val time: Long,
    val srcText: String,
    val dstText: String,
    val direction: String
)

/**
 * 翻译历史存储（本地 SharedPreferences，JSON 数组）。
 * 相同原文+译文去重，只更新时间为最新一条；最多保留 [MAX] 条。
 */
object HistoryStore {

    private const val PREFS = "laotrans_history"
    private const val KEY = "records"
    private const val MAX = 200

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(c: Context, srcText: String, dstText: String, direction: String) {
        val list = list(c).toMutableList()
        // 原文+译文相同时只保留最新一条（更新时间）
        list.removeAll { it.srcText == srcText && it.dstText == dstText }
        val now = System.currentTimeMillis()
        list.add(0, HistoryRecord(now, now, srcText, dstText, direction))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(c, list)
    }

    fun list(c: Context): List<HistoryRecord> {
        val json = sp(c).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<HistoryRecord>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(HistoryRecord(
                    o.optLong("id", System.currentTimeMillis() + i),
                    o.optLong("time", System.currentTimeMillis()),
                    o.optString("src", ""),
                    o.optString("dst", ""),
                    o.optString("dir", "")
                ))
            } catch (_: Exception) {}
        }
        return out
    }

    fun remove(c: Context, id: Long) {
        val list = list(c).toMutableList()
        list.removeAll { it.id == id }
        save(c, list)
    }

    fun clear(c: Context) {
        sp(c).edit().remove(KEY).apply()
    }

    private fun save(c: Context, list: List<HistoryRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject()
                .put("id", r.id)
                .put("time", r.time)
                .put("src", r.srcText)
                .put("dst", r.dstText)
                .put("dir", r.direction))
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
    }
}
