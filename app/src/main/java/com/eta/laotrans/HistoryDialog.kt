package com.eta.laotrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 翻译历史对话框：展示历史记录，支持播放与删除。
 */
@Composable
internal fun HistoryScreenContent(show: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(HistoryStore.list(context)) }
    if (show) {
        Dialog(onDismissRequest = onBack) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(context.getString(R.string.history_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    if (records.isEmpty()) {
                        Text(context.getString(R.string.history_empty), modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
                    } else {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            records.forEach { r ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(r.direction, fontSize = 11.sp)
                                        Text(r.srcText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                        Text(r.dstText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(text = context.getString(R.string.btn_play), onClick = { scope.launch { LaoSpeech.speak(r.dstText, context) } })
                                            TextButton(text = context.getString(R.string.btn_delete), onClick = { HistoryStore.remove(context, r.id); records = HistoryStore.list(context) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(text = context.getString(R.string.history_clear), onClick = { HistoryStore.clear(context); records = HistoryStore.list(context) })
                        Spacer(Modifier.width(8.dp))
                        TextButton(text = "‹ 返回", onClick = onBack)
                    }
                }
            }
        }
    }
}
