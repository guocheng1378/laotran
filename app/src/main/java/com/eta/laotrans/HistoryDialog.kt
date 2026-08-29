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
 * 整页历史面板（作为底部栏 tab 内容）。
 *
 * 每条记录显示原文、译文、方向，以及「音频」状态（该译文是否已生成过并缓存了音频文件）。
 * 播放按钮优先使用本地缓存音频（[LaoSpeech.resolveCache] + [LaoSpeech.playFile]），
 * 未命中时才调用 [LaoSpeech.speak] 在线合成。
 *
 * @param onPick 点击某条记录时回调（回填输入框用），传 null 则不可点击。
 * @param onGotoAudio 点击「音频库」按钮时回调（切到底部音频库 tab），传 null 则不显示该按钮。
 */
@Composable
internal fun HistoryPanel(
    onBack: () -> Unit,
    onPick: ((HistoryRecord) -> Unit)? = null,
    onGotoAudio: (() -> Unit)? = null,
) {
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
            Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            Text(
                "暂无翻译历史",
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally)
            )
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                records.forEach { r ->
                    val audioPath = r.audioPath.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                    val hasAudio = audioPath != null || LaoSpeech.hasCachedAudio(context, r.dstText)
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .then(if (onPick != null) Modifier.clickable {
                                onPick(r)
                            } else Modifier)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.direction, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                if (hasAudio) {
                                    Text("🎵 已存音频", fontSize = 11.sp, color = Color(0xFF3482FF))
                                }
                                if (onPick != null) Text("点击回填", fontSize = 11.sp, color = Color.Gray)
                            }
                            Text(r.srcText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            Text(r.dstText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(text = "🔊 播放", onClick = {
                                    scope.launch {
                                        // 优先本地缓存音频
                                        val cached = audioPath ?: LaoSpeech.resolveCache(r.dstText, context)
                                        if (cached != null) {
                                            LaoSpeech.playFile(cached, 1.0f)
                                        } else {
                                            val body = r.dstText.substringBefore("转写：").substringBefore("拼音：").trim()
                                            LaoSpeech.speak(if (body.isNotEmpty()) body else r.dstText, context)
                                        }
                                    }
                                })
                                if (hasAudio && onGotoAudio != null) {
                                    TextButton(text = "📂 音频库", onClick = { onGotoAudio() })
                                }
                                TextButton(text = "🗑 删除", onClick = {
                                    HistoryStore.remove(context, r.id)
                                    records = HistoryStore.list(context)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
