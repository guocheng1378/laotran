package com.eta.laotrans

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 翻译引擎（大模型 LLM，OpenAI 兼容接口）
 *
 * 所有配置（接口地址 / API Key / 模型名）在 App 内的设置界面里填，
 * 通过 [Config] 持久化，因此切换任意 OpenAI 兼容服务（b.ai、DeepSeek、
 * OpenAI、Qwen、Kimi…）都不需要重新打包。
 *
 * 支持「拉取模型」：调用 /v1/models 列出该接口可用的模型。
 *
 * 输出约定：
 * - 翻译成中文时，只输出中文译文本身。
 * - 翻译成老挝语时，输出两行：
 *     第一行：老挝语译文
 *     第二行：转写：<拉丁字母罗马音>（方便朗读前对照发音，也为中文用户提供读音参考）
 */
object TranslateEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 大模型响应可能慢
        .build()

    /**
     * 拉取该接口可用的模型列表。成功返回模型 id 列表；失败抛异常。
     */
    suspend fun listModels(context: Context): List<String> =
        withContext(Dispatchers.IO) {
            val baseUrl = Config.baseUrl(context)
            val key = Config.apiKey(context)
            if (key.isBlank()) throw IllegalStateException("请先在设置里填写 API Key")

            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $key")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: throw IllegalStateException("空响应")
                if (!resp.isSuccessful) {
                    throw IllegalStateException("获取模型失败 HTTP ${resp.code}: $body")
                }
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val names = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
                if (names.isEmpty()) throw IllegalStateException("该接口未返回可用模型")
                names
            }
        }

    /**
     * 翻译文本。source/target 建议："zh"（中文）"lo"（老挝语）。
     * 返回翻译后的字符串。
     */
    suspend fun translate(context: Context, text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val baseUrl = Config.baseUrl(context)
            val key = Config.apiKey(context)
            val model = Config.model(context)
            if (key.isBlank()) throw IllegalStateException("请先在设置里填写 API Key（⚙️ 设置）")
            if (model.isBlank()) throw IllegalStateException("请先在设置里选择模型（⚙️ 设置）")

            val srcName = if (source == "lo") "老挝语" else "中文"
            val tgtName = if (target == "lo") "老挝语" else "中文"

            // 翻译成老挝语时，额外要求给出拉丁字母罗马音转写，便于发音与对照。
            val prompt = if (target == "lo") {
                "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成老挝语。\n" +
                        "严格按照以下两行输出：\n" +
                        "第1行：只输出老挝语译文本身，不要任何解释、注释或引号。\n" +
                        "第2行：以「转写：」开头，输出第1行老挝语译文的拉丁字母罗马音（Latin romanization），用于发音参考。\n" +
                        "不要输出其它任何内容。文本如下：\n$text"
            } else {
                "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                        "只输出译文本身，不要任何解释、注释或引号。文本如下：\n$text"
            }

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是一名精准的老挝语-中文互译专家，译文要自然、准确、符合当地人表达。"))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.2)

            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $key")
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
