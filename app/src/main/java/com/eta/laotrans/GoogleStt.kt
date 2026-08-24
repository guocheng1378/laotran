package com.eta.laotrans

import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Speech-to-Text 录音 + 识别封装。
 * 采样率 16kHz，16bit，单声道 PCM。
 */
object GoogleStt {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SAMPLE_RATE = 16000
    private var audioRecord: AudioRecord? = null
    private var recording = false
    private val buffer = ByteArrayOutputStream()

    fun startRecording() {
        buffer.reset()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE * 2 * 3) // ~3秒缓冲
        val record = AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            bufSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        audioRecord = record
        recording = true
        record.startRecording()
        Thread {
            val buf = ByteArray(bufSize)
            while (recording) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) buffer.write(buf, 0, read)
            }
        }.start()
    }

    /** 停止录音，返回 PCM 数据 */
    fun stopRecording(): ByteArray {
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        return buffer.toByteArray()
    }

    /** 上传 PCM 到 Google Cloud STT，返回识别文本 */
    suspend fun recognize(pcm: ByteArray, apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
            val config = JSONObject()
                .put("languageCode", "lo-LA")
                .put("encoding", "LINEAR16")
                .put("sampleRateHertz", SAMPLE_RATE)
                .put("audioChannelCount", 1)
            val body = JSONObject()
                .put("config", config)
                .put("audio", JSONObject().put("content", b64))
            val req = Request.Builder()
                .url("https://speech.googleapis.com/v1/speech:recognize?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            if (!resp.isSuccessful) {
                Log.e("LaoTran", "Google STT HTTP ${resp.code}: $respBody")
                return@withContext null
            }
            val results = JSONObject(respBody).optJSONArray("results")
            val alt = results?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
            alt?.optString("transcript", null)
        } catch (e: Exception) {
            Log.e("LaoTran", "Google STT error", e)
            null
        }
    }
}
