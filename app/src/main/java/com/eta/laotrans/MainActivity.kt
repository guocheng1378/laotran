package com.eta.laotrans

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var dirLabel: TextView

    // 语音语速（默认 1.0）
    private var speakSpeed: Float = 1.0f

    // 自动方向识别；⇄ 按下后强制取反方向
    private var forceReverse = false

    // 中文朗读：系统 TTS
    private var systemTts: TextToSpeech? = null
    private var ttsReady = false

    // 防抖自动翻译
    private val handler = Handler(Looper.getMainLooper())
    private var autoTranslateJob: Job? = null
    private var isAutoInserting = false
    private var lastTranslated: String = ""

    // 语音输入（系统识别 Activity）
    private val REQ_SPEECH = 4002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        resultText = findViewById(R.id.resultText)
        statusText = findViewById(R.id.statusText)
        dirLabel = findViewById(R.id.dirLabel)

        findViewById<Button>(R.id.translateBtn).setOnClickListener { doTranslate(manual = true) }
        findViewById<Button>(R.id.swapBtn).setOnClickListener { toggleForceReverse() }
        findViewById<Button>(R.id.speakBtn).setOnClickListener { doSpeak() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.voiceBtn).setOnClickListener { startVoiceInput() }
        setupSpeedControl()
        setupAutoTranslate()
        setupTts()

        dirLabel.text = "自动识别方向"
    }

    /** 初始化系统 TTS（用于中文朗读） */
    private fun setupTts() {
        systemTts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                systemTts?.language = Locale.CHINA
            }
        }
    }

    /** 输入停顿 600ms 后自动翻译（流式） */
    private fun setupAutoTranslate() {
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isAutoInserting) return
                handler.removeCallbacks(autoTranslateRunnable)
                val text = s?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    lastTranslated = ""
                    resultText.text = ""
                    statusText.text = ""
                    return
                }
                if (text == lastTranslated) return
                handler.postDelayed(autoTranslateRunnable, 600)
            }
        })
    }

    private val autoTranslateRunnable = Runnable { doTranslate(manual = false) }

    /** ⇄ 切换：强制反向，再按一次恢复自动 */
    private fun toggleForceReverse() {
        forceReverse = !forceReverse
        Toast.makeText(
            this,
            if (forceReverse) "已强制反向方向，再按一次恢复自动识别" else "已恢复自动识别方向",
            Toast.LENGTH_SHORT
        ).show()
        val text = inputText.text.toString().trim()
        if (text.isNotEmpty()) doTranslate(manual = false)
    }

    /** 计算当前生效方向：默认自动识别；forceReverse 时取反 */
    private fun effectiveDirection(text: String): Pair<String, String> {
        val (s, t) = TranslateEngine.autoDetect(text)
        return if (forceReverse) t to s else s to t
    }

    private fun label(code: String) = if (code == "zh") "中文" else "老挝语"

    private fun showSettings() {
        SettingsDialog.show(this) { /* 配置变化后无需额外刷新 */ }
    }

    // ====== 语音输入：系统识别 Activity（自带录音与 UI） ======
    private fun startVoiceInput() {
        // 听写语言与翻译方向相反：译成老挝语就听写中文，反之听老挝语
        val text = inputText.text.toString().trim()
        val (_, tgt) = effectiveDirection(text)
        val listenLang = if (tgt == "lo") "zh-CN" else "lo-LA"

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
            }
            startActivityForResult(intent, REQ_SPEECH)
            statusText.text = "正在聆听…"
        } catch (e: Exception) {
            statusText.text = "没有可用的语音识别应用"
            Toast.makeText(this, "没有可用的语音识别应用", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH) return
        if (resultCode != Activity.RESULT_OK) {
            statusText.text = "已取消"
            return
        }
        val text = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (text.isNullOrEmpty()) {
            statusText.text = "没听清，请再试一次"
            return
        }
        isAutoInserting = true
        inputText.setText(text)
        inputText.setSelection(text.length)
        lastTranslated = ""
        doTranslate(manual = false)
        isAutoInserting = false
    }

    // ====== 翻译 ======
    private fun doTranslate(manual: Boolean) {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) {
            if (manual) Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Config.isConfigured(this)) {
            statusText.text = "请先在 ⚙️ 设置里填写 API Key 和模型"
            if (manual) showSettings()
            return
        }

        // 自动识别（或强制）方向
        val (src, tgt) = effectiveDirection(text)
        dirLabel.text = buildString {
            append("${label(src)} → ${label(tgt)}")
            if (forceReverse) append("（已反向）")
        }

        if (text == lastTranslated && resultText.text.isNotEmpty() && !manual) return

        statusText.text = "翻译中…"
        autoTranslateJob?.cancel()
        autoTranslateJob = lifecycleScope.launch {
            try {
                val full = StringBuilder()
                val result = TranslateEngine.translateStream(
                    this@MainActivity, text, src, tgt
                ) { delta ->
                    full.append(delta)
                    runOnUiThread { resultText.text = full.toString() }
                }
                lastTranslated = text
                resultText.text = result
                // 翻译成老挝语时自动朗读（在线 MMS）；译成中文不自动读
                if (tgt == "lo") {
                    statusText.text = "翻译完成，正在朗读…"
                    doSpeak()
                } else {
                    statusText.text = "翻译完成"
                }
            } catch (e: Exception) {
                if (!manual && text != inputText.text.toString().trim()) {
                    return@launch // 用户已继续输入，忽略过期失败
                }
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
        // 去掉「转写：」「拼音：」行，只读译文本体
        val body = full.substringBefore("转写：").substringBefore("拼音：").trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "先翻译，再朗读", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (TranslateEngine.containsLao(body)) {
                // 老挝语：在线 MMS 合成
                statusText.text = "正在合成老挝语音…"
                val ok = LaoSpeech.speak(body, this@MainActivity, speakSpeed)
                statusText.text = if (ok) "发音成功 🔊" else "发音失败"
            } else {
                // 中文/其他：系统 TTS
                statusText.text = "正在合成语音…"
                val ok = speakWithSystemTts(body)
                statusText.text = if (ok) "发音成功 🔊" else "本机没有可用的中文语音引擎"
            }
        }
    }

    private suspend fun speakWithSystemTts(text: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val tts = systemTts
            if (!ttsReady || tts == null) return@withContext false
            tts.setSpeechRate(speakSpeed)
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "laotrans_tts_${System.currentTimeMillis()}")
            r == TextToSpeech.SUCCESS
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        autoTranslateJob?.cancel()
        systemTts?.stop()
        systemTts?.shutdown()
        super.onDestroy()
    }
}