package com.eta.laotrans

import android.Manifest
import android.util.Log
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    // 方向模式：0 自动识别，1 中文→老挝语，2 老挝语→中文
    private var dirMode = 0

    // 中文朗读：系统 TTS
    private var systemTts: TextToSpeech? = null
    private var ttsReady = false

    // 防抖自动翻译
    private val handler = Handler(Looper.getMainLooper())
    private var autoTranslateJob: Job? = null
    private var isAutoInserting = false
    private var lastTranslated: String = ""

    // 语音输入：应用内 SpeechRecognizer（自绘界面，识别器就绪后才提示说话）
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var fallbackToSystemTried = false
    private var lastListenLang = "zh-CN"
    private lateinit var voiceZhBtn: Button
    private lateinit var voiceLaBtn: Button
    private var activeVoiceBtn: Button? = null
    private var pendingVoiceLang = "zh-CN"
    private var pendingVoiceDir = 1
    private val REQ_SPEECH = 4002
    private val REQ_RECORD_AUDIO = 4003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        resultText = findViewById(R.id.resultText)
        statusText = findViewById(R.id.statusText)
        dirLabel = findViewById(R.id.dirLabel)

        findViewById<Button>(R.id.translateBtn).setOnClickListener { doTranslate(manual = true) }
        findViewById<Button>(R.id.swapBtn).setOnClickListener { toggleDirection() }
        dirLabel.setOnClickListener { cycleDirMode() }
        findViewById<Button>(R.id.speakBtn).setOnClickListener { doSpeak() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        voiceZhBtn = findViewById(R.id.voiceZhBtn)
        voiceLaBtn = findViewById(R.id.voiceLaBtn)
        voiceZhBtn.setOnClickListener { startVoiceInput("zh-CN", 1) }
        voiceLaBtn.setOnClickListener { startVoiceInput("lo-LA", 2) }
        setupSpeedControl()
        setupAutoTranslate()
        setupTts()

        // 预热语音识别服务：提前绑定，避免首次点击时冷启动导致识别失败
        runCatching { getRecognizer() }

        updateDirLabel()
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

    /** 点击方向胶囊：自动 → 中→老 → 老→中 → 自动 循环 */
    private fun cycleDirMode() {
        dirMode = (dirMode + 1) % 3
        updateDirLabel()
        val text = inputText.text.toString().trim()
        if (text.isNotEmpty()) doTranslate(manual = false)
    }

    /** ⇄ 互换：自动时切到 中→老；已指定方向则交换方向 */
    private fun toggleDirection() {
        dirMode = when (dirMode) {
            0 -> 1
            1 -> 2
            else -> 1
        }
        updateDirLabel()
        val text = inputText.text.toString().trim()
        if (text.isNotEmpty()) doTranslate(manual = false)
    }

    /** 更新方向胶囊文案 */
    private fun updateDirLabel() {
        dirLabel.text = when (dirMode) {
            1 -> "中文 → 老挝语"
            2 -> "老挝语 → 中文"
            else -> "自动识别：中文 ⇄ 老挝语"
        }
    }

    /** 计算当前生效方向：自动识别或用户指定 */
    private fun effectiveDirection(text: String): Pair<String, String> {
        return when (dirMode) {
            1 -> "zh" to "lo"
            2 -> "lo" to "zh"
            else -> TranslateEngine.autoDetect(text)
        }
    }

    private fun label(code: String) = if (code == "zh") "中文" else "老挝语"

    private fun showSettings() {
        SettingsDialog.show(this) { /* 配置变化后无需额外刷新 */ }
    }

    // ====== 语音输入：应用内 SpeechRecognizer ======
    private fun getRecognizer(): SpeechRecognizer {
        if (speechRecognizer == null) {
            val cn = findRecognitionComponent()
            speechRecognizer = if (cn != null) {
                SpeechRecognizer.createSpeechRecognizer(this, cn)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizer?.setRecognitionListener(speechListener)
        }
        return speechRecognizer!!
    }

    /**
     * 寻找可用的语音识别服务组件。
     * 部分 ROM（如精简过的小米）voice_recognition_service 为空，
     * SpeechRecognizer 默认路径会直接报 ERROR_CLIENT，必须显式指定组件。
     */
    private fun findRecognitionComponent(): ComponentName? {
        // 1) 系统已配置的
        val configured = Settings.Secure.getString(
            contentResolver, "voice_recognition_service"
        )
        if (!configured.isNullOrBlank()) {
            ComponentName.unflattenFromString(configured)?.let { return it }
        }
        // 2) 从已安装识别服务中按优先级挑选
        val services = runCatching {
            packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0
            )
        }.getOrNull().orEmpty()
        if (services.isEmpty()) return null
        val preferred = listOf(
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
            "com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService"
        )
        for (flat in preferred) {
            val cn = ComponentName.unflattenFromString(flat) ?: continue
            if (services.any {
                    it.serviceInfo.packageName == cn.packageName &&
                        it.serviceInfo.name == cn.className
                }) return cn
        }
        val first = services.first()
        return ComponentName(first.serviceInfo.packageName, first.serviceInfo.name)
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // 识别器真正就绪，此时提示说话才能录上
            runOnUiThread {
                isListening = true
                activeVoiceBtn?.text = "⏹ 聆听中"
                statusText.text = "请说话…"
            }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            runOnUiThread { statusText.text = "识别中…" }
        }

        override fun onError(error: Int) {
            runOnUiThread {
                isListening = false
                resetVoiceButtons()
                statusText.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，请再试一次"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语音服务不支持当前语言，请改用文字输入"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，请检查网络后重试"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务忙，请稍后再试"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    else -> "识别失败（$error），请重试"
                }
                // ERROR_CLIENT（识别服务不可用/未配置）时回退到系统识别界面
                if (error == SpeechRecognizer.ERROR_CLIENT && !fallbackToSystemTried) {
                    fallbackToSystemTried = true
                    statusText.text = "应用内识别不可用，改用系统识别…"
                    launchSystemRecognition(lastListenLang)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            runOnUiThread {
                isListening = false
                resetVoiceButtons()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (text.isNullOrEmpty()) {
                    statusText.text = "没听清，请再试一次"
                    return@runOnUiThread
                }
                // 老挝语识别模式下检查结果是否真的含老挝文字
                if (lastListenLang == "lo-LA" && !containsLaoScript(text)) {
                    statusText.text = "识别结果不是老挝语（系统语音服务不支持老挝语识别），请改用文字输入"
                    // 仍填入，让用户看到实际结果
                } else if (lastListenLang == "zh-CN" && containsLaoScript(text)) {
                    statusText.text = "检测到老挝语，请改用 🎤老 按钮识别"
                }
                isAutoInserting = true
                inputText.setText(text)
                inputText.setSelection(text.length)
                isAutoInserting = false
                lastTranslated = ""
                doTranslate(manual = false)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private var recognizerSupportedLanguages: Set<String>? = null

    /** 查询识别服务支持的语言列表（通过标准 ACTION_GET_LANGUAGE_DETAILS 广播） */
    private fun checkRecognizerLanguages(callback: (Set<String>) -> Unit) {
        if (recognizerSupportedLanguages != null) {
            callback(recognizerSupportedLanguages!!)
            return
        }
        val cn = findRecognitionComponent()
        if (cn == null) {
            callback(emptySet())
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS).apply {
            setPackage(cn.packageName)
        }
        val answered = arrayOf(false)
        // 超时保护：识别服务不响应广播时视为语言未知，不阻塞语音输入
        val timeoutRunnable = object : Runnable {
            override fun run() {
                if (!answered[0]) {
                    answered[0] = true
                    callback(emptySet())
                }
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed(timeoutRunnable, 2500)
        sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, i: Intent) {
                if (answered[0]) return
                answered[0] = true
                mainHandler.removeCallbacks(timeoutRunnable)
                val langs = i.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                )?.toSet() ?: emptySet()
                recognizerSupportedLanguages = langs
                callback(langs)
            }
        }, null, Activity.RESULT_OK, null, null)
    }

    /** 判断文本是否包含老挝文（Unicode U+0E80–U+0EFF） */
    private fun containsLaoScript(text: String): Boolean {
        return text.any { it.code in 0x0E80..0x0EFF }
    }

    private fun resetVoiceButtons() {
        voiceZhBtn.text = "🎤中"
        voiceLaBtn.text = "🎤老"
    }

    private fun startVoiceInput(listenLang: String, forcedDir: Int) {
        lastListenLang = listenLang
        // 中文语音 -> 中文转老挝语；老挝语音 -> 老挝语转中文
        if (dirMode != forcedDir) {
            dirMode = forcedDir
            updateDirLabel()
        }

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceLang = listenLang
            pendingVoiceDir = forcedDir
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO
            )
            return
        }

        if (isListening) {
            // 再点一下停止聆听
            activeVoiceBtn?.text = if (activeVoiceBtn == voiceZhBtn) "🎤中" else "🎤老"
            isListening = false
            activeVoiceBtn = null
            runCatching { speechRecognizer?.stopListening() }
            return
        }

        // 异步查询识别服务语言支持
        checkRecognizerLanguages { supported ->
            runOnUiThread {
                if (supported.isNotEmpty() && listenLang !in supported) {
                    Log.d("LaoTran", "语音服务不支持 $listenLang, supported=$supported, 仍尝试识别")
                }
                doStartListening(listenLang)
            }
        }
    }

    private fun doStartListening(listenLang: String) {
        activeVoiceBtn = if (listenLang == "zh-CN") voiceZhBtn else voiceLaBtn
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            getRecognizer().startListening(intent)
            statusText.text = "识别器准备中…"
        } catch (e: Exception) {
            statusText.text = "没有可用的语音识别服务"
            Toast.makeText(this, "没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
        }
    }

    /** 系统识别 Activity（回退路径：应用内 SpeechRecognizer 不可用时） */
    private fun launchSystemRecognition(listenLang: String) {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                val lang = pendingVoiceLang
                val dir = pendingVoiceDir
                startVoiceInput(lang, dir)
            } else {
                statusText.text = "未授予录音权限"
            }
        }
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

        // 自动识别或用户指定方向
        val (src, tgt) = effectiveDirection(text)
        updateDirLabel()
        statusText.text = "${label(src)} → ${label(tgt)}"

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
        speechRecognizer?.destroy()
        speechRecognizer = null
        systemTts?.stop()
        systemTts?.shutdown()
        super.onDestroy()
    }
}