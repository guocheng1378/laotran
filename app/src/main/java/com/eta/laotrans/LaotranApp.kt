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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
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

    var selectedTab by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        // ====== 液态玻璃氛围背景 ======
        GlassBackground()

        // ====== 主内容 ======
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 110.dp)
        ) {
            // ====== 顶栏（玻璃） ======
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(context.getString(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(context.getString(R.string.app_subtitle), fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                }
                GlassIconButton(icon = MiuixIcons.Settings, onClick = { vm.showSettings = true })
            }
            Spacer(Modifier.height(16.dp))

            // ====== 输入卡（方向 + 输入 + 语音工具） ======
            GlassCard {
                // 方向模式：玻璃按钮，点击循环
                GlassButton(
                    text = vm.dirLabelTextForUi(context),
                    onClick = { vm.cycleDirMode(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    label = context.getString(R.string.hint_input),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        text = context.getString(R.string.voice_zh),
                        onClick = { startVoice("zh-CN", 1) },
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        text = context.getString(R.string.voice_lo),
                        onClick = { startVoice("lo-LA", 2) },
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        text = context.getString(R.string.clear_btn),
                        onClick = { vm.clearInput() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ====== 翻译主按钮（玻璃 primary） ======
            GlassButton(
                text = context.getString(R.string.btn_translate),
                onClick = { vm.translate(context, true) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                primary = true,
            )
            Spacer(Modifier.height(16.dp))

            // ====== 译文结果卡 ======
            GlassCard {
                SmallTitle(context.getString(R.string.result_label))
                Text(vm.result.ifEmpty { context.getString(R.string.result_empty) }, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
                Text(vm.status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (vm.result.isNotEmpty()) {
                    val clipboard = LocalClipboardManager.current
                    GlassButton(
                        text = "复制",
                        onClick = { clipboard.setText(AnnotatedString(vm.result)) },
                        modifier = Modifier.padding(top = 12.dp),
                        primary = true,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ====== 朗读 + 语速滑块卡 ======
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.btn_speak), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    GlassButton(text = "朗读", onClick = { vm.speak(context) })
                }
                Spacer(Modifier.height(16.dp))
                var speedLocal by remember { mutableStateOf(vm.speakSpeed) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.speed_label), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("${vm.speakSpeed}×", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = speedLocal,
                    onValueChange = { speedLocal = it },
                    onValueChangeFinished = { vm.setSpeed(context, speedLocal) },
                    valueRange = 0.75f..2.0f,
                    keyPoints = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
                    showKeyPoints = true,
                    magnetThreshold = 0.1f,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        // ====== 底部 Miuix 悬浮玻璃导航栏 ======
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            FloatingNavigationBar(color = Color(0xF2FFFFFF)) {
                FloatingNavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = MiuixIcons.Translate,
                    label = "翻译",
                )
                FloatingNavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { vm.showHistory = true; selectedTab = 0 },
                    icon = MiuixIcons.Refresh,
                    label = "历史",
                )
                FloatingNavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { vm.showSettings = true; selectedTab = 0 },
                    icon = MiuixIcons.Settings,
                    label = "设置",
                )
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

// ================= 液态玻璃基础组件 =================

@Composable
private fun GlassBackground() {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color(0xFF3D6BFF),
                0.5f to Color(0xFF7B5CFF),
                1f to Color(0xFF2BB8FF)
            )
        ))
        Box(
            Modifier
                .size(300.dp)
                .align(Alignment.TopStart)
                .offset(x = (-100).dp, y = (-60).dp)
                .background(Brush.radialGradient(listOf(Color(0x66FFFFFF), Color.Transparent)))
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.CenterEnd)
                .offset(x = (-60).dp, y = (-140).dp)
                .background(Brush.radialGradient(listOf(Color(0x55FFD166), Color.Transparent)))
        )
        Box(
            Modifier
                .size(340.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-140).dp, y = 80.dp)
                .background(Brush.radialGradient(listOf(Color(0x5590E8FF), Color.Transparent)))
        )
        Box(
            Modifier
                .size(180.dp)
                .align(Alignment.CenterStart)
                .background(Brush.radialGradient(listOf(Color(0x44FFFFFF), Color.Transparent)))
        )
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = shape
    Column(
        modifier = modifier
            .shadow(10.dp, s, spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(s)
            .background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.72f), s)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
private fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(15.dp)
    val bg = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.55f)
    val fg = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(shape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface)
    }
}
