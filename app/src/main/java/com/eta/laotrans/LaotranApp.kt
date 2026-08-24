package com.eta.laotrans

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
private fun LaotranScreen() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var dirMode by remember { mutableIntStateOf(0) }
    var speakSpeed by remember { mutableStateOf(loadSpeakSpeed(context)) }
    var lastTranslated by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 系统 TTS（中文朗读）
    var ttsReady by remember { mutableStateOf(false) }
    var systemTts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val tts = TextToSpeech(context) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                @Suppress("DEPRECATION")
                tts.language = Locale.CHINA
            }
        }
        systemTts = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var pendingVoiceLang by remember { mutableStateOf("zh-CN") }
    var pendingVoiceDir by remember { mutableIntStateOf(1) }

    fun label(code: String): String =
        if (code == "zh") context.getString(R.string.label_zh) else context.getString(R.string.label_lo)

    fun dirLabelText(): String = when (dirMode) {
        1 -> context.getString(R.string.dir_zh_lo)
        2 -> context.getString(R.string.dir_lo_zh)
        else -> context.getString(R.string.auto_recognize)
    }

    fun effectiveDirection(text: String): Pair<String, String> = when (dirMode) {
        1 -> "zh" to "lo"
        2 -> "lo" to "zh"
        else -> TranslateEngine.autoDetect(text)
    }

    suspend fun speakWithSystemTts(text: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val tts = systemTts
            if (!ttsReady || tts == null) return@withContext false
            tts.setSpeechRate(speakSpeed)
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "laotrans_tts_${System.currentTimeMillis()}")
            r == TextToSpeech.SUCCESS
        }

    fun doSpeak() {
        val full = result.trim()
        if (full.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_speak_first), Toast.LENGTH_SHORT).show()
            return
        }
        val body = full.substringBefore("转写：").substringBefore("拼音：").trim()
        if (body.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_speak_first), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            if (TranslateEngine.containsLao(body)) {
                status = context.getString(R.string.status_synth_lao)
                val ok = LaoSpeech.speak(body, context, speakSpeed)
                status = if (ok) context.getString(R.string.status_speak_ok) else context.getString(R.string.status_speak_fail)
            } else {
                status = context.getString(R.string.status_synth_voice)
                val ok = speakWithSystemTts(body)
                status = if (ok) context.getString(R.string.status_speak_ok) else context.getString(R.string.status_no_tts_zh)
            }
        }
    }

    fun doTranslate(manual: Boolean) {
        val text = input.trim()
        if (text.isEmpty()) {
            if (manual) Toast.makeText(context, context.getString(R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (!Config.isConfigured(context)) {
            status = context.getString(R.string.hint_need_config)
            if (manual) showSettings = true
            return
        }
        if (text == lastTranslated && result.isNotEmpty() && !manual) return
        val (src, tgt) = effectiveDirection(text)
        status = context.getString(R.string.status_translating)
        scope.launch {
            try {
                val full = StringBuilder()
                val res = TranslateEngine.translateStream(context, text, src, tgt) { delta ->
                    full.append(delta)
                    mainHandler.post { result = full.toString() }
                }
                mainHandler.post { result = res }
                lastTranslated = text
                result = res
                HistoryStore.add(context, text, res, context.getString(R.string.dir_progress, label(src), label(tgt)))
                if (tgt == "lo") {
                    status = context.getString(R.string.status_done_speaking)
                    doSpeak()
                } else {
                    status = context.getString(R.string.status_done)
                }
            } catch (e: Exception) {
                if (!manual && text != input.trim()) return@launch
                status = context.getString(R.string.status_failed_with, e.message ?: "")
            }
        }
    }

    fun cycleDirMode() {
        dirMode = (dirMode + 1) % 3
        val text = input.trim()
        if (text.isNotEmpty()) doTranslate(false)
    }

    fun toggleDirection() {
        dirMode = when (dirMode) { 0 -> 1; 1 -> 2; else -> 1 }
        val text = input.trim()
        if (text.isNotEmpty()) doTranslate(false)
    }

    fun clearInput() {
        input = ""
        result = ""
        status = ""
        lastTranslated = ""
    }

    fun setSpeed(v: Float) {
        speakSpeed = v
        context.getSharedPreferences("laotrans_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putFloat("speak_speed", v).apply()
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            status = context.getString(R.string.status_cancelled)
        } else {
            val text = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrEmpty()) {
                status = context.getString(R.string.status_unheard)
            } else {
                input = text
                lastTranslated = ""
                doTranslate(false)
            }
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
            status = context.getString(R.string.status_listening)
        } catch (e: Exception) {
            status = context.getString(R.string.status_no_speech)
            Toast.makeText(context, context.getString(R.string.status_no_speech), Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val lang = pendingVoiceLang
            val dir = pendingVoiceDir
            if (dirMode != dir) dirMode = dir
            launchRecognition(lang)
        } else {
            status = context.getString(R.string.status_perm_denied)
        }
    }

    fun startVoice(listenLang: String, forcedDir: Int) {
        if (dirMode != forcedDir) dirMode = forcedDir
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceLang = listenLang
            pendingVoiceDir = forcedDir
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchRecognition(listenLang)
    }

    // 防抖自动翻译
    LaunchedEffect(input) {
        if (input.isBlank()) {
            result = ""
            status = ""
            lastTranslated = ""
            return@LaunchedEffect
        }
        delay(600)
        doTranslate(false)
    }

    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(context.getString(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(context.getString(R.string.app_subtitle), fontSize = 12.sp)
                }
                Row {
                    TextButton(text = "🕘", onClick = { showHistory = true })
                    TextButton(text = "⚙︎", onClick = { showSettings = true })
                }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(
                text = dirLabelText(),
                onClick = { cycleDirMode() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                label = context.getString(R.string.hint_input),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = context.getString(R.string.clear_btn), onClick = { clearInput() })
                TextButton(text = context.getString(R.string.voice_zh), onClick = { startVoice("zh-CN", 1) })
                TextButton(text = context.getString(R.string.voice_lo), onClick = { startVoice("lo-LA", 2) })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = context.getString(R.string.btn_translate),
                    onClick = { doTranslate(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = context.getString(R.string.btn_swap),
                    onClick = { toggleDirection() },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            SmallTitle(context.getString(R.string.speed_label))
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(result.ifEmpty { context.getString(R.string.history_nothing) }, fontSize = 16.sp)
                    Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                text = context.getString(R.string.btn_speak),
                onClick = { doSpeak() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                speeds.forEach { s ->
                    TextButton(
                        text = speedText(s),
                        onClick = { setSpeed(s) },
                        modifier = Modifier.weight(1f),
                        colors = if (speakSpeed == s) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialogContent(
            onDismiss = { showSettings = false },
            onSaved = { showSettings = false },
        )
    }
    if (showHistory) {
        HistoryScreenContent(onBack = { showHistory = false })
    }
}

private fun speedText(v: Float): String = when (v) {
    0.75f -> "0.75"
    1.0f -> "1.0"
    1.25f -> "1.25"
    1.5f -> "1.5"
    2.0f -> "2.0"
    else -> v.toString()
}

private fun loadSpeakSpeed(context: android.content.Context): Float =
    context.getSharedPreferences("laotrans_prefs", android.content.Context.MODE_PRIVATE).getFloat("speak_speed", 1.0f)

@Composable
private fun SettingsDialogContent(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(Config.baseUrl(context)) }
    var apiKey by remember { mutableStateOf(Config.apiKey(context)) }
    var model by remember { mutableStateOf(Config.model(context)) }
    var locale by remember { mutableStateOf(Config.locale(context)) }
    top.yukonga.miuix.kmp.overlay.WindowDialog(
        show = true,
        title = context.getString(R.string.settings_title),
        onDismissRequest = onDismiss,
        onDismissFinished = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = context.getString(R.string.settings_base_url), useLabelAsPlaceholder = true)
                TextField(value = apiKey, onValueChange = { apiKey = it }, label = context.getString(R.string.settings_api_key), useLabelAsPlaceholder = true)
                TextField(value = model, onValueChange = { model = it }, label = context.getString(R.string.settings_model), useLabelAsPlaceholder = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = context.getString(R.string.settings_cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    TextButton(text = context.getString(R.string.settings_save), onClick = {
                        Config.save(context, baseUrl, apiKey, model, locale)
                        onSaved()
                    }, colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        },
    )
}

@Composable
private fun HistoryScreenContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(HistoryStore.list(context)) }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = "‹ 返回", onClick = onBack)
                Text(context.getString(R.string.history_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(text = context.getString(R.string.history_clear), onClick = {
                    HistoryStore.clear(context)
                    records = HistoryStore.list(context)
                })
            }
            if (records.isEmpty()) {
                Text(
                    context.getString(R.string.history_nothing),
                    modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(records) { r ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.direction, fontSize = 11.sp)
                                Text(r.srcText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                Text(r.dstText, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(text = context.getString(R.string.btn_play), onClick = {
                                        scope.launch { LaoSpeech.speak(r.dstText, context) }
                                    })
                                    TextButton(text = context.getString(R.string.btn_delete), onClick = {
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
}
