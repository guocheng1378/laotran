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
 * 通过 [Config] 持久化。
 *
 * 翻译成老挝语时输出两行：老挝语译文 + 「转写：<罗马音>」。
 * 支持 [translateStream] 流式翻译：边生成边通过 onDelta 回调显示。
 */
object TranslateEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 大模型响应可能慢
        .build()

    /** 无读超时限制的客户端，专用于流式请求（流式间隔可能超过 60s） */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 流式不设读超时
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
     * 返回翻译后的字符串。翻译成老挝语时为「老挝语译文\n转写：罗马音」两行格式。
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

            val prompt = buildPrompt(text, srcName, tgtName, target == "lo")

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt()))
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

    /**
     * 流式翻译：SSE 边生成边回调。onDelta 在后台线程被调用，收到每个增量片段；
     * 返回完整拼接结果。失败抛异常。
     *
     * @param onDelta 每次收到增量时回调（参数为新增的文本片段）
     */
    suspend fun translateStream(
        context: Context,
        text: String,
        source: String,
        target: String,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val baseUrl = Config.baseUrl(context)
        val key = Config.apiKey(context)
        val model = Config.model(context)
        if (key.isBlank()) throw IllegalStateException("请先在设置里填写 API Key（⚙️ 设置）")
        if (model.isBlank()) throw IllegalStateException("请先在设置里选择模型（⚙️ 设置）")

        val srcName = if (source == "lo") "老挝语" else "中文"
        val tgtName = if (target == "lo") "老挝语" else "中文"
        val prompt = buildPrompt(text, srcName, tgtName, target == "lo")

        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.2)

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $key")
            .build()

        val full = StringBuilder()
        streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IllegalStateException("翻译请求失败 HTTP ${resp.code}: $err")
            }
            val source2 = resp.body?.source() ?: throw IllegalStateException("空响应")
            while (true) {
                val line = source2.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                try {
                    val delta = JSONObject(payload)
                        .getJSONArray("choices")
                        .optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content") ?: ""
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                } catch (_: Exception) {
                    // 忽略无法解析的心跳/注释行
                }
            }
        }
        full.toString().trim()
    }

    // ====== prompt 构造 ======

    private fun systemPrompt(): String =
        "你是一名精准的老挝语-中文互译专家，译文要自然、准确、符合当地人表达。"

    private fun buildPrompt(text: String, srcName: String, tgtName: String, toLao: Boolean): String {
        return if (toLao) {
            "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                    "请严格遵守以下输出格式，输出两行：\n" +
                    "第一行：只写老挝语译文，纯老挝文字，不要有任何符号、拼音或说明；\n" +
                    "第二行：以「转写：」开头，给出老挝语译文的拉丁转写（用罗马字母标注发音，帮助不懂老挝文字的人朗读）。\n" +
                    "不要输出其他任何内容。文本如下：\n$text"
        } else {
            "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                    "只输出译文本身，不要任何解释、注释或引号。文本如下：\n$text"
        }
    }
}
