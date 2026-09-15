# 女仆管理器

女仆管理器是一个以聊天为核心的原生 Android 客户端。模型服务、联网搜索和数据都由用户自己控制；应用不包含账号、广告、统计、遥测或云同步。

当前版本：3.0.8

## 当前功能

- OpenAI Compatible 多服务配置与模型管理
- 模型与 Provider 成对路由；请求失败且尚未输出内容时自动尝试备用模型
- 流式聊天、Markdown、多轮上下文、人设和本地聊天历史
- Brave Search、Tavily、SearXNG 与自定义搜索源并行查询、自动合并和去重
- 本地长期记忆、完整聊天归档和可编辑会话交接包
- 结构化错误说明、最近错误记录和隐私安全的 JSON 错误报告导出
- GitHub Release 更新检查、APK 哈希/包名/版本/签名校验和 Android 系统安装确认
- 深色、浅色、跟随系统和 Material You 动态颜色
- 拍照/选图发送、自定义对话名字与头像
- 通用后台任务：观察、执行、验证、再规划；普通文件整理/读写、搜索、系统操作与授权应用页面操作
- 持久化检查点、权限暂停恢复、按操作确认、可恢复文件移除及中断核对
- 可拖动悬浮头像、快速聊天、任务确认和结果提醒
- 统一的浅深色界面、独立陪伴头像、直接关闭与安静提醒模式
- 可选的文件/通知/任务完成/Wi-Fi/充电/时间/应用事件触发（先建议再执行）

TTS 保留原有实现。人物主动消息可投递到悬浮头像。RP V1 与图片生成 V1 已移至 `archive/`，不参与当前 APK 构建。

后台任务不是无限权限：仅访问指定共享目录与授权应用，原生 API 优先，必要时使用用户主动开启的无障碍。没有 Root、任意脚本、密码输入、永久删除或静默发送工具。界面自动化兼容性取决于目标应用；系统强停后需重新打开。边界、系统权限和测试清单见 [通用任务说明](docs/PHONE_AGENT.md)，旧任务恢复见 [V1 说明](docs/PHONE_TASKS.md)。

## 隐私

聊天、人设、长期记忆、服务配置和 API 密钥默认只保存在 Android 应用数据中。错误报告不会导出 API 密钥、Authorization、提示词、请求正文、回复正文或聊天内容，服务地址只保留主机名。

联网搜索返回内容会以“不可信外部资料”边界提供给模型，网页内容不能覆盖应用的系统规则。HTTP 仅用于用户明确配置的局域网或 Tailscale 私有服务；公网服务应使用 HTTPS。

## 构建

需要 JDK 17 和 Android SDK 34。仓库包含 Gradle Wrapper。

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/`。本地签名、Release 构建和 GitHub 自动发布说明见 [BUILDING.md](BUILDING.md)，模块边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 项目来源

本项目基于 [Minis233/miniichat](https://github.com/Minis233/miniichat) 修改，继续采用 MIT License 并保留原版权声明。依赖、上游项目和素材说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Launcher 图片由当前项目维护者提供，不自动包含在 MIT 源码许可中；公开分发时应确认相应版权和肖像授权。
