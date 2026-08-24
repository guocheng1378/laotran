# 老挝语翻译 · LaoTrans

> 一款用**大模型**实现 **中文 ⇄ 老挝语 互译** 的 Android 应用，带语音输入、发音和翻译历史。

用大模型做老挝语-中文翻译，并解决老挝语的**发音**（MMS 在线合成）和**不认识老挝文字的人**的朗读问题（罗马音转写 / 汉语拼音）。适合去老挝旅游、做生意、学习老挝语或做中老跨境交流的人。

## ✨ 功能

- **中文 ⇄ 老挝语双向翻译**：基于大模型（OpenAI 兼容接口，默认使用 b.ai）
- **自动识别方向**：输入含老挝字母 → 译成中文；否则 → 译成老挝语
- **流式翻译 + LRU 缓存**：边生成边显示；相同句子直接命中秒回
- 🔊 **老挝语发音**：翻译结果自动用 **Meta MMS** 在线合成老挝语音
- 🔉 中文译文自动附**汉语拼音**，并可朗读
- 🎤 **语音输入**：说中文 / 老挝语自动转文字（系统语音识别）
- 🌐 **界面语言切换**：设置里可切换 中文 / 老挝文 界面
- 📖 **翻译历史**：本地保存，每条可重读、可删除、一键清空
- ⏱️ **朗读语速**：0.75×–2.0× 分段调节
- 🚀 **GitHub Actions 自动打包** APK，也支持本地构建

## 🧱 技术栈

- Kotlin + AndroidX + Material Design（液态玻璃风格 UI）
- OkHttp（网络）+ kotlinx-coroutines（协程）
- OpenAI 兼容 `chat/completions`（翻译）+ `/models`（拉取模型列表）
- Meta **MMS** 老挝语 TTS（在线合成发音）
- Android 系统语音识别（`RecognizerIntent` / `RecognitionService`）
- GitHub Actions（CI 打包）

## 🔑 免费获取 API Key

翻译由大模型驱动，需要一个 OpenAI 兼容的 API Key。

推荐用 **b.ai**（本项目默认接口地址 `https://api.b.ai/v1`），通过下面的邀请链接注册即可获得**免费额度**：

> 👉 **https://chat.b.ai/chat?invite_code=C7SA2S**

<p align="center">
  扫码注册 b.ai，领取免费 API Key 👇
  <br/>
  <img src="docs/laotran-qr.png" alt="b.ai 免费 API 注册二维码" width="240"/>
</p>

打开链接注册后，在 b.ai 控制台创建 API Key，然后把 Key 填进 App 的**设置界面**即可。API Key / 接口地址 / 模型名都在 App 内设置，**无需改代码、无需重新打包**。

## 📱 安装前要准备什么

### 方式一：直接安装 APK（最省事，推荐）
- 下载 APK：从 **Releases** 页签取 `app-debug.apk`，或用 GitHub Actions 的 **Artifacts**。
- 手机要求：**Android 7.0（API 24）及以上**。
- 首次安装：手机提示“未知来源应用”时，前往设置打开“允许安装未知应用”即可；或用数据线 `adb install app-debug.apk`。

### 方式二：本地源码构建（开发者）
需要安装：
1. **JDK 17**（[Adoptium Temurin](https://adoptium.net/)，或 Android Studio 自带）
2. **Android Studio**（推荐，自带 Android SDK、Gradle）或单独安装 **Android SDK（compileSdk 34）**
3. Gradle **8.7**（项目自带 wrapper，`./gradlew` 会自动下载）

构建命令：

```bash
./gradlew assembleDebug
```

生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

> 说明：`build.gradle.kts` 会从根目录 `local.properties` 读取 `API_KEY` 注入 `BuildConfig`（本地构建可选）。实际上不填也没关系，因为 **App 内设置界面填 Key 才是主方式**。

### 方式三：GitHub Actions 自动打包（免本地环境）
推送到 `main` 分支自动构建，在仓库 **Actions → Build APK → Artifacts** 下载。若在仓库 `Settings → Secrets and variables → Actions` 配置了名为 `API_KEY` 的 Secret，构建时会自动写入 `local.properties`。

## ⚙️ 使用步骤（第一次）

1. 打开 App，点右上角 **⚙️** 打开设置。
2. 填三项必填：
   - **接口地址**：默认已是 `https://api.b.ai/v1`（b.ai），一般不用改
   - **API Key**：粘贴从 [b.ai](https://chat.b.ai/chat?invite_code=C7SA2S) 拿到的 Key
   - **模型**：点「**拉取模型**」自动获取可用模型列表并选择；或手动填模型名
3. 点「**保存**」。
4. 在上方输入框输入文字，点「**翻译**」。
5. 结果自动显示；点「🔊 朗读」听发音；顶部胶囊可切换「自动 / 中→老 / 老→中」；🕘 查看翻译历史。
6. 切换到老挝文界面：进 **⚙️ 设置 → 界面语言** 选「老挝文」并保存，主界面/设置/历史会整体变为老挝文。

### 语音输入

App 上方有两个语音按钮：
- 🎤**中**：说中文 → 识别为中文 → 翻译成老挝语
- 🎤**老**：说老挝语 → 识别为老挝语 → 翻译成中文

默认使用**手机自带的 Google 语音识别框**（系统 `RecognizerIntent`），中文、老挝语都能识别，**无需额外申请 API Key**。前提是：

- 手机装有 **Google 语音服务**（多数含谷歌服务的安卓设备自带；未安装会提示“没有可用的语音识别应用”，到应用商店安装 Google 语音服务或恢复谷歌服务框架即可）。
- 系统存在可用的语音识别引擎（部分精简系统需手动开启）。

界面语言支持 **中文 ⇄ 老挝文 切换**：打开 **⚙️ 设置 → 界面语言**，选择中文或老挝文后点「保存」，界面即切换显示。

## 📁 项目结构

```
app/src/main/java/com/eta/laotrans/
├── MainActivity.kt          主界面 + 交互逻辑
├── TranslateEngine.kt       大模型翻译引擎（流式 + LRU 缓存）
├── LaoSpeech.kt             老挝语 MMS 在线发音
├── LocaleUtils.kt            界面语言（中文 / 老挝文）
├── Config.kt                运行时配置（接口地址 / API Key / 模型 / 界面语言）
├── SettingsDialog.kt        设置弹窗（可拉取模型）
├── HistoryActivity.kt       翻译历史界面
└── HistoryStore.kt          翻译历史本地存储
res/                         布局 / 主题 / 图标
.github/workflows/build.yml  自动打包（可选注入 API Key）
```

## ❓ 常见问题

- **提示“请先填写 API Key”**：去设置里填 b.ai 的 Key（上面的免费地址）。
- **翻译很慢**：首次调用大模型 + 在线 TTS 都走网络；相同句子命中缓存后是秒回。
- **老挝语没有声音**：需要联网（MMS 在线合成），并确认网络可访问 b.ai。
- **拉取模型失败**：先核对接口地址和 Key 是否正确，再点「拉取模型」。

## 📄 许可证

[MIT](./LICENSE)
