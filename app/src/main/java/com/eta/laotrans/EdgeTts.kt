package com.eta.laotrans

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 微软 Edge 在线 TTS（免费、无需 API Key）。
 *
 * 直接复现 edge-tts 的 WebSocket 协议：连接 speech.platform.bing.com，
 * 先发 speech.config，再发 ssml，服务端以多个二进制帧返回音频（MP3）。
 * 与 edge-tts 唯一区别是本类用 Kotlin 原生实现，可直接跑在 Android 上。
 *
 * 老挝语可用语音（lo-LA 仅这两个，已用 edge-tts --list-voices 验证）：
 *  - [VOICE_FEMALE]  女声（默认）
 *  - [VOICE_MALE]    男声
 */
object EdgeTts {

    private const val TAG = "EdgeTts"

    // 与 edge-tts 完全一致，请勿随意改动
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

    const val VOICE_FEMALE = "lo-LA-KeomanyNeural"   // 女声（默认）
    const val VOICE_MALE = "lo-LA-ChanthavongNeural" // 男声

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dateFmt = SimpleDateFormat(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * 生成 Sec-MS-GEC 校验值，算法与 edge-tts 完全一致：
     * 取当前时间（1601 起的文件时间，单位 100 纳秒），向下取整到 5 分钟，
     * 拼接 TRUSTED_CLIENT_TOKEN 后做 SHA-256，结果转大写十六进制。
     * 该值与服务器时间强相关，每 5 分钟变化一次；设备时间偏差过大将导致鉴权失败。
     */
    private fun generateSecMsGec(): String {
        val winEpoch = 11644473600L
        var ticks = System.currentTimeMillis() / 1000L + winEpoch
        ticks -= ticks % 300L                  // 向下取整到 5 分钟
        val fileTime = ticks * 10_000_000L     // 换算为 100 纳秒间隔
        val strToHash = fileTime.toString() + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun jsDate(): String = dateFmt.format(Date())

    /** XML 转义 + 过滤微软不支持的控制字符（与 edge-tts 行为一致）。 */
    private fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> {
                        val code = c.code
                        if ((code in 0..8) || (code in 11..12) || (code in 14..31)) " " else c.toString()
                    }
                }
            )
        }
        return sb.toString()
    }

    private fun buildSsml(text: String, voice: String, rate: String = "+0%"): String {
        val escaped = xmlEscape(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='lo-LA'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>$escaped</prosody>" +
                "</voice></speak>"
    }

    private fun speechConfigMsg(): String {
        val ts = jsDate()
        return "X-Timestamp:$ts\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
    }

    private fun ssmlMsg(ssml: String): String {
        val ts = jsDate()
        val reqId = UUID.randomUUID().toString().replace("-", "")
        return "X-RequestId:$reqId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${ts}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                ssml
    }

    /**
     * 合成文本为 MP3 字节数组；失败返回 null。
     * @param voice 默认 [VOICE_FEMALE]（老挝女声），可传 [VOICE_MALE]（男声）。
     */
    suspend fun synthesize(
        text: String,
        voice: String = VOICE_FEMALE,
        rate: String = "+0%"
    ): ByteArray? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null

        val url = WSS_URL +
                "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "") +
                "&Sec-MS-GEC=" + generateSecMsGec() +
                "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val request = Request.Builder()
            .url(url)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/${CHROMIUM_FULL_VERSION.split(".")[0]}.0.0.0 " +
                        "Safari/537.36 Edg/${CHROMIUM_FULL_VERSION.split(".")[0]}.0.0.0"
            )
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("Cookie", "muid=$muid;")
            .build()

        suspendCancellableCoroutine<ByteArray?> { cont ->
            val audioOut = ByteArrayOutputStream()
            var completed = false
            val finish: (ByteArray?) -> Unit = { result ->
                if (!completed) {
                    completed = true
                    if (cont.isActive) cont.resume(result)
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(speechConfigMsg())
                    webSocket.send(ssmlMsg(buildSsml(trimmed, voice, rate)))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val idx = text.indexOf("\r\n\r\n")
                    val headers = if (idx >= 0) text.substring(0, idx) else text
                    val path = headers.lineSequence()
                        .firstOrNull { it.startsWith("Path:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()
                    when (path) {
                        "turn.end" -> {
                            webSocket.close(1000, null)
                            finish(audioOut.toByteArray())
                        }
                        "audio.metadata", "response", "turn.start" -> { /* ignore */ }
                        else -> Log.w(TAG, "unexpected text path: $path")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLength =
                        ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (headerLength > data.size - 2) return
                    // 二进制帧结构：[2 字节 header 长度][header][\r\n][audio]
                    val audioStart = 2 + headerLength + 2
                    if (audioStart < data.size) {
                        audioOut.write(data, audioStart, data.size - audioStart)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "ws failure: ${t.message}", t)
                    finish(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // 未收到 turn.end 也兜底返回已收集到的数据
                    finish(if (audioOut.size() > 0) audioOut.toByteArray() else null)
                }
            }

            val ws = client.newWebSocket(request, listener)
            cont.invokeOnCancellation { runCatching { ws.close(1000, null) } }
        }
    }
}
