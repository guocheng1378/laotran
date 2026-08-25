package com.eta.laotrans

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时配置：接口地址、API Key、模型名、界面语言。
 * 由 App 内的设置界面填写，持久化到 SharedPreferences，
 * 这样换任何大模型服务都不用重新打包。
 */
object Config {

    private const val PREFS = "laotran_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_LOCALE = "locale"

    // 默认值：b.ai
    fun defaultBaseUrl() = "https://api.b.ai/v1"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String =
        sp(c).getString(KEY_BASE_URL, defaultBaseUrl()) ?: defaultBaseUrl()

    fun apiKey(c: Context): String = sp(c).getString(KEY_API_KEY, "") ?: ""

    fun model(c: Context): String = sp(c).getString(KEY_MODEL, "") ?: ""

    /** 界面语言："zh" = 中文（默认），"lo" = 老挝文 */
    fun locale(c: Context): String = sp(c).getString(KEY_LOCALE, "zh") ?: "zh"

    fun save(c: Context, baseUrl: String, apiKey: String, model: String, locale: String = "zh") {
        sp(c).edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_LOCALE, locale)
            .apply()
    }

    /**
     * 是否已具备可翻译的配置。
     * 仅当 API Key 与 Model 都已填写才算配置完成。
     */
    fun isConfigured(c: Context): Boolean = apiKey(c).isNotBlank() && model(c).isNotBlank()
}
