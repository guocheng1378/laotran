## v1.14（Edge TTS 音色/语速 + 首选引擎切换）

- 新增：语音合成「首选引擎」设置（自动 / 仅 Edge / 仅 MMS），可分别验证两个 TTS。
- 新增：Edge TTS 音色切换（女声 Keomany / 男声 Chanthavong）。
- 新增：语速调节映射到 Edge 合成速率（MMS 仅改变播放速度，因其接口仅接收文本）。
- 音频库文件：Edge 产出 .mp3，MMS 兜底产出 .wav，便于区分引擎。

# LaoTran 更新说明（v1.2 → v1.3）

## 本次新增 / 修复

### 1. 朗读语速调节
- UI：朗读按钮下方新增「语速」选项组，可选 0.75 / 1.0 / 1.25 / 1.5 / 2.0 倍速，默认 1.0。
- 记忆：用 `SharedPreferences`（`laotrans_prefs`）记住上次选中的语速，重开 App 自动恢复。
- 技术：`LaoSpeech.speak()` 增加 `speed` 参数，播放前用 `MediaPlayer.setPlaybackParams()` 调速；已包 `try-catch`，设备不支持变速时静默使用默认速度，不会崩溃。

### 2. 翻译附带拉丁转译（罗马音，阅读发音用）
- 事实：翻译成老挝语时，`TranslateEngine` 的 prompt 已要求模型输出两行：
  1. 纯老挝语译文
  2. `转写：<罗马音>`（拉丁字母标注发音）
- 修复：此前 `doSpeak()` 会把整段结果（含「转写：xxx」）交给 TTS 合成，导致朗读内容错误。现改为**只朗读第一行老挝语原文**，跳过转写行。
- 展示：翻译结果框显示「老挝语译文 + 转写：罗马音」两行。

## 涉及改动文件
- `app/src/main/res/layout/activity_main.xml`（新增语速选项组）
- `app/src/main/java/com/eta/laotrans/MainActivity.kt`（语速控制、朗读取原文、传速）
- `app/src/main/java/com/eta/laotrans/LaoSpeech.kt`（playback 变速）

## 构建（GitHub Actions）
push 主分支 → Actions 自动打包 → Artifacts 下载 `laotran-debug-apk`。
如未配置过，先在仓库 `Settings → Secrets → Actions` 添加 `DEEPSEEK_API_KEY`。

---

## 可复制的 git 提交命令
```bash
git add -A
git commit -m "feat: 朗读语速调节 + 翻译附带拉丁转译(罗马音)，修复朗读误读转写行"
git push origin main
```
