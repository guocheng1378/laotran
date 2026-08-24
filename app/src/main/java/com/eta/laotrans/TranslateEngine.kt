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
 * 翻译引擎（大模型 LLM，OpenAI 兼容接口）
 *
 * 通过 Chat Completions 让大模型在「中文 ↔ 老挝语」之间互译。
 * 相比 Google 翻译，LLM 对老挝语的长句、口语、人地名、语境的理解更准。
 *
 * 基础地址：https://api.b.ai/v1  (chat/completions)
 * MODEL：按你的模型名设置（见下）
 * API Key 由 BuildConfig 注入（不硬编码在公开代码里）：
 *   - 本地构建：写入 local.properties 的 API_KEY=xxx
 *   - GitHub Actions：设置 Secrets.API_KEY 后读入
 */
object TranslateEngine {

    private const val BASE_URL = "https://api.b.ai/v1"
    // TODO: 改成你实际使用的模型名
    private const val MODEL = "b.ai-chat"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 大模型响应可能慢
        .build()

    /**
     * 翻译文本。source/target 建议："zh"（中文）"lo"（老挝语）。
     * 返回翻译后的字符串。
     */
    suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.API_KEY
            if (apiKey.isBlank()) throw IllegalStateException("未配置大模型 API Key，请在 local.properties 填入 API_KEY 或设置 GitHub Secrets")

            val srcName = if (source == "lo") "老挝语" else "中文"
            val tgtName = if (target == "lo") "老挝语" else "中文"

            val prompt = "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                    "只输出译文本身，不要任何解释、注释或引号。文本如下：\n$text"

            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是一名精准的老挝语-中文互译专家，译文要自然、准确、符合当地人表达。"))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.2)

            val req = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: throw IllegalStateException("空响应")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("翻译请求失败 HTTP ${resp.code}: $respBody")
                }
                val choices = JSONObject(respBody).getJSONArray("choices")
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
}
