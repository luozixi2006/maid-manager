# 同一个角色：手机、手表与独立记忆

## 当前方案与信任边界

按最新选择，手表一直陪同手机：使用系统已配对蓝牙的加密 RFCOMM。手机保存权威会话、人设、记忆并请求模型，手表是活动感知和回复端。无需手表浏览器、Tailscale、Windows Gateway 或公网端口，不修改 F:\tts。

- app：原手机应用；watch：独立 Android APK；companion-core：低功耗事件判定、蓝牙帧和增量复制。
- 手机选择原有聊天与已配对手表，明确确认同步范围；手表输入 8 位一次性配对码（10 分钟、最多 5 次尝试）。随后验证配对 MAC 和设备 token，手机可暂停/撤销。
- 手表不接收模型 API Key、系统提示或整个记忆库。只同步选定会话、公开名字头像、摘要、事件和角色状态。不获得手机工具权限。
- 手机与手表使用同一 conversation_id，消息 UUID；persona_id:conversation_id 隔离通道，不创建第二个人格。

## 增量与离线

手机原 ConversationStore 是聊天权威。SQLite 镜像日志只服务同步；outbox 先落盘，确认后移除，同一 op_id 重试幂等。30 条一页，保留最新 revision/sequence 和永久删除墓碑，旧游标仍能追上。Live Context 不累计每次重复采样的历史版本。

手表离线消息/事件保留本地，恢复蓝牙后补传。手机按固定 reply-消息ID / event-事件ID 持久化推理任务，回复使用固定 ID 防止重复。失败退避、可重试；切换会话不清空手表待发送记录。手机镜像会重新读取原会话，不能用陈旧快照删除新同步消息。

前台约 5 秒同步，手表感知服务约 30 秒同步，另有持久化恢复任务。没有网络或蓝牙就不宣称实时。**此方案需要手机联网调用模型，不支持手表离开手机后独立模型聊天**，这是无需手表登录 Tailscale 的取舍。

## 感知、事件与主动联系

用户开启后优先计步器、significant motion、离腕状态。加速度约每 5 分钟采 10 秒，心率约每 15 分钟采 10 秒；不存连续原始加速度/PPG/ECG。处理断采、计数器重置、跨日、离腕和过期值。

已见设备清单：OWW211 / Android 11 可见标准 accelerometer、significant motion、step detector/counter、heart rate、off-body。传感器可见不等于实际连续读数可用，权限与息屏限制必须实机测。

Live Context 有时间戳和有效期。Event Memory 保存活动开始、步行/跑步推测、停止活动、久未活动、可能入睡/醒来等。静止不叫坐着，关屏不等于睡觉，计步不证明出门。无标准睡眠/训练接口时不伪造结论，不读 OPPO 私有数据库，不作医疗诊断。

手机可选提供屏幕状态、媒体、耳机和网络；当前应用只在页面陪伴授权、悬浮陪伴运行且应用白名单允许时提供。开启手表不自动开启无障碍或截图。

事件触发模型结合人设、同一历史、有效 Live Context、近期事件和相关长期记忆决定 SEND/SKIP。程序先执行授权、勿扰、去重、防打扰检查。人设自然问候仍保留，不限于工作提醒；上一条未回复只触发有时限冷却，不再永久停发。人设设置显示最近未发送原因。

Presence 为 idle / observing / awake / talking / sleeping，表示角色，不用于判断用户健康。名字头像跟随同一人设，工作悬浮头像可单独自定义/恢复。

## 人设独立长期记忆

原数据库增量迁移，保留旧行。未指定人设的旧记忆不自动注入任何角色，在设置中手动归入。普通聊天不显示记忆列表。

每条记忆包含内容、personaId、类型、重要度、置信度、创建/确认/使用时间、证据来源、稳定性、embedding/model、版本和状态。相处记忆记录共同经历、梗与约定。

成功聊天 → 持久化抽取任务 → 所选模型提出 ADD/UPDATE/MERGE/CONFLICT → 本地校验来源、归属和版本 → 整批原子提交 → 本地向量化。

- 短偏好不再因少于 8 字丢弃；不把每句聊天或助手猜测直接当事实。
- 来源 ID 和原文证据必须在本批真实资料中，模型不能跨人设写入；版本校验避免覆盖人工编辑。
- 进展更新替换旧事实，冲突暂不注入上下文，可通过后来聊天澄清或设置内更正。
- 定期有界合并、保留来源，轮转处理而非永远只取最新一页。
- Raw Event → 短期事件 → 多日模式。至少 3 个不同日期的同类时段行为才可提出习惯；不长期记单日步数/单次心率，模式置信度上限 0.7。
- 先隔离 personaId，再按语义、关键词、重要度、置信度、时间检索，限制条数和字符预算，不整库塞回模型。
- 手机本地 BAAI BGE small Chinese、512 维、最大 256 tokens、ONNX INT8；官方 cased tokenizer 对照测试。加载失败明确降级关键词，不冒充语义检索。
- 抽取仍请求用户选定的模型服务，向量化不另行上传。网络失败队列可重试，退出页面不丢；关闭相关开关停止自动处理。
- 聊天归档保存人设归属和记忆元数据；交接包只含该人设有效记忆。

## 后台与验收边界

手机使用用户启动的可见连接前台服务，手表使用用户启动的健康感知前台服务。系统强停、关闭蓝牙/通知、省电策略仍可中断；再次打开可恢复队列，缺失事件不补造。系统权限不绕过。

自动测试与模拟 UI 不等于实机验收。仍须在手机+手表测试：配对撤销、离线补传、双方聊天、息屏、耗电、通知、真实传感器、Android ONNX 运行。当前 ADB 没有连接设备，不能声称这些项目已通过。

官方依据：

- https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://huggingface.co/BAAI/bge-small-zh-v1.5
- https://onnxruntime.ai/docs/get-started/with-java.html
