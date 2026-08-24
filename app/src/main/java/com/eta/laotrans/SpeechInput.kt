package com.eta.laotrans

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast

/**
 * 系统语音识别（Collins 词典同款方案）：
 * SpeechRecognizer + RecognitionListener，语言跟随当前翻译方向，
 * 结果通过回调返回给 Activity 填入输入框。
 *
 * 显式绑定可用的识别服务（优先 Google），避免系统未选默认服务导致 ERROR_CLIENT。
 */
object SpeechInput {

    private const val TAG = "LaoVoice"
    private const val REQ_CODE = 4001
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((String) -> Unit)? = null
    private var statusUpdater: ((String) -> Unit)? = null
    private var pendingLang: String? = null

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

    /** 挑选可绑定的识别服务组件，优先 Google */
    private fun pickComponent(activity: Activity): ComponentName? {
        // 方案 A：通过 RecognitionServiceInfo 枚举（API 26+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val infoClass = Class.forName("android.speech.RecognitionServiceInfo")
                val getAvail = infoClass.getMethod("getAvailableServices", Activity::class.java)
                @Suppress("UNCHECKED_CAST")
                val list = getAvail.invoke(null, activity) as? List<*> ?: emptyList<Any>()
                Log.d(TAG, "可用服务数：${list.size}")
                for (item in list) {
                    val service = item?.javaClass?.getMethod("getService")?.invoke(item)
                    Log.d(TAG, "  $service")
                    if (service is ComponentName && service.packageName.contains("google")) {
                        return service
                    }
                }
                // 取第一个
                for (item in list) {
                    val service = item?.javaClass?.getMethod("getService")?.invoke(item)
                    if (service is ComponentName) return service
                }
            } catch (e: Exception) {
                Log.w(TAG, "RecognitionServiceInfo 反射失败", e)
            }
        }

        // 方案 B：硬编码已知服务
        val candidates = listOf(
            ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            ),
            ComponentName(
                "com.xiaomi.mibrain.speech",
                ".asr.AsrService"
            )
        )
        for (c in candidates) {
            try {
                if (activity.packageManager.resolveService(
                        Intent("android.speech.RecognitionService").setComponent(c),
                        PackageManager.MATCH_ALL
                    ) != null
                ) {
                    Log.d(TAG, "硬编码找到可用服务：$c")
                    return c
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve $c 失败", e)
            }
        }
        return null
    }

    private fun beginListen(activity: Activity, langCode: String) {
        val onStatus = statusUpdater
        if (!isAvailable(activity)) {
            Log.w(TAG, "无可用识别服务，放弃")
            onStatus?.invoke("本机没有可用的语音识别服务")
            return
        }
        release()

        val comp = pickComponent(activity)
        Log.d(TAG, "选定组件：${comp ?: "无（fallback 默认）"}")

        recognizer = if (comp != null) {
            SpeechRecognizer.createSpeechRecognizer(activity, comp)
        } else {
            Log.w(TAG, "未找到可用识别服务组件，使用默认（可能因无默认选中而失败）")
            SpeechRecognizer.createSpeechRecognizer(activity)
        }.apply {
            setRecognitionListener(object : RecognitionListener {
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
                    Log.e(TAG, "onError code=$error")
                    release()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        SpeechRecognizer.ERROR_CLIENT -> "语音识别服务未就绪，请检查系统设置"
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
                startListening(intent)
                Log.d(TAG, "startListening 已调用")
            } catch (e: Exception) {
                Log.e(TAG, "startListening 异常", e)
                onStatus?.invoke("启动识别失败：${e.message}")
            }
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