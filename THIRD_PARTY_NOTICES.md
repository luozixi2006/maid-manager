# 第三方归属

本文件记录当前源码中可识别的上游项目、直接依赖和素材来源。依赖的完整许可证文本仍以各项目发布包及其官方仓库为准。

## 上游项目

### MiniiChat

- 项目：https://github.com/Minis233/miniichat
- 作者：Minis233
- 许可证：MIT
- 版权：Copyright (c) 2026 Minis233
- 使用情况：本项目基于 MiniiChat 的 Kotlin、Jetpack Compose、数据存储、网络请求和聊天界面代码继续开发。

上游 README 还注明其功能范围参考了 `rikkahub/rikkahub`（Apache-2.0）与 `Chevey339/kelivo`（AGPL-3.0），并明确说明没有复制这两个项目的源码。当前本地代码审计未发现这两个项目的版权头或直接复制声明。

## 直接依赖

| 组件 | 当前版本 | 许可证 |
| --- | --- | --- |
| Android Gradle Plugin | 8.5.2 | Apache-2.0 |
| Kotlin Android / Compose / Serialization 插件 | 2.0.20 | Apache-2.0 |
| AndroidX Core、Activity、Lifecycle、Navigation、DataStore | 见 `app/build.gradle.kts` | Apache-2.0 |
| Jetpack Compose、Material 3、Material Icons | Compose BOM 2024.09.02 | Apache-2.0 |
| Ktor Client | 2.3.12 | Apache-2.0 |
| kotlinx.serialization | 1.7.3 | Apache-2.0 |
| kotlinx.coroutines | 1.8.1 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Gradle Wrapper | 随仓库提供 | Apache-2.0 |
| PdfBox-Android | 2.0.27.0 | Apache-2.0 |

Ktor 的 Android HTTP 实现会传递使用 OkHttp；OkHttp 采用 Apache-2.0。

## 图标与素材

### 本地记忆检索

- BAAI / FlagEmbedding `bge-small-zh-v1.5`，MIT，固定版本 `7999e1d3359715c523056ef9478215996d62a620`。
- 模型来源：https://huggingface.co/BAAI/bge-small-zh-v1.5 。官方权重在本地转换为 ONNX MatMul INT8，不使用第三方未知权重。
- ONNX Runtime Android 1.20.0，MIT，Copyright (c) Microsoft Corporation。
- 完整许可证随 APK 位于 `assets/memory/THIRD_PARTY_LICENSES.txt`；转换脚本为 `tools/export_memory_model.py`，哈希与验证数据在同目录 `provenance.json`。
- 只有手机 APK 携带约 57 MB 模型。向量化本地进行，不发送给额外的向量服务；抽取事实及生成回复仍使用用户选定的模型服务。

Launcher 图片由当前项目维护者提供。除非权利人另行授权，该图片不因放入本仓库而自动适用 MIT License。公开发布或允许再分发前，应由项目维护者确认其版权和肖像使用权限。
