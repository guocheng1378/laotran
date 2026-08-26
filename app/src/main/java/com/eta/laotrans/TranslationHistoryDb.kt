package com.eta.laotrans

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条翻译历史记录。 */
data class TranslationHistoryItem(
    val time: Long,
    val srcText: String,
    val dstText: String,
    val direction: String = ""
)

/**
 * 翻译历史存储（SharedPreferences 持久化，JSON 数组）。
 *
 * 提供 add / getAll / remove / clear 四个方法。
 * 相同原文+译文只保留最新一条（刷新时间）；最多保留 [MAX] 条。
 */
object TranslationHistoryDb {

    private const val PREFS = "translation_history"
    private const val KEY = "history"
    private const val MAX = 200

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 新增一条历史（新记录在最前）。原文+译文相同则只更新时间。
     * [direction] 形如 "lo->zh" / "zh->lo"，用于界面展示方向。
     */
    fun add(c: Context, srcText: String, dstText: String, direction: String = ""): Unit {
        if (srcText.isBlank()) return
        val list = getAll(c).toMutableList()
        list.removeAll { it.srcText == srcText && it.dstText == dstText }
        list.add(0, TranslationHistoryItem(System.currentTimeMillis(), srcText, dstText, direction))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(c, list)
    }

    /** 读取全部历史（新记录在前）。 */
    fun getAll(c: Context): List<TranslationHistoryItem> {
        val json = sp(c).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<TranslationHistoryItem>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(TranslationHistoryItem(
                    o.optLong("time", System.currentTimeMillis()),
                    o.optString("src", ""),
                    o.optString("dst", ""),
                    o.optString("dir", "")
                ))
            } catch (_: Exception) {
                // 跳过损坏的单条记录
            }
        }
        return out
    }

    /** 删除某条原文对应的所有历史记录，返回是否删除了任何一条。 */
    fun remove(c: Context, srcText: String): Boolean {
        if (srcText.isBlank()) return false
        val list = getAll(c).toMutableList()
        val before = list.size
        list.removeAll { it.srcText == srcText }
        if (list.size == before) return false
        save(c, list)
        return true
    }

    /** 清空全部历史。 */
    fun clear(c: Context): Unit {
        sp(c).edit().remove(KEY).apply()
    }

    private fun save(c: Context, list: List<TranslationHistoryItem>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject()
                .put("time", item.time)
                .put("src", item.srcText)
                .put("dst", item.dstText)
                .put("dir", item.direction))
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
    }
}
