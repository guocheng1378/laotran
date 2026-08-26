package com.eta.laotrans

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 已保存音频记录（音频库列表项）。 */
data class SavedAudio(
    val path: String,
    val text: String,
    val time: Long
)

/**
 * 老挝语语音合成引擎（在线 Meta MMS TTS）+ 本地音频持久化。
 *
 * 流程：
 * 1) 合成：老挝语文本 -> 在线 Gradio Space（kenjichou/lao-tts-api）队列 -> 得到 wav url。
 * 2) 持久化：下载 wav 保存到 context.filesDir/audio/lao_yyyyMMdd_HHmmss.wav，
 *    并把记录写入 [AudioHistoryStore]（text、filePath、timestamp）。
 * 3) 本地缓存：相同文本再次朗读时，先查内存 [memCache]、再查 [AudioHistoryStore]，
 *    命中直接回放已保存文件，不请求网络。
 * 4) 播放：MediaPlayer 播放；[currentPlayer] 持有引用避免被 GC 回收，播放新音频前先停止旧。
 */
object LaoSpeech {

    private const val BASE = "https://kenjichou-lao-tts-api.hf.space"
    private const val AUDIO_DIR = "audio"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    /** 内存缓存：text -> 已保存 wav 文件绝对路径（避免重复读 SharedPreferences）。 */
    private val memCache = ConcurrentHashMap<String, String>()

    /** 音频目录（filesDir/audio），不存在则创建。 */
    private fun audioDir(context: Context): File =
        File(context.filesDir, AUDIO_DIR).apply { if (!exists()) mkdirs() }

    /** 停止并释放当前正在播放的音频（若存在）。 */
    fun stop() {
        currentPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        currentPlayer = null
    }

    /**
     * 合成老挝语音并播放。
     * 优先命中本地缓存（内存 -> AudioHistoryStore），未命中才在线合成并持久保存。
     * 返回 true 表示已成功触发播放。
     */
    suspend fun speak(text: String, context: Context, speed: Float = 1.0f): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // 1) 本地缓存命中：直接回放已保存文件，不请求网络
        val cached = resolveCache(trimmed, context)
        if (cached != null) {
            playFile(cached, speed)
            return true
        }

