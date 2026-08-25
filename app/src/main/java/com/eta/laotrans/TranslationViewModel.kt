package com.eta.laotrans

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 翻译界面状态与业务逻辑。
 * 独立于 UI（Composable），把翻译、语音识别结果、朗读、方向切换等
 * 逻辑从 LaotranApp 中抽离出来，便于维护与测试。
 */
class TranslationViewModel : ViewModel() {

    var input by mutableStateOf("")
    var result by mutableStateOf("")
    var status by mutableStateOf("")
    var dirMode by mutableIntStateOf(0)
    var speakSpeed by mutableStateOf(1.0f)
    var lastTranslated by mutableStateOf("")
    var showSettings by mutableStateOf(false)
    var showHistory by mutableStateOf(false)

    private var initialized = false
    private var translateJob: Job? = null
    private var speakJob: Job? = null
    private var jobToken = 0

    // 系统 TTS（中文朗读）
    private var ttsReady = false
    private var systemTts: TextToSpeech? = null

    private fun ctx(c: Context, resId: Int, vararg args: Any): String =
        if (args.isEmpty()) c.getString(resId) else c.getString(resId, *args)

    private fun label(c: Context, code: String): String =
        if (code == "zh") c.getString(R.string.label_zh) else c.getString(R.string.label_lo)

    private fun dirLabelText(c: Context): String = when (dirMode) {
        1 -> c.getString(R.string.dir_zh_lo)
        2 -> c.getString(R.string.dir_lo_zh)
        else -> c.getString(R.string.auto_recognize)
    }

    private fun effectiveDirection(text: String): Pair<String, String> = when (dirMode) {
        1 -> "zh" to "lo"
        2 -> "lo" to "zh"
        else -> TranslateEngine.autoDetect(text)
    }

    fun dirLabelTextForUi(c: Context): String = dirLabelText(c)

    /** 首次挂载时初始化语速与 TTS（幂等）。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        speakSpeed = loadSpeakSpeed(context)
        initTts(context)
    }

    private fun initTts(context: Context) {
        systemTts = TextToSpeech(context.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                systemTts?.language = Locale.CHINA
            }
        }
    }


    fun cycleDirMode(context: Context) {
        dirMode = (dirMode + 1) % 3
        val text = input.trim()
        if (text.isNotEmpty()) translate(context, false)
    }

    fun toggleDirection(context: Context) {
        dirMode = when (dirMode) { 0 -> 1; 1 -> 2; else -> 1 }
        val text = input.trim()
        if (text.isNotEmpty()) translate(context, false)
    }

    fun clearInput() {
        input = ""
        result = ""
        status = ""
        lastTranslated = ""
    }

    fun setSpeed(context: Context, v: Float) {
        speakSpeed = v
        context.getSharedPreferences("laotrans_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("speak_speed", v).apply()
    }

    private suspend fun speakWithSystemTts(context: Context, text: String): Boolean =
        withContext(Dispatchers.Main) {
            val tts = systemTts
            if (!ttsReady || tts == null) return@withContext false
            tts.setSpeechRate(speakSpeed)
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "laotrans_tts_${System.currentTimeMillis()}")
            r == TextToSpeech.SUCCESS
        }

    /** 朗读当前译文：老挝语走在线 TTS，中文走系统 TTS。 */
    fun speak(context: Context) {
        val full = result.trim()
        if (full.isEmpty()) {
            Toast.makeText(context, ctx(context, R.string.toast_speak_first), Toast.LENGTH_SHORT).show()
            return
        }
        val body = full.substringBefore("转写：").substringBefore("拼音：").trim()
        if (body.isEmpty()) {
            Toast.makeText(context, ctx(context, R.string.toast_speak_first), Toast.LENGTH_SHORT).show()
            return
        }
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            if (TranslateEngine.containsLao(body)) {
                status = ctx(context, R.string.status_synth_lao)
                val ok = LaoSpeech.speak(body, context, speakSpeed)
                status = if (ok) ctx(context, R.string.status_speak_ok) else ctx(context, R.string.status_speak_fail)
            } else {
                status = ctx(context, R.string.status_synth_voice)
                val ok = speakWithSystemTts(context, body)
                status = if (ok) ctx(context, R.string.status_speak_ok) else ctx(context, R.string.status_no_tts_zh)
            }
        }
    }

    /** 语音识别成功回调。 */
    fun onVoiceRecognized(context: Context, text: String) {
        input = text
        lastTranslated = ""
        translate(context, false)
    }

    /** 语音识别取消 / 未识别到内容。 */
    fun onVoiceCancelled(context: Context) {
        status = ctx(context, R.string.status_cancelled)
    }

    fun onVoiceUnheard(context: Context) {
        status = ctx(context, R.string.status_unheard)
    }

    fun setListeningStatus(context: Context) {
        status = ctx(context, R.string.status_listening)
    }

    fun setSpeechUnavailableStatus(context: Context) {
        status = ctx(context, R.string.status_no_speech)
    }

    fun setPermissionDeniedStatus(context: Context) {
        status = ctx(context, R.string.status_perm_denied)
    }

    /**
     * 翻译。自动防抖由 UI 的 LaunchedEffect 控制；
     * 此处使用 jobToken + 任务取消避免「过期请求覆盖最新结果」的竞态。
     */
    fun translate(context: Context, manual: Boolean) {
        val text = input.trim()
        if (text.isEmpty()) {
            if (manual) Toast.makeText(context, ctx(context, R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (!Config.isConfigured(context)) {
            status = ctx(context, R.string.hint_need_config)
            if (manual) showSettings = true
            return
        }
        if (text == lastTranslated && result.isNotEmpty() && !manual) return
        val (src, tgt) = effectiveDirection(text)
        val myToken = ++jobToken
        status = ctx(context, R.string.status_translating)
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            val full = StringBuilder()
            try {
                val res = TranslateEngine.translateStream(context, text, src, tgt) { delta ->
                    full.append(delta)
                    if (myToken == jobToken) {
                        withContext(Dispatchers.Main) { result = full.toString() }
                    }
                }
                // 若翻译期间文本已变化（新任务启动），丢弃过期结果
                if (myToken != jobToken) return@launch
                lastTranslated = text
                result = res
                HistoryStore.add(context, text, res, ctx(context, R.string.dir_progress, label(context, src), label(context, tgt)))
                if (tgt == "lo") {
                    status = ctx(context, R.string.status_done_speaking)
                    speak(context)
                } else {
                    status = ctx(context, R.string.status_done)
                }
            } catch (e: Exception) {
                if (myToken != jobToken) return@launch
                if (!manual && text != input.trim()) return@launch
                status = ctx(context, R.string.status_failed_with, e.message ?: "")
            }
        }
    }

    override fun onCleared() {
        translateJob?.cancel()
        speakJob?.cancel()
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
    }
}

private fun loadSpeakSpeed(context: Context): Float =
    context.getSharedPreferences("laotrans_prefs", Context.MODE_PRIVATE).getFloat("speak_speed", 1.0f)
