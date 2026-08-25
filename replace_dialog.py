import re
p = '/workspace/laotran/app/src/main/java/com/eta/laotrans/LaotranApp.kt'
s = open(p, encoding='utf-8').read()

old_imp = "import top.yukonga.miuix.kmp.overlay.OverlayDialog"
assert s.count(old_imp) == 1, s.count(old_imp)
s = s.replace(old_imp, "import androidx.compose.ui.window.Dialog", 1)

marker = "private fun SettingsDialogContent(show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {"
idx = s.find(marker)
assert idx > 0

tail_new = '''private fun SettingsDialogContent(show: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(Config.baseUrl(context)) }
    var apiKey by remember { mutableStateOf(Config.apiKey(context)) }
    var model by remember { mutableStateOf(Config.model(context)) }
    var locale by remember { mutableStateOf(Config.locale(context)) }
    if (show) {
        Dialog(onDismissRequest = onDismiss) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(context.getString(R.string.settings_title), fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
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
            }
        }
    }
}

@Composable
private fun HistoryScreenContent(show: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(HistoryStore.list(context)) }
    if (show) {
        Dialog(onDismissRequest = onBack) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(context.getString(R.string.history_title), fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
                    if (records.isEmpty()) {
                        Text(context.getString(R.string.history_nothing), modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
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
'''
s = s[:idx] + tail_new
open(p, 'w', encoding='utf-8').write(s)
print("tail rewritten")
