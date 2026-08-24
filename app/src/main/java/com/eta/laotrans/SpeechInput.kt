package com.eta.laotrans

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast

/**
 * 系统语音识别：SpeechRecognizer + RecognitionListener。
 * 设备可能没有选中默认识别服务，因此按候选列表显式绑定：
 * 优先小米 AsrService（国产 ROM 核心语音），其次 Google TTS。
 * 绑定失败（ERROR_CANNOT_CONNECT）自动切换下一个候选。
 */
object SpeechInput {

    private const val TAG = "LaoVoice"
    private const val REQ_CODE = 4001

    // 候选识别服务（按优先级）
    private val candidateComponents = listOf(
        ComponentName("com.xiaomi.mibrain.speech", ".asr.AsrService"),
        ComponentName(
            "com.google.android.tts",
            "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
        )
    )

    private var recognizer: SpeechRecognizer? = null
    private var listener: ((String) -> Unit)? = null
    private var statusUpdater: ((String) -> Unit)? = null
    private var pendingLang: String? = null
    private var candidateIndex = 0

    /** 设备上是否有可用语音识别服务 */
    fun isAvailable(activity: Activity): Boolean {
        val ok = SpeechRecognizer.isRecognitionAvailable(activity)
        Log.d(TAG, "isRecognitionAvailable=$ok")
        return ok
    }

    /** 开始识别。langCode 如 "lo-LA" / "zh-CN" */
    fun start(activity: Activity, langCode: String, onStatus: (String) -> Unit, onResult: (String) -> Unit) {
        listener = onResult
        statusUpdater = onStatus
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "请求录音权限")
            pendingLang = langCode
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CODE)
            return
        }
        beginListen(activity, langCode)
    }

    /** 权限结果转发入口 */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray,
        activity: Activity
    ) {
        if (requestCode != REQ_CODE) return
        val lang = pendingLang ?: return
        pendingLang = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginListen(activity, lang)
        } else {
            statusUpdater?.invoke("未授予录音权限")
            Toast.makeText(activity, "未授予录音权限，无法语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginListen(activity: Activity, langCode: String) {
        if (!isAvailable(activity)) {
            Log.w(TAG, "无可用识别服务，放弃")
            statusUpdater?.invoke("本机没有可用的语音识别服务")
            return
        }
        release()
        candidateIndex = 0
        createAndListen(activity, langCode)
    }

    /** 找到第一个可 resolve 的候选组件 */
    private fun nextComponent(activity: Activity): ComponentName? {
        while (candidateIndex < candidateComponents.size) {
            val comp = candidateComponents[candidateIndex]
            candidateIndex++
            try {
                if (activity.packageManager.resolveService(
                        Intent("android.speech.RecognitionService").setComponent(comp),
                        PackageManager.MATCH_ALL
                    ) != null
                ) {
                    Log.d(TAG, "选用识别服务：$comp")
                    return comp
                }
                Log.w(TAG, "服务不存在：$comp")
            } catch (e: Exception) {
                Log.w(TAG, "resolve $comp 失败", e)
            }
        }
        return null
    }

    private fun createAndListen(activity: Activity, langCode: String) {
        val onStatus = statusUpdater
        val comp = nextComponent(activity)
        if (comp == null) {
            Log.w(TAG, "所有候选识别服务均不可用")
            onStatus?.invoke("没有可用的语音识别服务")
            return
        }

        val recognizerInstance = try {
            SpeechRecognizer.createSpeechRecognizer(activity, comp)
        } catch (e: Exception) {
            Log.e(TAG, "createSpeechRecognizer 异常", e)
            onStatus?.invoke("启动识别失败：${e.message}")
            return
        }
        recognizer = recognizerInstance

        recognizerInstance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                onStatus?.invoke("正在聆听…请说话")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                onStatus?.invoke("检测到说话…")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                onStatus?.invoke("识别中…")
            }
            override fun onError(error: Int) {
                Log.e(TAG, "onError code=$error（组件 $comp）")
                if (error == SpeechRecognizer.ERROR_CANNOT_CONNECT_TO_SERVICE
                    && candidateIndex < candidateComponents.size
                ) {
                    // 绑定失败，切换下一个候选
                    Log.w(TAG, "切换下一个识别服务候选")
                    release()
                    createAndListen(activity, langCode)
                    return
                }
                release()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    SpeechRecognizer.ERROR_CLIENT -> "语音识别服务未就绪"
                    SpeechRecognizer.ERROR_CANNOT_CONNECT_TO_SERVICE -> "语音识别服务不可用"
                    else -> "识别出错（code=$error）"
                }
                onStatus?.invoke(msg)
            }
            override fun onResults(results: Bundle?) {
                Log.d(TAG, "onResults")
                release()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                Log.d(TAG, "识别结果：$text")
                if (text.isNotEmpty()) listener?.invoke(text)
                else onStatus?.invoke("没听清，请再试一次")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
            putExtra("android.speech.extra.PREFER_OFFLINE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
        }
        try {
            recognizerInstance.startListening(intent)
            Log.d(TAG, "startListening 已调用（$comp）")
        } catch (e: Exception) {
            Log.e(TAG, "startListening 异常", e)
            onStatus?.invoke("启动识别失败：${e.message}")
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun release() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}