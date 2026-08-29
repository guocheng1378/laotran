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
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * 翻译引擎模式选择。
 */
enum class TranslateMode {
    /** 自动：优先免费翻译（MyMemory），失败时降级到大模型 LLM。 */
    AUTO,
    /** 仅免费：只用 MyMemory 免费翻译，不依赖大模型配置。 */
    FREE_ONLY,
    /** 仅大模型：只用 LLM（需配置 API Key / 模型名）。 */
    LLM_ONLY,
}

/**
 * 翻译引擎（大模型 LLM，OpenAI 兼容接口 + MyMemory 免费翻译）。
 *
 * 所有配置（接口地址 / API Key / 模型名）在 App 内的设置界面里填，通过 [Config] 持久化。
 *
 * 方向自动识别：输入含老挝字母 → 译成中文；否则 → 译成老挝语。
 * 翻译成老挝语时输出两行：老挝语译文 + 「转写：<罗马音>」。
 * 翻译成中文时输出两行：中文译文 + 「拼音：<汉语拼音>」。
 * 支持 [translateStream] 流式翻译：边生成边通过 onDelta 回调显示。
 *
 * 双层翻译缓存：内存 LRU 缓存 + 磁盘文件缓存（filesDir/translate_cache.json）。
 * 相同来源/目标/文本直接命中缓存、跳过 LLM 请求；App 重启后磁盘缓存依然生效。
 * 另提供收藏功能，持久化到 filesDir/favorites.json。
 */
object TranslateEngine {

    /** 一次性/普通请求客户端。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * 流式请求客户端：SSE 长连接需要不限读超时（readTimeout=0），
     * 但用 callTimeout 限制整次调用的最大时长，防止服务端挂起时无限等待。
     */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    // ====== 翻译缓存（内存 LRU + 磁盘持久化） ======

    private const val CACHE_MAX = 64 // 内存 LRU 上限
    private const val FILE_CACHE_MAX = 512 // 磁盘缓存上限
    private const val FILE_CACHE_NAME = "translate_cache.json"

