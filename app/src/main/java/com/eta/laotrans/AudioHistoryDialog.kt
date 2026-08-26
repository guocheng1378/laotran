package com.eta.laotrans

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 音频库面板（整页）：展示已保存的老挝语音频列表。
 * 每条显示文本与生成时间，支持回放（LaoSpeech.playFile）与删除
 * （LaoSpeech.deleteAudio）；无记录时展示引导提示；顶栏带返回按钮。
 */
@Composable
internal fun AudioHistoryPanel(onBack: () -> Unit) {
    val context = LocalContext.current
    var audios by remember { mutableStateOf(LaoSpeech.getSavedAudioList(context)) }

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
            Text("音频库", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))

        if (audios.isEmpty()) {
            // 空状态引导
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无已保存音频", fontSize = 16.sp, color = Color.White.copy(alpha = 0.9f))
                Text(
                    "在翻译页朗读老挝语译文后，音频会自动保存在这里，可随时回放或删除。",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                audios.forEach { a ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                a.text,
                                fontSize = 14.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatAudioTime(a.time),
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(text = "播放", onClick = { LaoSpeech.playFile(a.path, 1.0f) })
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    text = "删除",
                                    onClick = {
                                        LaoSpeech.deleteAudio(context, a.path)
                                        audios = LaoSpeech.getSavedAudioList(context)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = "‹ 返回", onClick = onBack)
            }
        }
    }
}

private fun formatAudioTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))
