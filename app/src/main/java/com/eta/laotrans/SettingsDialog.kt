package com.eta.laotrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 大模型设置对话框：接口地址 / API Key / 模型名。
 * 保存后写入 SharedPreferences（Config）。
 */
@Composable
internal fun SettingsDialogContent(show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(Config.baseUrl(context)) }
    var apiKey by remember { mutableStateOf(Config.apiKey(context)) }
    var model by remember { mutableStateOf(Config.model(context)) }
    var locale by remember { mutableStateOf(Config.locale(context)) }
    if (show) {
        Dialog(onDismissRequest = onDismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(context.getString(R.string.settings_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = context.getString(R.string.settings_base_url), useLabelAsPlaceholder = true)
                    TextField(value = apiKey, onValueChange = { apiKey = it }, label = context.getString(R.string.settings_api_key), useLabelAsPlaceholder = true)
                    TextField(value = model, onValueChange = { model = it }, label = context.getString(R.string.settings_model), useLabelAsPlaceholder = true)
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
