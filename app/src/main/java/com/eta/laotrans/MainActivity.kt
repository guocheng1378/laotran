package com.eta.laotrans

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var dirLabel: TextView
    private var speechRecognizer: SpeechRecognizer? = null

    // 当前方向：默认 中文 → 老挝语
    private var source: String = "zh"
    private var target: String = "lo"
    private var lastResult: String = ""

    // 语音语速（默认 1.0）
    private var speakSpeed: Float = 1.0f

    private val RECORD_AUDIO_REQ: Int = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        resultText = findViewById(R.id.resultText)
        statusText = findViewById(R.id.statusText)
        dirLabel = findViewById(R.id.dirLabel)

        findViewById<Button>(R.id.translateBtn).setOnClickListener { doTranslate() }
        findViewById<Button>(R.id.swapBtn).setOnClickListener { swapDirection() }
        findViewById<Button>(R.id.speakBtn).setOnClickListener { doSpeak() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.voiceBtn).setOnClickListener { startVoiceInput() }
        setupSpeedControl()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun swapDirection() {
        val old = source
        source = target
        target = old
        updateDirLabel()
        Toast.makeText(this, "已切换：${label(source)} → ${label(target)}", Toast.LENGTH_SHORT).show()
    }

    private fun updateDirLabel() {
        dirLabel.text = "${label(source)} → ${label(target)}"
    }

    private fun label(code: String) = if (code == "zh") "中文" else "老挝语"

    private fun showSettings() {
        SettingsDialog.show(this) { refreshConfigStatus() }
    }

    private fun refreshConfigStatus() {
        val configured = Config.isConfigured(this)
        val model = Config.model(this)
        val hint = if (configured && model.isNotBlank()) {
            "已配置：${Config.baseUrl(this)} · ${model}"
        } else {
            "未配置翻译服务，请点击 ⚙️ 设置"
        }
        statusText.text = hint
    }

    // ====== 语音输入 ======
    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQ)
            return
        }
        runVoice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQ) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runVoice()
            } else {
                Toast.makeText(this, "需要录音权限才能语音输入", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runVoice() {
        // 先尝试显式绑定 GoogleTTS 识别服务（permission=null，可被第三方绑定），
        // 不行再回落到系统默认 recognizer。不依赖 isRecognitionAvailable 硬拦截。
        val candidates = listOf(
            ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"),
            ComponentName("com.xiaomi.mibrain.speech", "com.xiaomi.mibrain.speech.asr.AsrService")
        )

        speechRecognizer?.destroy()

        // 默认组件
        speechRecognizer = if (SpeechRecognizer.isRecognitionAvailable(this)) {
            SpeechRecognizer.createSpeechRecognizer(this)
        } else {
            null
        }
        // 若默认组件不可用，尝试候选组件
        if (speechRecognizer == null) {
            for (c in candidates) {
                try {
                    val sr = SpeechRecognizer.createSpeechRecognizer(this, c)
                    speechRecognizer = sr
                    break
                } catch (e: Exception) {
                    // 该组件绑定失败，继续尝试下一个
                }
            }
        }
        if (speechRecognizer == null) {
            statusText.text = "设备没有可用的语音识别引擎"
            Toast.makeText(this, "设备没有可用的语音识别引擎", Toast.LENGTH_SHORT).show()
            return
        }

        // 老挝语语音识别：lo-LA；若引擎不支持则回落到默认语言
        val locale = if (source == "lo") java.util.Locale("lo", "LA") else java.util.Locale.getDefault()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "请说${if (source == "lo") "老挝语" else "中文"}…"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val r = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!r.isNullOrEmpty()) {
                    // 实时显示识别中的文字
                    val txt = r[0]
                    inputText.setText(txt)
                    inputText.setSelection(txt.length)
                }
            }
            override fun onResults(results: Bundle?) {
                val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!r.isNullOrEmpty()) {
                    inputText.setText(r[0])
                    inputText.setSelection(r[0].length)
                    statusText.text = "已识别，正在翻译…"
                    doTranslate()
                } else {
                    statusText.text = "未识别到语音"
                    Toast.makeText(this@MainActivity, "未识别到语音", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再试"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "识别引擎不支持老挝语"
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎繁忙"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，无法识别"
                    else -> "语音识别出错（$error）"
                }
                statusText.text = msg
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    // ====== 翻译 ======
    private fun doTranslate() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Config.isConfigured(this)) {
            statusText.text = "请先在 ⚙️ 设置里填写 API Key 和模型"
            showSettings()
            return
        }
        statusText.text = "翻译中…"
        lifecycleScope.launch {
            try {
                val result = TranslateEngine.translate(this@MainActivity, text, source, target)
                lastResult = result
                resultText.text = result
                // 翻译结果是老挝语时，自动触发朗读
                if (target == "lo") {
                    statusText.text = "翻译完成，正在朗读…"
                    doSpeak()
                } else {
                    statusText.text = "翻译完成"
                }
            } catch (e: Exception) {
                statusText.text = "翻译失败：${e.message}"
            }
        }
    }

    private fun doSpeak() {
        val full = resultText.text.toString().trim()
        if (full.isEmpty()) {
            Toast.makeText(this, "先翻译，再朗读", Toast.LENGTH_SHORT).show()
            return
        }
        // 若翻译结果含「转写：」行，朗读时只取老挝语原文部分
        val text = if (full.contains("转写：")) full.substringBefore("转写：").trim() else full
        if (text.isEmpty()) {
            Toast.makeText(this, "先翻译，再朗读", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "正在合成老挝语音…"
        lifecycleScope.launch {
            val ok = LaoSpeech.speak(text, this@MainActivity, speakSpeed)
            statusText.text = if (ok) "发音成功 🔊" else "发音失败"
        }
    }

    private fun setupSpeedControl() {
        val prefs = getSharedPreferences("laotrans_prefs", MODE_PRIVATE)
        val saved = prefs.getFloat("speak_speed", 1.0f)
        speakSpeed = saved
        val group = findViewById<RadioGroup>(R.id.speedGroup)
        val map = mapOf(
            R.id.speed075 to 0.75f,
            R.id.speed100 to 1.0f,
            R.id.speed125 to 1.25f,
            R.id.speed150 to 1.5f,
            R.id.speed200 to 2.0f
        )
        for ((id, v) in map) {
            if (v == saved) { group.check(id); break }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val v = map[checkedId] ?: 1.0f
            speakSpeed = v
            prefs.edit().putFloat("speak_speed", v).apply()
        }
    }
}
