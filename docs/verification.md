# 0.5.0 MVP 验证记录

日期：2026-11-12。交付 `dist/ScreenGuideQi-0.5.0.apk`，同一包用于共享端/指导端。

## 最终包

- 非调试 Release，versionCode 5，versionName 0.5.0-mvp，minSdk 29 / targetSdk 36，包含 arm64-v8a、armeabi-v7a、x86、x86_64。
- 文件 69,896,196 字节；SHA-256：`90B04A061CB9BCA0627DFFAD2DD07321478EE362E72AFC0AED75E693EC5171F4`。
- APK v2 签名、ZIP CRC、ZIP 16 KB 对齐、64 位原生库 ELF LOAD 16 KB 对齐均通过。签名沿用旧版自用安装身份，可覆盖升级；不是商店分发签名方案。
- Release 不可 run-as，未包含 Debug 合成音标记和播放样本检测代码；没有摄像头/无障碍服务/系统音频采集权限。

## 自动检查

- Debug、Release 编译打包通过；Android 10 项单元测试全部通过。
- Debug/Release Lint：0 错误、4 个原有警告（固定依赖更新及备份规则提示）。
- TypeScript 检查与 Worker 打包通过；9 项 workerd/SQLite-backed DO 集成测试通过。新增真实等待 35 秒的回归，确认慢速信令不会在原 30 秒期限误删已确认房间。
- 已发布 Worker 版本 `83792572-d59b-4fd9-a5a7-08a7553c06df`；Pages 入口绑定同一 DO，不需要重新部署网关。没有升级套餐或启用计费 TURN。

## Android 14 双模拟器

两个真实 API 34 x86_64 APK 实例、WHPX、720×1280，虚拟 Wi-Fi。通过 UI 操作系统授权，未绕过 MediaProjection。以下是本轮分项回归及最终包复测；媒体路径为 host/prflx 的虚拟局域网，不是跨运营商 NAT。

| 验证 | 结果 |
|---|---|
| 协助码、申请加入、共享端确认和本次系统录屏授权 | 通过 |
| 持续变化画面 | Debug 回归 62.3 秒，接收 184,424 → 920,783 字节、解码 13 → 123 帧、12 次场景变化 |
| 拒绝录屏、同进程重新配对授权、两端按钮停止 | 通过；投屏和前台服务消失 |
| 共享端横竖屏与投屏通知栏停止 | 通过 |
| 麦克风授权前和拒绝后 | 不录音、不启动麦克风服务，视频继续 |
| 拒绝麦克风后再通过系统弹窗允许 | 通过；语音正常启用 |
| Debug 合成音链路 | 实际 Android AudioRecord → Opus/WebRTC → 对端播放回调，440 Hz 相关度约 0.978；不保存 PCM，不使用主机真实麦克风 |
| 反复开启/关闭语音，应用与通知栏关闭 | 通过；实际录音停止，视频保留 |
| 静止画面下的全屏、指导端旋转及切回竖屏 | 已修复并以实际截图通过，保留内存中的单帧显示缓冲直到会话结束 |
| 指导端开启语音后锁屏、共享端锁屏 | 结束协助，投屏/录音/前台服务释放 |
| 任一端 force-stop | 对端结束、投屏清理 |
| 实际媒体网络中断 | 约 15.6 秒后结束采集；恢复网络没有自动恢复共享 |

## 最终 Release 使用默认公网 WSS

- 同一个非调试 Release 安装到两个模拟器，实际配对、系统授权、解码共享画面通过。
- 未注入合成音，使用模拟器正常的静音麦克风输入；共享端实际解码 297,120 个音频采样，证明正式音频路径传通，不能据此判断物理音质。
- 静止画面的全屏/旋转截图通过；关闭麦克风后视频继续。
- 视频继续 32.2 秒：接收字节 418,145 → 774,515、解码帧 44 → 102、6 次场景变化；指导端断开后 MediaProjection 与前台服务完全停止。
- 公网信令通过了本轮完整会话，但此前仍发生握手失败、消息延迟和断线。不能将一次成功写成公网稳定性或跨运营商穿透通过。
- 已将媒体协商期限调整为客户端/服务端 90 秒，WSS 首次响应 45 秒；立即发送首个探活，后续每 10 秒文本心跳、45 秒无 pong 后本地结束。已连接媒体中断仍有 10 秒清理倒计时。

## 尚待真实设备验证

两部真机、不同运营商 NAT、蜂窝网络、物理麦克风/扬声器音质、Android 10/其他厂商和版本、单应用共享及 16 KB 页设备运行均未代替测试。TURN 默认关闭；免费额度现值及账户套餐核验边界见 [免费服务](free-services.md)。

证据在 `build/emulator-test/`：`suite-result.json`、`permission-result.json`、`voice-result.json`、`extra-result.json`、`mvp-result.json`、`fullscreen-result.json`、`release-result.json`、`apk-validation.json` 和仅含模拟器系统/App 页面截图。应用不提供录像或音频保存。历史阶段记录见 [0.3 验证](verification-0.3.md)。

# 0.5.1 TURN 验证记录

日期：2026-11-12。新增可选 TURN 中继，交付 `dist/ScreenGuideQi-0.5.1.apk`。

## 服务与凭据

- 信令服务端新增三种 TURN 模式：Cloudflare Realtime（key + token）、托管静态凭据、自建 Coturn REST。当前线上启用**托管静态凭据**（Metered，免费额度每月 50 GB）。
- 凭据只存于 Worker/Pages secret，随 `session.accepted` 的 `ice` 字段按会话下发；APK 与仓库不含账号或密钥。`/health` 返回 `stage: 1, turnEnabled: true`。
- 中继地址 4 条：`turn:global.relay.metered.ca:80`、`:443`、`:80?transport=tcp`、`turns:global.relay.metered.ca:443?transport=tcp`。

## 自动检查

- 信令：TypeScript 检查通过；workerd/SQLite-backed DO 集成测试 14 项全部通过（新增 TURN 下发、静态透传、半配置回退、CF 模式降级共 5 项）。
- Android：Debug/Release 编译通过；单元测试 15 项全部通过（新增 `IceConfigurationTest` 5 项）；Release Lint 0 错误、5 个既有类别警告。
- 公网 WSS 端到端：两个测试客户端完成 `room.create → join → accept`，双方 `session.accepted` 均包含 4 条中继地址与 16 位凭据，凭据与 Worker secret 一致。

## 最终包

- 非调试 Release，versionCode 6，versionName 0.5.1-mvp，minSdk 29 / targetSdk 36，69,896,196 字节。
- SHA-256：`C81C35B6ABD2B2AF7C516CF312ADED89DDC954EC88B7BA22EAA6728407142C1B`。
- 签名沿用 0.5.0 的调试身份（可覆盖升级）；ZIP CRC 与 16 KB 对齐通过。

## 尚未验证

- 中继承载的实际画面质量、跨运营商 NAT 表现、真实手机麦克风/扬声器；海外节点对国内网络的延迟与丢包。模拟器/主机代理测试不能代替两部真机。
- Metered 免费额度超限后的行为以其服务商页面为准，本项目未开通付费。
