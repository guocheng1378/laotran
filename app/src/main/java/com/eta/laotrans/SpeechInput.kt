package com.eta.laotrans

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

/**
 * 系统语音识别（Collins 词典同款方案）：
 * SpeechRecognizer + RecognitionListener，语言跟随当前翻译方向，
 * 结果通过回调返回给 Activity 填入输入框。
 */
object SpeechInput {

    private const val REQ_CODE = 4001
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((String) -> Unit)? = null
    private var pendingLang: String? = null

    /** 设备上是否有可用语音识别服务 */
    fun isAvailable(activity: Activity): Boolean =
        SpeechRecognizer.isRecognitionAvailable(activity)

    /**
     * 开始识别。langCode 如 "lo-LA" / "zh-CN"。
     * onResult 在主线程回调识别文本；失败/取消不回调。
     */
    fun start(activity: Activity, langCode: String, onResult: (String) -> Unit) {
        listener = onResult
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLang = langCode
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CODE)
            return
        }
        beginListen(activity, langCode, onResult)
    }

    /** 权限结果转发入口，MainActivity 在 onRequestPermissionsResult 里调用 */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray,
        activity: Activity
    ) {
        if (requestCode != REQ_CODE) return
        val lang = pendingLang ?: return
        pendingLang = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginListen(activity, lang) { s -> listener?.invoke(s) }
        } else {
            Toast.makeText(activity, "未授予录音权限，无法语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginListen(activity: Activity, langCode: String, onResult: (String) -> Unit) {
        if (!isAvailable(activity)) {
            Toast.makeText(activity, "本机没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
            return
        }
        release()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    release()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        else -> "识别出错（code=$error）"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    release()
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (text.isNotEmpty()) onResult(text)
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
            startListening(intent)
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
