package com.eta.laotrans

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var dirLabel: TextView

    // 当前方向：默认 中文 → 老挝语
    private var source: String = "zh"
    private var target: String = "lo"
    private var lastResult: String = ""

    // 语音语速（默认 1.0）
    private var speakSpeed: Float = 1.0f

    // 防抖自动翻译
    private val handler = Handler(Looper.getMainLooper())
    private var autoTranslateJob: Job? = null
    private var isAutoInserting = false   // 程序填入文本（如语音识别结果）时暂停自动翻译
    private var lastTranslated: String = "" // 上次已翻译的原文，避免重复请求

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        resultText = findViewById(R.id.resultText)
        statusText = findViewById(R.id.statusText)
        dirLabel = findViewById(R.id.dirLabel)

        findViewById<Button>(R.id.translateBtn).setOnClickListener { doTranslate(manual = true) }
        findViewById<Button>(R.id.swapBtn).setOnClickListener { swapDirection() }
        findViewById<Button>(R.id.speakBtn).setOnClickListener { doSpeak() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.voiceBtn).setOnClickListener { openKeyboardVoice() }
        setupSpeedControl()
        setupAutoTranslate()
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
                    resultText.text = ""
                    statusText.text = ""
                    return
                }
                if (text == lastTranslated) return // 内容未变不重复翻译
                handler.postDelayed(autoTranslateRunnable, 600)
            }
        })
    }

    private val autoTranslateRunnable = Runnable { doTranslate(manual = false) }

    private fun swapDirection() {
        val old = source
        source = target
        target = old
        updateDirLabel()
        Toast.makeText(this, "已切换：${label(source)} → ${label(target)}", Toast.LENGTH_SHORT).show()
        // 方向切换后重新翻译当前内容
        val text = inputText.text.toString().trim()
        if (text.isNotEmpty()) doTranslate(manual = false)
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

    // ====== 语音输入：改由系统键盘的语音听写提供 ======
    private fun openKeyboardVoice() {
        inputText.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
        statusText.text = "请在键盘上点击麦克风图标语音输入"
    }

    /**
     * 程序向输入框填入文本时使用（避免触发自动翻译循环）。
     * 填入后立即手动翻译一次。
     */
    private fun insertProgrammatically(text: String) {
        isAutoInserting = true
        inputText.setText(text)
        inputText.setSelection(text.length)
        isAutoInserting = false
        handler.removeCallbacks(autoTranslateRunnable)
        doTranslate(manual = false)
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
        if (text == lastTranslated && resultText.text.isNotEmpty() && !manual) return

        statusText.text = "翻译中…"
        autoTranslateJob?.cancel()
        autoTranslateJob = lifecycleScope.launch {
            try {
                val full = StringBuilder()
                val result = TranslateEngine.translateStream(
                    this@MainActivity, text, source, target
                ) { delta ->
                    full.append(delta)
                    // 流式增量回主线程更新界面
                    runOnUiThread {
                        resultText.text = full.toString()
                    }
                }
                lastResult = result
                lastTranslated = text
                resultText.text = result
                // 翻译结果是老挝语时，自动触发朗读
                if (target == "lo") {
                    statusText.text = "翻译完成，正在朗读…"
                    doSpeak()
                } else {
                    statusText.text = "翻译完成"
                }
            } catch (e: Exception) {
                if (!manual && text != inputText.text.toString().trim()) {
                    // 用户已继续输入，忽略这次过期的失败
                    return@launch
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        autoTranslateJob?.cancel()
        super.onDestroy()
    }
}
