# 当前架构与模块边界

女仆管理器目前是单模块原生 Android 项目，技术栈为 Kotlin、Jetpack Compose、Material 3、DataStore、Ktor 和 kotlinx.serialization。

## 主要边界

- `api/`：OpenAI Compatible 文本请求、流式响应和错误分类。
- `data/`：Provider、设置、人设和聊天记录的本地持久化。
- `memory/`：长期记忆数据与注入。
- `rp/`：RP 数据结构、模型适配、世界生成、剧情推进和持久化。
- `tts/`：语音合成协议、HTTP 实现、WAV 缓存和 Android 播放。
- `ui/`：Compose 页面与导航状态。
- `chatdata/`：完整会话归档、无覆盖合并，以及分段生成的结构化 AI 交接包。
- `proactive/`：基于 WorkManager 的一次性随机调度、模型 SEND/SKIP 决策、全局限频和通知。

## 本地数据与升级

聊天、人设、设置和 RP 数据使用 DataStore，长期记忆使用 SQLite。会话解码失败时会回退到上一份本地 JSON 备份，而不是静默显示为空。完整归档导入采用合并策略：ID 冲突时创建副本，不覆盖设备上的现有聊天；归档和交接包不包含 API 密钥或 Authorization 信息。

Android 升级能否原地保留数据取决于 applicationId 与签名连续性。Debug 包名为 `com.maidmanager.debug`，Release 包名为 `com.maidmanager`，它们是两个独立的数据目录，不能互相覆盖安装或自动共享数据。

## 语音

`TtsProvider` 是文本到音频的协议边界；`CustomHttpTtsProvider` 负责远程合成；`TtsManager` 负责 Android 缓存和 MediaPlayer 播放。后续语音聊天应复用这条输出链路。

当前没有语音输入、麦克风权限、ASR、实时会话、打断控制或回声消除。本阶段不提供这些功能，也不选择相应服务或模型。

## iOS 可复用性

Provider 数据结构、模型配置、RP 模型、Prompt 规则和大部分 JSON 协议与 UI 无关，未来可作为跨平台设计参考。Compose 页面、Android ViewModel、Context/DataStore、MediaPlayer、文件选择和权限处理是 Android 专有实现，需要由 iOS 客户端单独处理。

当前不预设 Flutter、React Native、Compose Multiplatform、KMP 或 Swift 路线。
