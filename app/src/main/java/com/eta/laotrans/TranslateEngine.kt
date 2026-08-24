package com.eta.laotrans

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 翻译引擎（Google Cloud Translation v3）
 *
 * 需要配置：
 *  - GOOGLE_PROJECT_ID：你的 Google Cloud 项目 ID
 *  - GOOGLE_API_KEY：允许调用 Translation 的 API Key
 *
 * 语言代码：老挝语 = "lo"（旧版也用 "lo-LA"），中文 = "zh"
 * 注意：老挝语是 Google 支持的语言，可直接双向互译。
 */
object TranslateEngine {

    // TODO: 替换成你自己的项目 ID 和 API Key
    private const val GOOGLE_PROJECT_ID = "YOUR_PROJECT_ID"
    private const val GOOGLE_API_KEY = "YOUR_API_KEY"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 翻译文本。sourceLang/targetLang 用 ISO 代码，如 "zh" / "lo"。
     * 返回翻译后的字符串。
     */
    suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val url = "https://translation.googleapis.com/v3/projects/$GOOGLE_PROJECT_ID:translateText?key=$GOOGLE_API_KEY"
            val body = JSONObject()
                .put("contents", arrayOf(text))
                .put("sourceLanguageCode", source)
                .put("targetLanguageCode", target)
                .put("mimeType", "text/plain")

            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw IllegalStateException("空响应")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("翻译请求失败 HTTP ${resp.code}: $respBody")
                }
                val arr = JSONObject(respBody).getJSONArray("translations")
                arr.getJSONObject(0).getString("translatedText")
            }
        }
}
