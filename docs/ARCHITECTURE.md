# 当前架构与模块边界

项目保持单模块原生 Android 结构，技术栈为 Kotlin、Jetpack Compose、Material 3、DataStore、Ktor 和 kotlinx.serialization。此次整理只建立必要边界，没有进行 Clean Architecture 或多模块重构。

## 聊天主线

- `api/LlmClient`：OpenAI Compatible HTTP、流式 SSE/JSON 解析与协议错误。
- `api/OpenAiEndpointResolver`：统一处理根地址、`/v1` 和完整接口地址，避免重复拼接。
- `api/ModelRouter`：把 Provider 与模型作为不可拆分的路由，按用户配置产生候选顺序。
- `api/ChatGateway`：逐路由执行请求；只有在没有输出任何正文或推理内容时才允许回退，避免混合两次回答。
- `ChatViewModel`：协调会话持久化、搜索上下文、模型请求和 UI 状态，不再负责各协议的底层实现。

## 联网搜索

- `search/`：Brave Search、Tavily、SearXNG 和自定义兼容接口适配器。
- `data/SearchSourceStore`：本地保存各搜索源的启用状态和配置，并迁移旧版单搜索源设置。
- `SearchManager`：并行请求全部已启用且配置完整的来源；单源失败不取消其他来源；结果交错、去重后再提供给模型。
- 网页结果会包在明确的不可信资料边界内，不能把网页指令当作系统指令执行。

## 错误与诊断

- `error/AppErrorClassifier`：区分配置、鉴权、限流、服务端、DNS、连接、TLS、超时、格式、空响应和存储错误。
- `error/AppErrorStore`：本地保存最近 100 条结构化错误。
- `ui/AppErrorDialog`：每次可见错误同时显示原因和处理建议。
- `ui/ErrorCenterScreen`：查看、删除和导出 JSON 报告。
- 错误记录与导出都禁止包含密钥、Authorization、请求/回复正文和聊天内容。

## 其他活动模块

- `data/`：Provider、设置、人设和聊天记录的本地持久化。
- `memory/`：长期记忆数据与注入。
- `tts/`：既有文本转语音、WAV 缓存与 Android 播放；本轮不扩展。
- `chatdata/`：完整会话归档、无覆盖合并和结构化会话交接包。
- `proactive/`：既有人物主动消息调度；本轮不扩展。
- `update/`：读取公开 GitHub Release，校验清单、SHA-256、applicationId、versionCode 与签名，再交给 Android 系统确认安装。

## 已隔离功能

`archive/rp-v1/` 和 `archive/image-generation-v1/` 是从活动源码提取出的快照，不位于 Android SourceSet 中，不参与编译。旧版 RP DataStore、历史消息里的图片兼容字段和旧图片查看能力不会被主动删除，防止升级后破坏已有本地数据。

## 本地数据与升级

聊天、人设、设置和错误历史使用 DataStore，长期记忆使用 SQLite。会话解码失败时回退到上一份本地 JSON 备份，而不是静默显示为空。归档导入采用合并策略，ID 冲突时创建副本。

Debug 与 Release 现在都使用 `com.maidmanager.debug`，用于保持 2.x 安装路径。但 Android 原地升级还必须保持签名一致：如果旧 APK 的私钥不可用，需要先导出聊天、卸载旧版、安装使用固定 3.x 签名的首个 Release，再导入数据。此后 GitHub Release 必须始终使用同一私钥签名。