        // 2) 未命中：在线合成 + 持久保存 + 回放
        return withContext(Dispatchers.IO) {
            try {
                val wavUrl = synthesize(trimmed) ?: return@withContext false

                val dlReq = Request.Builder().url(wavUrl)
                    .header("User-Agent", "Mozilla/5.0").build()
                val dlResp = client.newCall(dlReq).execute()
                val bytes = dlResp.body?.bytes() ?: return@withContext false
                dlResp.close()
                if (bytes.isEmpty()) return@withContext false

                // 3) 保存到 filesDir/audio/lao_yyyyMMdd_HHmmss.wav
                val wavFile = newAudioFile(context)
                FileOutputStream(wavFile).use { it.write(bytes) }

                // 4) 写入持久化记录 + 内存缓存
                AudioHistoryStore.add(context, trimmed, wavFile.absolutePath)
                memCache[trimmed] = wavFile.absolutePath

                // 5) 回放
                playFile(wavFile.absolutePath, speed)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /** 查本地缓存：先内存再持久化；命中返回文件路径，文件丢失则清理对应记录。 */
    private fun resolveCache(text: String, context: Context): String? {
        memCache[text]?.let { path ->
            if (File(path).exists()) return path
            memCache.remove(text)
        }
        val rec = AudioHistoryStore.findByText(context, text) ?: return null
        if (File(rec.filePath).exists()) {
            memCache[text] = rec.filePath
            return rec.filePath
        }
        AudioHistoryStore.removeByPath(context, rec.filePath)
        return null
    }

    /** 提交 Gradio 队列合成并轮询，返回 wav 下载 url（失败返回 null）。 */
    private fun synthesize(text: String): String? {
        val session = "lao${System.currentTimeMillis()}"

        // 1) 提交合成任务到队列
        val joinJson = JSONObject()
            .put("data", JSONArray(arrayOf(text)))
            .put("event_data", JSONObject.NULL)
            .put("fn_index", 2)
            .put("trigger_id", 2)
            .put("session_hash", session)

        val joinReq = Request.Builder()
            .url("$BASE/gradio_api/queue/join")
            .post(joinJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val joinResp = client.newCall(joinReq).execute()
        val joinBody = joinResp.body?.string() ?: return null
        joinResp.close()

        val eventId = Regex("\"event_id\":\"([^\"]+)\"").find(joinBody)?.groupValues?.get(1)
            ?: return null

        // 2) 轮询等待合成完成（SSE：text/event-stream，逐行读取）
        val pollUrl = "$BASE/gradio_api/queue/data?session_hash=$session&event_id=$eventId"
        val pollReq = Request.Builder().url(pollUrl)
            .header("User-Agent", "Mozilla/5.0").build()
        val pollResp = client.newCall(pollReq).execute()
        val body = pollResp.body ?: return null
        val source = body.source()

        var wavUrl: String? = null
        val sb = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            sb.append(line).append("\n")
            if (line.contains("process_completed")) {
                val jsonStr = line.substringAfter("data:").trim()
                wavUrl = runCatching {
                    JSONObject(jsonStr)
                        .getJSONObject("output")
                        .getJSONArray("data")
                        .getJSONObject(0)
                        .getString("url")
                }.getOrNull()
                break
            }
        }
        pollResp.close()

        // 3) 兜底：若上面没解析到，再用宽松正则从全文取 url
        if (wavUrl.isNullOrBlank()) {
            wavUrl = Regex("\"url\":\"([^\"]+)\"").find(sb.toString())?.groupValues?.get(1)
        }
        wavUrl = wavUrl?.trim()?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
        return wavUrl
    }

    /** 生成持久化 wav 文件名（lao_yyyyMMdd_HHmmss.wav，撞名时追加序号避免覆盖）。 */
    private fun newAudioFile(context: Context): File {
        val dir = audioDir(context)
        val base = "lao_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var file = File(dir, "$base.wav")
        var i = 1
        while (file.exists()) {
            file = File(dir, "${base}_$i.wav")
            i++
        }
        return file
    }

    /** 读取已保存音频列表（最新在前），供音频库面板展示。 */
    fun getSavedAudioList(context: Context): List<SavedAudio> =
        AudioHistoryStore.list(context)
            .filter { it.filePath.isNotBlank() && File(it.filePath).exists() }
            .map { SavedAudio(it.filePath, it.text, it.timestamp) }

    /** 删除音频文件并同步清理 AudioHistoryStore 记录与内存缓存。 */
    fun deleteAudio(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        val fileDeleted = !file.exists() || file.delete()
        memCache.entries.removeAll { it.value == path }
        val recDeleted = AudioHistoryStore.removeByPath(context, path)
        return fileDeleted || recDeleted
    }

    /** 回放本地 wav 文件（供音频库面板回放）。 */
    fun playFile(path: String, speed: Float = 1.0f) {
        if (path.isBlank() || !File(path).exists()) return
        Handler(Looper.getMainLooper()).post { playOnMain(path, speed) }
    }

    /** 在主线程执行 MediaPlayer 播放（先停止旧播放器，再持有新引用）。 */
    private fun playOnMain(path: String, speed: Float) {
        currentPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        try {
            val player = MediaPlayer()
            currentPlayer = player
            player.setDataSource(path)
            player.setOnCompletionListener { mp ->
                runCatching { mp.release() }
                if (currentPlayer === mp) currentPlayer = null
            }
            player.prepare()
            if (speed > 0f && speed != 1.0f) {
                try {
                    player.setPlaybackParams(PlaybackParams().setSpeed(speed))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            player.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
