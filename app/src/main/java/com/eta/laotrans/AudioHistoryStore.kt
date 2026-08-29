package com.eta.laotrans

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AudioRecord(
    val text: String,
    val filePath: String,
    val timestamp: Long,
    val srcText: String = "",
    val romanization: String = ""
)

/**
 * 已保存音频记录存储（本地 SharedPreferences，JSON 数组）。
 * 管理语音合成产物的元信息：文本、wav 文件绝对路径、生成时间。
 * 提供 add / list / findByText / removeByPath 方法，供 LaoSpeech 做本地缓存
 * 与音频库面板（AudioHistoryDialog）读取/删除使用。
 */
object AudioHistoryStore {

    private const val PREFS = "laotrans_audio_history"
    private const val KEY = "records"
    private const val MAX = 200

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 新增一条音频记录（同文本已存在则只更新时间戳，去重）。 */
    fun add(c: Context, text: String, filePath: String, srcText: String = "", romanization: String = "") {
        val list = list(c).toMutableList()
        list.removeAll { it.filePath == filePath }
        val now = System.currentTimeMillis()
        list.add(0, AudioRecord(text, filePath, now, srcText, romanization))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(c, list)
    }

    /** 读取全部音频记录，最新在前。 */
    fun list(c: Context): List<AudioRecord> {
        val json = sp(c).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<AudioRecord>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(AudioRecord(
                    o.optString("text", ""),
                    o.optString("path", ""),
                    o.optLong("time", System.currentTimeMillis()),
                    o.optString("src", ""),
                    o.optString("rom", "")
                ))
            } catch (_: Exception) {}
        }
        return out
    }

    /** 按文本查找已保存音频（用于本地缓存复用）。 */
    fun findByText(c: Context, text: String): AudioRecord? =
        list(c).firstOrNull { it.text == text && fileExists(it.filePath) }

    /** 按文件路径删除记录。返回是否删除了某条。 */
    fun removeByPath(c: Context, filePath: String): Boolean {
        val list = list(c).toMutableList()
        val before = list.size
        list.removeAll { it.filePath == filePath }
        if (list.size == before) return false
        save(c, list)
        return true
    }

    /** 清理文件中已不存在（被外部删除）的记录。 */
    fun pruneMissing(c: Context) {
        val list = list(c).toMutableList()
        val before = list.size
        list.removeAll { !fileExists(it.filePath) }
        if (list.size != before) save(c, list)
    }

    private fun fileExists(path: String): Boolean =
        path.isNotBlank() && java.io.File(path).exists()

    fun clear(c: Context) {
        sp(c).edit().remove(KEY).apply()
    }

    private fun save(c: Context, list: List<AudioRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject()
                .put("text", r.text)
                .put("path", r.filePath)
                .put("time", r.timestamp)
                .put("src", r.srcText)
                .put("rom", r.romanization))
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
    }
}