    /** 内存 LRU 缓存：热路径快速命中。 */
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
    }

    /** 磁盘缓存的加载副本（Key = [cacheKey]，Value = 译文）。 */
    private val fileCache = LinkedHashMap<String, String>()
    private var fileCacheLoaded = false
    private val cacheLock = Any()

    private fun cacheKey(source: String, target: String, text: String) = "$source|$target|$text"

    private fun cacheFile(context: Context) = File(context.filesDir, FILE_CACHE_NAME)

    /** 懒加载磁盘缓存（幂等，仅首次真正读盘）。 */
    private fun loadFileCache(context: Context) {
        synchronized(cacheLock) {
            if (fileCacheLoaded) return
            fileCacheLoaded = true
            try {
                val f = cacheFile(context)
                if (!f.exists()) return
                val obj = JSONObject(f.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k)
                    if (v.isNotEmpty()) fileCache[k] = v
                }
            } catch (_: Exception) {
                // 缓存文件损坏/不可读时忽略，不影响翻译功能
            }
        }
    }

    /** 把磁盘缓存写回 translate_cache.json。 */
    private fun saveFileCache(context: Context) {
        synchronized(cacheLock) {
            try {
                val obj = JSONObject()
                for ((k, v) in fileCache) obj.put(k, v)
                val f = cacheFile(context)
                f.parentFile?.mkdirs()
                f.writeText(obj.toString())
            } catch (_: Exception) {
                // 写失败时忽略，仅丢失本次缓存
            }
        }
    }

    private fun cacheGetMem(key: String): String? = synchronized(cache) { cache[key] }

    private fun cachePutMem(key: String, value: String) {
        if (value.isBlank()) return
        synchronized(cache) { cache[key] = value }
    }

    /** 双层缓存读：先内存，后磁盘；磁盘命中会自动提升到内存。 */
    private fun cacheGet(context: Context, key: String): String? {
        cacheGetMem(key)?.let { return it }
        loadFileCache(context)
        synchronized(cacheLock) {
            val v = fileCache[key]
            if (v != null) cachePutMem(key, v)
            return v
        }
    }

    /** 双层缓存写：同时写入内存与磁盘。 */
    private fun cachePut(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        cachePutMem(key, value)
        loadFileCache(context)
        synchronized(cacheLock) {
            fileCache[key] = value
            while (fileCache.size > FILE_CACHE_MAX) {
                val it = fileCache.keys.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
            saveFileCache(context)
        }
    }

    /** 清空内存与磁盘翻译缓存。 */
    fun clearCache(context: Context) {
        synchronized(cache) { cache.clear() }
        synchronized(cacheLock) {
            fileCache.clear()
            try {
                val f = cacheFile(context)
                if (f.exists()) f.delete()
            } catch (_: Exception) {
            }
        }
    }

    // ====== 收藏 ======

    private const val FAVORITES_NAME = "favorites.json"
    private val favoritesLock = Any()

    /** 一条收藏：原文 [text] + 译文 [result] + 收藏时间 [time]。 */
    data class FavoriteEntry(val text: String, val result: String, val time: Long)

    private fun favoritesFile(context: Context) = File(context.filesDir, FAVORITES_NAME)

    /** 新增/更新收藏（按原文去重，命中则刷新时间）。 */
    fun favoriteTranslation(context: Context, text: String, result: String) {
        if (text.isBlank() || result.isBlank()) return
        synchronized(favoritesLock) {
            val list = loadFavorites(context).toMutableList()
            list.removeAll { it.text == text }
            list.add(0, FavoriteEntry(text, result, System.currentTimeMillis()))
            saveFavorites(context, list)
        }
    }

    /** 读取全部收藏（新收藏在前）。 */
    fun getFavorites(context: Context): List<FavoriteEntry> = synchronized(favoritesLock) {
        loadFavorites(context)
    }

    /** 删除指定原文的收藏。 */
    fun removeFavorite(context: Context, text: String) {
        synchronized(favoritesLock) {
            val list = loadFavorites(context).toMutableList()
            list.removeAll { it.text == text }
            saveFavorites(context, list)
        }
    }

    /** 清空全部收藏。 */
    fun clearFavorites(context: Context) {
        synchronized(favoritesLock) {
            try {
                val f = favoritesFile(context)
                if (f.exists()) f.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** 读取收藏文件（调用方需已持有 [favoritesLock]）。 */
    private fun loadFavorites(context: Context): List<FavoriteEntry> {
        try {
            val f = favoritesFile(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = mutableListOf<FavoriteEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text", "")
                if (text.isBlank()) continue
                out.add(FavoriteEntry(text, o.optString("result", ""), o.optLong("time", 0L)))
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /** 写入收藏文件（调用方需已持有 [favoritesLock]）。 */
    private fun saveFavorites(context: Context, list: List<FavoriteEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject()
                    .put("text", e.text)
                    .put("result", e.result)
                    .put("time", e.time))
            }
            val f = favoritesFile(context)
            f.parentFile?.mkdirs()
            f.writeText(arr.toString())
        } catch (_: Exception) {
            // 写失败忽略
        }
    }

    // ====== 语言检测 ======

    /** 是否包含老挝文字符（U+0E80–U+0EFF） */
    fun containsLao(text: String): Boolean = text.any { it in '\u0E80'..'\u0EFF' }

    /** 是否包含汉字（CJK 统一表意文字） */
    fun containsChinese(text: String): Boolean = text.any { it in '\u4E00'..'\u9FFF' }

    /**
     * 自动识别翻译方向：含老挝字母 → 老挝语→中文；
     * 含汉字或其他 → 中文→老挝语。返回 Pair(source, target)。
     */
    fun autoDetect(text: String): Pair<String, String> =
        if (containsLao(text)) "lo" to "zh" else "zh" to "lo"

    // ====== 免费翻译（MyMemory） ======

    /**
     * 免费翻译（MyMemory 后端）：先查双层缓存，未命中时调用 [MyMemoryTranslate]，
     * 成功后写回缓存。失败（无网络/超时/超配额）返回 null，由调用方决定降级策略。
     *
     * @param context 上下文（用于缓存 I/O）
     * @param text    待翻译文本
     * @param source  源语言代码（"zh" / "lo"）
     * @param target  目标语言代码（"zh" / "lo"）
     * @return 译文文本，失败返回 null
     */
    suspend fun translateFree(
        context: Context,
        text: String,
        source: String,
        target: String
    ): String? = withContext(Dispatchers.IO) {
        val key = cacheKey(source, target, text)
        cacheGet(context, key)?.let { return@withContext it }
        val translated = MyMemoryTranslate.translate(text, source, target)
        if (translated != null) cachePut(context, key, translated)
        translated
    }

    /**
     * 流式翻译：SSE 边生成边回调。onDelta 在后台线程被调用；
     * 返回完整拼接结果。失败抛异常。命中缓存时一次性回调完整结果。
     */
    suspend fun translateStream(
        context: Context,
        text: String,
        source: String,
        target: String,
        onDelta: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val key = cacheKey(source, target, text)
        cacheGet(context, key)?.let { cached ->
            // 命中缓存（内存或磁盘）：直接回调完整结果，跳过 LLM 请求
            onDelta(cached)
            return@withContext cached
        }

        val baseUrl = normalizeBaseUrl(Config.baseUrl(context))
        val keyToken = Config.apiKey(context)
        val model = Config.model(context)
        if (keyToken.isBlank()) throw IllegalStateException("请先在设置里填写 API Key（⚙️ 设置）")
        if (model.isBlank()) throw IllegalStateException("请先在设置里选择模型（⚙️ 设置）")

        val srcName = if (source == "lo") "老挝语" else "中文"
        val tgtName = if (target == "lo") "老挝语" else "中文"

        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                .put(JSONObject().put("role", "user").put("content", buildPrompt(text, srcName, tgtName, target))))
            .put("temperature", 0.2)

        val req = Request.Builder()
            .url(baseUrl + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $keyToken")
            .build()

        val full = StringBuilder()
        streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IllegalStateException("翻译请求失败 HTTP ${resp.code}: $err")
            }
            val src = resp.body?.source() ?: throw IllegalStateException("空响应")
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                try {
                    // JSONObject.optString 遇到 JSON null 会返回 "null" 字符串，
                    // 必须先判断 has/isNull，避免把服务端的空 chunk 拼成 "null"。
                    val choice = JSONObject(payload)
                        .getJSONArray("choices")
                        .optJSONObject(0)
                    val deltaObj = choice?.optJSONObject("delta")
                    val delta = if (deltaObj != null && deltaObj.has("content") && !deltaObj.isNull("content")) {
                        deltaObj.optString("content")
                    } else {
                        ""
                    }
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                } catch (_: Exception) {
                    // 忽略无法解析的心跳/注释行
                }
            }
        }
        val result = full.toString().trim()
        cachePut(context, key, result)
        result
    }

    // ====== 模型列表 ======

    /** 拉取服务端可用模型列表（OpenAI 兼容 GET /models）。失败返回空列表。 */
    /** 规范化 baseUrl：OpenAI 兼容服务接口约定在 /v1 下；若用户未显式包含 /v1 则自动补上。 */
    private fun normalizeBaseUrl(raw: String): String {
        val u = raw.trim().trimEnd('/')
        if (u.isEmpty() || u.endsWith("/v1")) return u
        return if (Regex("/v\\d").containsMatchIn(u)) u else "$u/v1"
    }

    fun listModelsSync(baseUrl: String, apiKey: String): List<String> {
        if (baseUrl.isBlank()) return emptyList()
        val url = normalizeBaseUrl(baseUrl) + "/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                // 兼容不同后端：优先 id，其次 name / model 字段
                o.optString("id").takeIf { it.isNotBlank() }
                    ?: o.optString("name").takeIf { it.isNotBlank() }
                    ?: o.optString("model").takeIf { it.isNotBlank() }
            }.distinct()
        }
    }

    // ====== prompt 构造 ======

    private fun systemPrompt(): String =
        "你是一名精准的老挝语-中文互译专家，译文要自然、准确、符合当地人表达。"

    private fun buildPrompt(text: String, srcName: String, tgtName: String, target: String): String {
        return when (target) {
            "lo" ->
                "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                        "请严格遵守以下输出格式，输出两行：\n" +
                        "第一行：只写老挝语译文，纯老挝文字，不要有任何符号、拼音或说明；\n" +
                        "第二行：以「转写：」开头，给出老挝语译文的拉丁转写（用罗马字母标注发音，帮助不懂老挝文字的人朗读）。\n" +
                        "不要输出其他任何内容。文本如下：\n$text"
            "zh" ->
                "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                        "请严格遵守以下输出格式，输出两行：\n" +
                        "第一行：只写中文译文，不要有任何符号、拼音或说明；\n" +
                        "第二行：以「拼音：」开头，给出中文译文的汉语拼音（全部标注声调，用空格分隔词语，帮助不认识汉字的人朗读）。\n" +
                        "不要输出其他任何内容。文本如下：\n$text"
            else ->
                "你是一名专业的老挝语-中文翻译。请把下面的文本从${srcName}翻译成${tgtName}。" +
                        "只输出译文本身，不要任何解释、注释或引号。文本如下：\n$text"
        }
    }
}
