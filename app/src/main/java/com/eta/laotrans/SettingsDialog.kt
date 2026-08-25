package com.eta.laotrans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 大模型设置对话框：接口地址 / API Key / 模型名。
 * 模型支持下拉选择（从 GET /models 拉取真实可用模型），也可手动输入。
 * 保存后写入 SharedPreferences（Config）。
 */
@Composable
internal fun SettingsDialogContent(show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(Config.baseUrl(context)) }
    var apiKey by remember { mutableStateOf(Config.apiKey(context)) }
    var model by remember { mutableStateOf(Config.model(context)) }
    var locale by remember { mutableStateOf(Config.locale(context)) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // 展开模型下拉时，从服务端拉取可用模型列表（失败则回退到内置常用列表）
    LaunchedEffect(modelMenuExpanded) {
        if (modelMenuExpanded) {
            modelsLoading = true
            availableModels = withContext(Dispatchers.IO) {
                runCatching { TranslateEngine.listModelsSync(baseUrl, apiKey) }.getOrDefault(emptyList())
            }
            modelsLoading = false
        }
    }

    val candidates = if (availableModels.isNotEmpty()) availableModels else fallbackModels

    if (show) {
        Dialog(onDismissRequest = onDismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(context.getString(R.string.settings_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = context.getString(R.string.settings_base_url), useLabelAsPlaceholder = true)
                    TextField(value = apiKey, onValueChange = { apiKey = it }, label = context.getString(R.string.settings_api_key), useLabelAsPlaceholder = true)
                    TextField(value = model, onValueChange = { model = it }, label = context.getString(R.string.settings_model), useLabelAsPlaceholder = true)

                    // 模型下拉：点击展开可用模型列表，点选回填
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp)
                            .clickable { modelMenuExpanded = !modelMenuExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (modelsLoading) "模型加载中…" else "可用模型（${candidates.size}）",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (modelMenuExpanded) "收起 ▴" else "展开 ▾",
                            fontSize = 13.sp
                        )
                    }

                    if (modelMenuExpanded) {
                        Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                if (modelsLoading) {
                                    Text("加载中…", fontSize = 14.sp, modifier = Modifier.padding(20.dp))
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

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(text = context.getString(R.string.settings_cancel), onClick = onDismiss)
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = context.getString(R.string.settings_save),
                            onClick = {
                                Config.save(context, baseUrl, apiKey, model, locale)
                                onSaved()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }
}

/** 内置常用模型（作为从服务端拉取失败时的回退候选） */
private val fallbackModels = listOf(
    "mimo-v2.5", "mimo-v2.5-pro", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5",
    "claude-opus-4.7", "claude-sonnet-4.6", "gemini-3.5-flash", "deepseek-v4-flash",
    "qwen3.8-max", "glm-5.1", "kimi-k2.6"
)
