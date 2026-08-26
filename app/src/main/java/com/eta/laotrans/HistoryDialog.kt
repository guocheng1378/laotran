package com.eta.laotrans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 整页历史面板（作为底部栏 tab 内容）：展示历史记录，支持播放与删除。
 * 传入 [onPick] 后，点击某条记录会把其原文回填到输入框（由调用方处理跳转）。
 */
@Composable
internal fun HistoryPanel(onBack: () -> Unit, onPick: ((HistoryRecord) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(HistoryStore.list(context)) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(bottom = 110.dp)
    ) {
        // 顶栏：返回 + 标题
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(text = "‹ 返回", onClick = onBack)
            Spacer(Modifier.width(8.dp))
            Text(context.getString(R.string.history_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        if (records.isEmpty()) {
            Text(
                context.getString(R.string.history_empty),
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally)
            )
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                records.forEach { r ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .then(if (onPick != null) Modifier.clickable { onPick(r) } else Modifier)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.direction, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                if (onPick != null) Text("点击回填", fontSize = 11.sp, color = Color.Gray)
                            }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = context.getString(R.string.history_clear), onClick = { HistoryStore.clear(context); records = HistoryStore.list(context) })
                Spacer(Modifier.width(8.dp))
                TextButton(text = "‹ 返回", onClick = onBack)
            }
        }
    }
}
