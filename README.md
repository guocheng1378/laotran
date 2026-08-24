# 老挝语翻译（LaoTran）

基于 **Kotlin + 原生** 的 **老挝语 ↔ 中文互译** Android 应用。

## 功能
- 🈲 **双向翻译**：中文 ⇄ 老挝语（使用 **DeepSeek 大模型 API** 翻译）
- 🔊 **老挝语发音**：翻译结果自动合成语音（Meta MMS 老挝语 TTS，在线合成）
- 🚀 **GitHub Actions 自动打包 APK**

## 技术栈
- Kotlin + AndroidX + Material3
- OkHttp（网络）
- **DeepSeek 大模型**（翻译，OpenAI 兼容 Chat Completions）
- Meta MMS 在线 TTS（老挝语发音）

## ⚠️ 使用前必须配置 DeepSeek API Key

1. 到 [DeepSeek 开放平台](https://platform.deepseek.com/) 注册并创建一个 API Key（`sk-...`）。
2. **本地构建**：在项目根目录的 `local.properties` 里添加一行：
   ```
   DEEPSEEK_API_KEY=sk-你的key
   ```
   （`local.properties` 已在 .gitignore 中，不会公开。）

3. **GitHub Actions 构建**：在仓库的 `Settings → Secrets and variables → Actions` 里添加一个名为
   `DEEPSEEK_API_KEY` 的 secret，值填入你的 key。构建时 workflow 会自动把它写进 `local.properties`。

> 🔒 **切勿**把真实 key 硬编码到源代码里，否则会泄露到公开仓库。

## 🔨 构建 APK（GitHub Actions，全自动）
1. 把代码推送到 GitHub 的主分支，Actions 会自动编译。
2. 打开仓库 **Actions** 标签页，运行 **Build APK**。
3. 完成后在 **Artifacts** 中下载 `laotran-debug-apk`。
4. 安装到手机即可。

## 📁 项目结构
```
├── app/src/main/java/com/eta/laotrans/
│   ├── MainActivity.kt          # 主界面 + 逻辑
│   ├── TranslateEngine.kt       # DeepSeek 翻译引擎
│   └── LaoSpeech.kt             # 老挝语 TTS 发音
├── app/src/main/res/            # 布局 / 主题 / 图标
├── .github/workflows/build.yml  # 自动打包（注入 API Key）
└── build.gradle.kts             # 构建配置
```
