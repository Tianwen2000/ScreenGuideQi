# MVP 实现结构

信令路径：两部 APK → WSS Pages 网关 → Worker AssistHub（SQLite-backed Durable Object）。媒体路径：共享端 MediaProjection → WebRTC 视频 → 指导端；指导端主动授权麦克风 → WebRTC 音频 → 共享端扬声器。信令不经过视频/音频。

## Android

- `MainActivity.kt`：角色入口、系统权限弹窗、录屏授权、全屏/比例适配、返回与锁屏行为；授权带会话世代标识，拒绝旧会话的迟到结果。
- `GuideViewModel.kt`：配对与媒体状态机、generation 回调隔离、超时、两端退出和清理协调。
- `signaling/SignalingClient.kt`：标准 OkHttp WSS、协议 JSON、连接超时、文本 ping/pong 和控制帧心跳；Release 只接受 WSS。
- `capture/ProjectionService.kt`：系统授权后启动 mediaProjection 前台服务，再由 ScreenCapturerAndroid 领取本次授权；投屏撤销、旋转尺寸与任务退出处理。
- `capture/CaptureSession.kt`、`CaptureStore.kt`：采集资源归属、先释放 PeerConnection 再释放视频工厂，幂等停止。
- `rtc/WebRtcEngine.kt`：共享端发 offer、指导端 answer、提前到达的 ICE 队列、音视频轨道、会话状态 DataChannel、实际媒体统计和关闭。
- `rtc/RemoteVideoStore.kt`：远端渲染器初始化/绑定/释放及实际帧比例；仅在内存保留一帧独立 I420 显示缓冲，用于静止画面的全屏/旋转重绘，结束后释放，不保存到文件。
- `rtc/VideoStats.kt`、`StunProbe.kt`：去地址化媒体计数与无采集的 STUN 检测。
- `rtc/IceConfiguration.kt`：默认 STUN；保留注入接口，未实现 TURN 凭据获取或自动中继。
- `voice/VoiceService.kt`、`VoiceStore.kt`：用户授权后的 microphone 前台服务，实际录音停止回调和通知栏关闭。
- `voice/SpeakerRoute.kt`：接收端扬声器与音频焦点，结束时恢复音频模式。
- `src/debug/.../AudioProbe.kt`：仅在专用模拟器显式设置测试标记后替换合成测试音、计量播放输出，不保存音频；`src/release` 同名实现直接返回构建器，没有这些功能。

首次只协商视频。麦克风权限与前台服务均成功后才创建音轨，通过已认证且加密的 WebRTC DataChannel 请求共享端增加接收音频；该通道交换语音增量 SDP，初始 SDP/ICE 仍走 WSS。这样授权前既没有录音轨道，也没有发送音频的协商，避免 WebRTC 提前初始化 AudioRecord。重复开关已协商的麦克风不重新申请录屏授权。

## 信令与状态

`room.create → waiting → room.join → pending → join.accept → accepted → 本次系统授权 → 录屏前台服务和采集 → capture.ready → negotiating → SDP/ICE → 实际解码 → media.ready → active → ended`。

共享端拒绝则指导端结束、房间回到原有效期内等待；协助码确认后删除。共享端管理 token 与协助码独立，服务器保存摘要。确认前与采集就绪前均不转发媒体协商。任一端退出、投屏撤销、超时或断线结束当前会话，不恢复旧 token 或自动重启采集。

`signaling/src/AssistHub.ts` 管理房间、附件身份、角色/状态/消息校验、限流与 alarm；`protocol.ts` 定义 v1 信封与随机码；`handler.ts` 校验 Upgrade/路由；`pages-gateway` 是相同 DO 的入口绑定。音频开关通过加密 WebRTC DataChannel 通知共享端，不增加云端音频数据。

TURN（可选，默认关闭）：`turn.ts` 在配置了 `TURN_URIS` 与 Worker secret `TURN_SECRET` 后，按 Coturn REST API 为每次接受的会话生成短期凭据，随 `session.accepted` 的 `ice` 字段下发；未配置时消息不变。客户端 `IceConfiguration.fromSignaling` 解析该字段并注入 `PeerConnection`，解析失败或字段缺失时回退默认 STUN 直连；长期密钥不进 APK。

资源停止顺序：停止实际录音、请求停止投屏、解绑渲染器/数据通道、关闭 PeerConnection，随后释放视频/音频轨道与工厂/采集器，关闭 WSS 并清除会话凭据；投屏 Service 与 CaptureSession 协调先释放其 PeerConnection。停止是幂等的，网络发送失败不阻止本地释放。
