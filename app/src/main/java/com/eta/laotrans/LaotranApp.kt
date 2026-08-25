package com.eta.laotrans

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun LaotranApp() {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF3482FF)) }
    MiuixTheme(controller = controller) {
        LaotranScreen()
    }
}

@Composable
private fun LaotranScreen(vm: TranslationViewModel = viewModel()) {
    val context = LocalContext.current

    // 首次挂载：读取语速 + 初始化 TTS
    LaunchedEffect(Unit) { vm.init(context) }

    // ====== 语音识别 ======
    var pendingVoiceLang by remember { mutableStateOf("zh-CN") }
    var pendingVoiceDir by remember { mutableIntStateOf(1) }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            vm.onVoiceCancelled(context)
        } else {
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrEmpty()) vm.onVoiceUnheard(context)
            else vm.onVoiceRecognized(context, text)
        }
    }

    fun launchRecognition(listenLang: String) {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLang)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.prompt_speak))
            }
            voiceLauncher.launch(intent)
            vm.setListeningStatus(context)
        } catch (e: Exception) {
            vm.setSpeechUnavailableStatus(context)
            Toast.makeText(context, context.getString(R.string.status_no_speech), Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (vm.dirMode != pendingVoiceDir) vm.dirMode = pendingVoiceDir
            launchRecognition(pendingVoiceLang)
        } else {
            vm.setPermissionDeniedStatus(context)
        }
    }

    fun startVoice(listenLang: String, forcedDir: Int) {
        if (vm.dirMode != forcedDir) vm.dirMode = forcedDir
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceLang = listenLang
            pendingVoiceDir = forcedDir
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchRecognition(listenLang)
    }

    // ====== 防抖自动翻译 ======
    LaunchedEffect(vm.input) {
        if (vm.input.isBlank()) {
            vm.clearInput()
            return@LaunchedEffect
        }
        delay(600)
        vm.translate(context, false)
    }

    Scaffold {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // ====== 顶栏 ======
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(context.getString(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(context.getString(R.string.app_subtitle), fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                            TextButton(text = "🕘", onClick = { vm.showHistory = true })
                        }
                        Box(Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                            TextButton(text = "⚙", onClick = { vm.showSettings = true })
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))

                // ====== 方向模式（主容器色分段块） ======
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.primaryContainer)) {
                    TextButton(
                        text = vm.dirLabelTextForUi(context),
                        onClick = { vm.cycleDirMode(context) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))

                // ====== 输入区 ======
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    label = context.getString(R.string.hint_input),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                // ====== 工具行：清空 + 语音 ======
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(text = context.getString(R.string.clear_btn), onClick = { vm.clearInput() })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = context.getString(R.string.voice_zh), onClick = { startVoice("zh-CN", 1) })
                        TextButton(text = context.getString(R.string.voice_lo), onClick = { startVoice("lo-LA", 2) })
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ====== 翻译 + 互换 ======
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = context.getString(R.string.btn_translate),
                        onClick = { vm.translate(context, true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = context.getString(R.string.btn_swap),
                        onClick = { vm.toggleDirection(context) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // ====== 译文结果区 ======
                Spacer(Modifier.height(12.dp))
                SmallTitle(context.getString(R.string.result_label))
                Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(vm.result.ifEmpty { context.getString(R.string.result_empty) }, fontSize = 16.sp)
                        Text(vm.status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant, modifier = Modifier.padding(top = 8.dp))
                        if (vm.result.isNotEmpty()) {
                            val clipboard = LocalClipboardManager.current
                            TextButton(
                                text = "复制",
                                onClick = { clipboard.setText(AnnotatedString(vm.result)) },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ====== 朗读 ======
                TextButton(
                    text = context.getString(R.string.btn_speak),
                    onClick = { vm.speak(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )

                // ====== 朗读语速 ======
                Spacer(Modifier.height(6.dp))
                SmallTitle(context.getString(R.string.speed_label))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    speeds.forEach { s ->
                        TextButton(
                            text = speedText(s),
                            onClick = { vm.setSpeed(context, s) },
                            modifier = Modifier.weight(1f),
                            colors = if (vm.speakSpeed == s) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        )
                    }
                }
            }
        }
    }

    SettingsDialogContent(
        show = vm.showSettings,
        onDismiss = { vm.showSettings = false },
        onSaved = { vm.showSettings = false },
    )
    HistoryScreenContent(
        show = vm.showHistory,
        onBack = { vm.showHistory = false },
    )
}

private fun speedText(v: Float): String = when (v) {
    0.75f -> "0.75"
    1.0f -> "1.0"
    1.25f -> "1.25"
    1.5f -> "1.5"
    2.0f -> "2.0"
    else -> v.toString()
}
