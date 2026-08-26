package com.eta.laotrans

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MyMemory 免费翻译（https://mymemory.translated.net）。
 *
 * 免费额度有限（匿名约每日 5000 字符），适合作为默认优先引擎。
 * 任何失败（超时 / 无网络 / 超配额 / 服务器异常 / 解析失败）一律返回 null、不抛异常，
 * 由调用方决定是否降级到 LLM。
 */
object MyMemoryTranslate {

    private const val ENDPOINT = "https://api.mymemory.translated.net/get"

    /** 免费翻译专用客户端：短超时，快速失败以便上层及时降级。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 免费翻译一段文本。
     *
     * @param text   待翻译文本
     * @param source 源语言代码（"zh" / "lo"）
     * @param target 目标语言代码（"zh" / "lo"）
     * @return 译文文本；失败（超时/无网络/超配额/解析失败）返回 null
     */
    fun translate(text: String, source: String, target: String): String? {
        if (text.isBlank()) return null
        return try {
            val langpair = "${langCode(source)}|${langCode(target)}"
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "$ENDPOINT?q=$q&langpair=$langpair"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                // HTTP 非 2xx（含超配额 403）视为失败
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                // 业务层失败：responseStatus 非 200（如配额用尽）
                if (root.optInt("responseStatus", -1) != 200) return null
                val translated = root.optJSONObject("responseData")
                    ?.optString("translatedText", "")
                    ?.trim()
                if (translated.isNullOrEmpty()) return null
                if (translated.equals("NO QUERY", ignoreCase = true)) return null
                translated
            }
        } catch (_: Exception) {
            // 超时 / 无网络 / 解析失败等：吞掉异常，返回 null
            null
        }
    }

    /** 把 App 内简写语言代码映射为 MyMemory 的语言对代码。 */
    private fun langCode(code: String): String = when (code) {
        "zh" -> "zh-CN"
        else -> code
    }
}
