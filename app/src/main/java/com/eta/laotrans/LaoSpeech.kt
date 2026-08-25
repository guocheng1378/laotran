package com.eta.laotrans

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 老挝语语音合成引擎（在线 Meta MMS TTS）
 *
 * 原理：把老挝语文本发给在线 Gradio Space（kenjichou/lao-tts-api），
 * 拿到生成的 wav 文件，再用 MediaPlayer 播放。
 *
 * 模块持有当前播放器引用（[currentPlayer]），避免播放中被 GC 回收；
 * 播放新的音频前会先停止并释放旧的。
 */
object LaoSpeech {

    private const val BASE = "https://kenjichou-lao-tts-api.hf.space"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    /** 停止并释放当前正在播放的音频（若存在）。 */
    fun stop() {
        currentPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        currentPlayer = null
    }

    /**
     * 合成老挝语音并播放。返回 true 表示已成功触发播放。
     */
    suspend fun speak(text: String, context: Context, speed: Float = 1.0f): Boolean = withContext(Dispatchers.IO) {
        try {
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
            val joinBody = joinResp.body?.string() ?: return@withContext false
            joinResp.close()

            val eventId = Regex("\"event_id\":\"([^\"]+)\"").find(joinBody)?.groupValues?.get(1)
                ?: return@withContext false

            // 2) 轮询等待合成完成（SSE：text/event-stream，逐行读取），从 process_completed 里取 wav url
            val pollUrl = "$BASE/gradio_api/queue/data?session_hash=$session&event_id=$eventId"
            val pollReq = Request.Builder().url(pollUrl)
                .header("User-Agent", "Mozilla/5.0").build()
            val pollResp = client.newCall(pollReq).execute()
            val body = pollResp.body ?: return@withContext false
            val source = body.source()

            var wavUrl: String? = null
            val sb = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                sb.append(line).append("\n")
                if (line.contains("process_completed")) {
                    val jsonStr = line.substringAfter("data:").trim()
                    wavUrl = jsonStr.let {
                        runCatching {
                            val obj = JSONObject(it)
                            obj.getJSONObject("output")
                                .getJSONArray("data")
                                .getJSONObject(0)
                                .getString("url")
                        }.getOrNull()
                    }
                    break
                }
            }
            pollResp.close()

            // 兜底：若上面没解析到，再用宽松正则从全文取 url
            if (wavUrl.isNullOrBlank()) {
                wavUrl = Regex("\"url\":\"([^\"]+)\"").find(sb.toString())?.groupValues?.get(1)
            }
            wavUrl = wavUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@withContext false

            // 还原可能的 \/ 转义
            wavUrl = wavUrl.replace("\\/", "/")
            if (!wavUrl.startsWith("http")) return@withContext false

            // 3) 下载 wav
            val dlReq = Request.Builder().url(wavUrl)
                .header("User-Agent", "Mozilla/5.0").build()
            val dlResp = client.newCall(dlReq).execute()
            val bytes = dlResp.body?.bytes() ?: return@withContext false
            dlResp.close()

            if (bytes.isEmpty()) return@withContext false

            // 4) 保存到缓存并播放（先停止旧播放器，再持有新引用）
            val wavFile = File(context.cacheDir, "lao_speak_${System.currentTimeMillis()}.wav")
            FileOutputStream(wavFile).use { it.write(bytes) }

            withContext(Dispatchers.Main) {
                currentPlayer?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                val player = MediaPlayer()
                currentPlayer = player
                player.setDataSource(wavFile.absolutePath)
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
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
