package com.eta.laotrans

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
        val text = resultText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "先翻译，再朗读", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "正在合成老挝语音…"
        lifecycleScope.launch {
            val ok = LaoSpeech.speak(text, this@MainActivity)
            statusText.text = if (ok) "发音成功 🔊" else "发音失败"
        }
    }
}
