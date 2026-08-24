package com.eta.laotrans

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用界面语言：中文 / 老挝文。
 * 在 Activity 创建前调用 [apply]，切换后调用 [apply] 会更新本 Spot
 * 的资源语言，再由 Activity recreate 生效。
 */
object LocaleUtils {

    private const val LO_LANG = "lo"
    private const val ZL_LANG = "zh"

    /** 返回当前配置里保存的语言（确保 BaseActivity 调用前 Config 已可读） */
    fun currentLocale(context: Context): String = Config.locale(context)

    /** 把当前界面语言应用到指定 Context 的资源 */
    fun apply(context: Context) {
        val lang = currentLocale(context)
        val locale = if (lang == LO_LANG) Locale(LO_LANG, "LA") else Locale(ZL_LANG, "CN")
        Locale.setDefault(locale)
        val conf = Configuration(context.resources.configuration)
        conf.setLocale(locale)
        context.resources.updateConfiguration(conf, context.resources.displayMetrics)
    }
}
