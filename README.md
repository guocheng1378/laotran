# 老挝语翻译（LaoTran）

基于 **Kotlin + WebView/原生** 的 **老挝语 ↔ 中文互译** Android 应用。

## 功能
- 🈲 **双向翻译**：中文 ⇄ 老挝语
- 🔊 **老挝语发音**：翻译结果自动合成语音（Meta MMS 老挝语 TTS，在线合成）
- 🚀 **GitHub Actions 自动打包 APK**

## 技术栈
- Kotlin + AndroidX + Material3
- OkHttp（网络）
- Google Cloud Translation v3（翻译，支持 lo 老挝语）
- Meta MMS 在线 TTS（老挝语发音）

## ⚠️ 使用前必须配置
翻译功能需要 **Google Cloud Translation v3** 的 API Key：

1. 打开 [Google Cloud Console](https://console.cloud.google.com)，创建项目并启用 **Cloud Translation API**。
2. 创建一个 **API Key**。
3. 打开 `app/src/main/java/com/eta/laotrans/TranslateEngine.kt`，替换：
   ```
   GOOGLE_PROJECT_ID = "你的项目ID"
   GOOGLE_API_KEY    = "你的API Key"
   ```

> 🔒 **不要**把真实的 API Key 直接提交到公开仓库！建议用环境变量或本地 `local.properties`（已加入 .gitignore）。

## 🔨 构建 APK（GitHub Actions，全自动）
把项目推送到 GitHub 后，GitHub Actions 会自动编译出 APK：
1. 在 GitHub 上创建新仓库，推送本目录所有文件。
2. 打开仓库的 **Actions** 标签页，运行 **Build APK** workflow。
3. 完成后在 **Artifacts** 中下载 `laotran-debug-apk`。
4. 把 APK 安装到手机即可。

## 📁 项目结构
```
├── app/src/main/java/com/eta/laotrans/
│   ├── MainActivity.kt          # 主界面 + 逻辑
│   ├── TranslateEngine.kt       # 翻译引擎
│   └── LaoSpeech.kt             # 老挝语 TTS 发音
├── app/src/main/res/            # 布局 / 主题 / 图标
├── .github/workflows/build.yml  # 自动打包
└── build.gradle.kts             # 构建配置
```
