package com.eta.laotrans

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时配置：接口地址、API Key、模型名、界面语言、翻译引擎模式。
 * 由 App 内的设置界面填写，持久化到 SharedPreferences，
 * 这样换任何大模型服务都不用重新打包。
 */
object Config {

    private const val PREFS = "laotran_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_LOCALE = "locale"
    private const val KEY_TRANSLATE_MODE = "translate_mode"
    private const val KEY_TTS_BASE_URL = "tts_base_url"
    private const val KEY_TTS_ENGINE = "tts_engine"
    private const val KEY_TTS_VOICE = "tts_voice"

    /** 语音合成首选引擎：自动(Edge 优先) / 仅 Edge / 仅 MMS(Gradio 兜底)。 */
    enum class TtsEngine { AUTO, EDGE, MMS }

    /** 语音合成首选引擎（默认自动：Edge 优先，失败回退 MMS）。 */
    fun ttsEngine(c: Context): TtsEngine {
        val raw = sp(c).getString(KEY_TTS_ENGINE, TtsEngine.AUTO.name) ?: TtsEngine.AUTO.name
        return runCatching { TtsEngine.valueOf(raw) }.getOrDefault(TtsEngine.AUTO)
    }
    fun saveTtsEngine(c: Context, e: TtsEngine) {
        sp(c).edit().putString(KEY_TTS_ENGINE, e.name).apply()
    }

    /** Edge TTS 音色：默认女声；仅对 Edge 引擎生效，MMS 固定单音色。 */
    fun ttsVoice(c: Context): String =
        sp(c).getString(KEY_TTS_VOICE, EdgeTts.VOICE_FEMALE) ?: EdgeTts.VOICE_FEMALE
    fun saveTtsVoice(c: Context, v: String) {
        sp(c).edit().putString(KEY_TTS_VOICE, v).apply()
    }

    // 默认值：b.ai
    fun defaultBaseUrl() = "https://api.b.ai/v1"

    /** 语音合成（Gradio TTS 模型）服务地址，可在设置中切换；默认 kenjichou 的 MMS TTS。 */
    fun defaultTtsBaseUrl() = "https://kenjichou-lao-tts-api.hf.space"

    fun ttsBaseUrl(c: Context): String =
        sp(c).getString(KEY_TTS_BASE_URL, defaultTtsBaseUrl()) ?: defaultTtsBaseUrl()

    fun saveTtsBaseUrl(c: Context, url: String) {
        sp(c).edit().putString(KEY_TTS_BASE_URL, url.trim()).apply()
    }

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String =
        sp(c).getString(KEY_BASE_URL, defaultBaseUrl()) ?: defaultBaseUrl()

    fun apiKey(c: Context): String = sp(c).getString(KEY_API_KEY, "") ?: ""

    fun model(c: Context): String = sp(c).getString(KEY_MODEL, "") ?: ""

    /** 界面语言："zh" = 中文（默认），"lo" = 老挝文 */
    fun locale(c: Context): String = sp(c).getString(KEY_LOCALE, "zh") ?: "zh"

    /**
     * 翻译引擎模式：默认 [TranslateMode.AUTO]（免费优先，失败降级 LLM）。
     * 读取异常时回退到默认值，保证不崩溃。
     */
    fun translateMode(c: Context): TranslateMode {
        val raw = sp(c).getString(KEY_TRANSLATE_MODE, TranslateMode.AUTO.name)
            ?: TranslateMode.AUTO.name
        return runCatching { TranslateMode.valueOf(raw) }.getOrDefault(TranslateMode.AUTO)
    }

    fun save(c: Context, baseUrl: String, apiKey: String, model: String, locale: String = "zh") {
        sp(c).edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_LOCALE, locale)
            .apply()
    }

    /** 单独持久化翻译引擎模式。 */
    fun saveTranslateMode(c: Context, mode: TranslateMode) {
        sp(c).edit().putString(KEY_TRANSLATE_MODE, mode.name).apply()
    }

    /**
     * 是否已具备可翻译的配置。
     * 仅当 API Key 与 Model 都已填写才算配置完成。
     */
    fun isConfigured(c: Context): Boolean = apiKey(c).isNotBlank() && model(c).isNotBlank()
}
