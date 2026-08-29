package com.eta.laotrans

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.GlobalScope
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 设置对话框：接口地址 / API Key / 模型名 / 翻译引擎。
 * 模型支持下拉选择（从 GET /models 拉取真实可用模型），列表可滚动，也可手动输入。
 * 保存后写入 SharedPreferences（Config）。
 * 另含「音频管理」区块：展示音频库数量并提供清空按钮（AudioHistoryStore.clear）。
 */
@Composable
internal fun SettingsDialogContent(show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    if (show) {
        Dialog(onDismissRequest = onDismiss) {
            Card(Modifier.fillMaxWidth()) {
                SettingsBody(onBack = onDismiss, onSaved = onSaved)
            }
        }
    }
}

/**
 * 整页设置面板（作为底部栏 tab 内容）。
 */
@Composable
internal fun SettingsPanel(onBack: () -> Unit, onSaved: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 110.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(text = "‹ 返回", onClick = onBack)
            Spacer(Modifier.width(8.dp))
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            SettingsBody(onBack = onBack, onSaved = onSaved)
        }
    }
}

@Composable
private fun SettingsBody(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(Config.baseUrl(context)) }
    var apiKey by remember { mutableStateOf(Config.apiKey(context)) }
    var model by remember { mutableStateOf(Config.model(context)) }
    var locale by remember { mutableStateOf(Config.locale(context)) }
    var translateMode by remember { mutableStateOf(Config.translateMode(context)) }
    var ttsBaseUrl by remember { mutableStateOf(Config.ttsBaseUrl(context)) }
    var modelResult by remember { mutableStateOf<TranslateEngine.ModelListResult?>(null) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // 接口地址 / API Key 变化时（防抖）自动拉取可用模型列表；展开菜单时也会刷新。
    // 关键修复：地址或 Key 一旦变化就强制重新拉取，避免"换了地址列表不刷新"。
    LaunchedEffect(baseUrl, apiKey, modelMenuExpanded) {
        delay(500)
        modelsLoading = true
        modelResult = withContext(Dispatchers.IO) {
            runCatching { TranslateEngine.listModels(baseUrl, apiKey) }
                .getOrElse { e -> TranslateEngine.ModelListResult(emptyList(), e.message ?: "未知错误") }
        }
        modelsLoading = false
    }

    val availableModels = modelResult?.models ?: emptyList()
    val modelError = modelResult?.error
    val candidates = if (availableModels.isNotEmpty()) availableModels else fallbackModels

    Column(Modifier.padding(20.dp)) {
        Text(context.getString(R.string.settings_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = context.getString(R.string.settings_base_url), useLabelAsPlaceholder = true)
        Text(
            "OpenAI 兼容地址，例如 https://api.b.ai/v1；模型列表从该地址的 /models 拉取（需有效 API Key）。",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        TextField(value = apiKey, onValueChange = { apiKey = it }, label = context.getString(R.string.settings_api_key), useLabelAsPlaceholder = true)
        Text(
            "API Key 以明文保存在本机，请仅在可信设备使用；使用免费翻译引擎可留空。",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
        TextField(value = model, onValueChange = { model = it }, label = context.getString(R.string.settings_model), useLabelAsPlaceholder = true)
        TextField(value = ttsBaseUrl, onValueChange = { ttsBaseUrl = it }, label = "语音合成（Gradio 模型）地址", useLabelAsPlaceholder = true)
        Text(
            "老挝语发音使用的 Gradio TTS 服务地址；若原服务失效，可改为新的 Space 地址（以 http 开头）。",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )

        // 模型下拉：点击展开可用模型列表，列表可滚动，点选回填
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable { modelMenuExpanded = !modelMenuExpanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (modelsLoading) "模型加载中…" else "可用模型（${candidates.size}）",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(text = "刷新", onClick = {
                    modelsLoading = true
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { TranslateEngine.listModels(baseUrl, apiKey) }
                            .getOrElse { e -> TranslateEngine.ModelListResult(emptyList(), e.message ?: "未知错误") }
                        withContext(Dispatchers.Main) {
                            modelResult = r
                            modelsLoading = false
                        }
                    }
                })
            }
            Text(
                text = if (modelMenuExpanded) "收起 ▴" else "展开 ▾",
                fontSize = 13.sp
            )
        }

        if (modelMenuExpanded) {
            Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp)
                ) {
                    if (modelsLoading) {
                        Text("加载中…", fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                    } else if (modelError != null) {
                        Text(
                            modelError ?: "拉取失败",
                            fontSize = 13.sp,
                            color = Color.Red.copy(alpha = 0.85f),
                            modifier = Modifier.padding(16.dp)
                        )
                        Text(
                            "可手动填写模型名（如 ${fallbackModels.first()}），保存后仍可使用。",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    } else if (candidates.isEmpty()) {
                        Text("未获取到模型，请手动输入模型名", fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                    } else {
                        candidates.forEach { cand ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { model = cand; modelMenuExpanded = false }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cand,
                                    fontSize = 15.sp,
                                    fontWeight = if (model == cand) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                if (model == cand) Text("✓", fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }

        // ====== 界面语言 ======
        Spacer(Modifier.height(20.dp))
        Text("界面语言", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "切换后保存并重启界面以生效；老挝语界面需系统字体支持。",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            listOf("zh" to "中文", "lo" to "老挝语").forEach { (code, labelText) ->
                val selected = locale == code
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { locale = code }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labelText,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) Text("✓", fontSize = 15.sp)
                }
            }
        }

        // ====== 翻译引擎 ======
        Spacer(Modifier.height(20.dp))
        Text("翻译引擎", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "自动：优先免费翻译，失败自动降级大模型；仅免费：只用 MyMemory 免费翻译（无需 API Key）；仅大模型：只用大模型（需 API Key）。",
            fontSize = 12.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        TranslateMode.values().forEach { mode ->
            val selected = translateMode == mode
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { translateMode = mode }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = translateModeLabel(mode),
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (selected) Text("✓", fontSize = 15.sp)
            }
        }

        // ====== 音频管理 ======
        Spacer(Modifier.height(20.dp))
        Text("音频管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        val audioCount = AudioHistoryStore.list(context).size
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "已保存音频：$audioCount 条",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    "翻译页朗读老挝语译文后，音频会保存在音频库中，可在「音频库」tab 回放或删除。",
                    fontSize = 12.sp,
                    color = Color.Gray.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "清空音频库",
                onClick = {
                    AudioHistoryStore.clear(context)
                    Toast.makeText(context, "音频库已清空", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("关于 / 更新", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        val updateMsg = remember { mutableStateOf<String?>(null) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前版本：${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 2.dp))
                updateMsg.value?.let { Text(it, fontSize = 12.sp, color = Color.Gray.copy(alpha = 0.85f)) }
            }
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "检查更新",
                onClick = {
                    updateMsg.value = "检查中…"
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val info = Updater.check()
                            withContext(Dispatchers.Main) {
                                if (info.hasUpdate && info.downloadUrl.isNotEmpty()) {
                                    updateMsg.value = "发现新版本 v${info.latestVersion}，正在下载…"
                                    runCatching { Updater.downloadAndInstall(context, info.downloadUrl) }
                                } else {
                                    updateMsg.value = "已是最新 (v${info.latestVersion})"
                                }
                            }
                        }.onFailure {
                            withContext(Dispatchers.Main) { updateMsg.value = "检查失败，请检查网络" }
                        }
                    }
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = context.getString(R.string.settings_cancel), onClick = onBack)
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = context.getString(R.string.settings_save),
                onClick = {
                    Config.save(context, baseUrl, apiKey, model, locale)
                    Config.saveTtsBaseUrl(context, ttsBaseUrl)
                    Config.saveTranslateMode(context, translateMode)
                    onSaved()
                    // 若界面语言有变化，重建 Activity 使新的 Locale 生效
                    (context as? android.app.Activity)?.recreate()
                },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

/** 翻译引擎选项的展示文案。 */
private fun translateModeLabel(mode: TranslateMode): String = when (mode) {
    TranslateMode.AUTO -> "自动（免费优先）"
    TranslateMode.FREE_ONLY -> "仅免费"
    TranslateMode.LLM_ONLY -> "仅大模型"
}

/** 内置常用模型（作为从服务端拉取失败时的回退候选） */
private val fallbackModels = listOf(
    "mimo-v2.5", "mimo-v2.5-pro", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5",
    "claude-opus-4.7", "claude-sonnet-4.6", "gemini-3.5-flash", "deepseek-v4-flash",
    "qwen3.8-max", "glm-5.1", "kimi-k2.6"
)
