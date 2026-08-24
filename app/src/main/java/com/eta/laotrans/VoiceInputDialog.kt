package com.eta.laotrans

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog

/**
 * 液态玻璃风格语音输入对话框：
 * 用 SpeechRecognizer 绑定设备上的识别服务，界面完全自绘（与主界面玻璃风格统一），
 * 不再弹出无法换肤的系统识别对话框。
 */
class VoiceInputDialog(
    private val activity: Activity,
    private val langCode: String,
    private val onResult: (String) -> Unit
) : AppCompatDialog(activity) {

    private var recognizer: SpeechRecognizer? = null
    private var pulseAnim: android.animation.ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val win = window ?: return
        win.setBackgroundDrawable(ColorDrawable(0x00000000))
        win.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        win.setGravity(Gravity.CENTER)
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.setDimAmount(0.3f)
        setContentView(R.layout.dialog_voice)
        if (Build.VERSION.SDK_INT >= 31) {
            // decorView 就绪后再设置背景模糊，避免 PhoneWindow.mDecor 为 null 崩溃
            window?.decorView?.post { window?.setBackgroundBlurRadius(60) }
        }

        findViewById<Button>(R.id.voiceCancel)!!.setOnClickListener { dismiss() }
        startPulse()
        startRecognition()
    }

    private fun startRecognition() {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        } catch (e: Exception) {
            statusText().text = "无法连接识别服务"
            return
        }
        val r = recognizer ?: return

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText().text = "正在聆听…"
            }

            override fun onBeginningOfSpeech() {
                statusText().text = "检测到说话…"
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                statusText().text = "识别中…"
            }

            override fun onError(error: Int) {
                stopPulse()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再试一次"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    else -> "识别出错（$error）"
                }
                statusText().text = msg
                releaseRecognizer()
            }

            override fun onResults(results: Bundle?) {
                stopPulse()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                releaseRecognizer()
                if (text.isNotEmpty()) {
                    dismiss()
                    onResult(text)
                } else {
                    statusText().text = "没听清，请再试一次"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                partialText().text = t
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            statusText().text = "启动识别失败"
        }
    }

    private fun statusText(): TextView = findViewById<TextView>(R.id.voiceStatus)!!

    private fun partialText(): TextView = findViewById<TextView>(R.id.voicePartial)!!

    private fun startPulse() {
        val ring = findViewById<View>(R.id.micRing)!!
        pulseAnim = android.animation.ValueAnimator.ofFloat(1f, 1.35f, 1f).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                ring.scaleX = v
                ring.scaleY = v
                ring.alpha = 0.75f - (v - 1f) * 1.5f
            }
        }
        pulseAnim?.start()
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
    }

    private fun releaseRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    override fun onStop() {
        stopPulse()
        releaseRecognizer()
        super.onStop()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        releaseRecognizer()
        super.onDetachedFromWindow()
    }
}