# ScreenGuideQi · 屏幕指导

一个 APK，Android 10 起，两部手机分别选择共享端和指导端。家人主动授权共享屏幕，指导端查看实时画面并按需开启单向语音指导。没有远程操作、无障碍控制、静默采集或录像功能。

## 使用

交付包：`dist/ScreenGuideQi-0.5.1.apk`，非调试 Release 构建，两端安装相同文件。已预填免费的 WSS 信令入口和 TURN 中继凭据（由信令服务端按会话下发），无需使用者购买服务器、购买域名或接入商业音视频 SDK。

1. 共享端创建八位协助码，指导端输入并申请加入。
2. 共享端确认是自己认识的人，点击接受，再允许通知和本次系统录屏授权。
3. 共享端切换到需要帮助的页面。指导端查看画面，可点击全屏查看。
4. 指导端点击“开启语音指导”才申请麦克风权限并开始发送声音，共享端扬声器播放。
5. 应用内或通知栏随时停止。任一端退出、锁屏、系统撤销投屏或连接失败后结束，不后台自动重新共享。下一次共享需要重新配对和授权。

免费模式是**公网直连优先 + TURN 兜底**：信令走 Cloudflare 托管，屏幕和语音通过 WebRTC 在手机之间传输，直连失败时经 Metered 免费 TURN（每月 50 GB）中继。凭据由信令服务端按会话短期下发，长期密钥不进 APK。中继节点在海外，部分网络下延迟高于直连。先在同一 Wi-Fi 验证，再测试不同网络。

## 验证与限制

构建、单元测试、后端集成测试和双 Android 14 模拟器的实际媒体测试见 [验证记录](docs/verification.md)。语音测试使用 Debug 专用合成音，经真实 Android 录音生命周期和 WebRTC 编解码传到对端播放；交付 Release 不包含合成音替换或样本检测逻辑。

模拟器不能代替两部真机的物理麦克风、扬声器、厂商系统兼容性和跨运营商 NAT 验证。公网免费入口曾出现间歇断线。受保护页面可能黑屏。参见 [已知限制](docs/limitations.md)、[免费服务与 TURN](docs/free-services.md)。

## 开发

```powershell
# JDK 17、Android SDK；默认构建 Release，-Verify 同时跑单测和 Lint
.\scripts\build-android.ps1 -Verify

cd signaling
npm ci
npm run check
npm test
```

编译原始输出：`app/build/outputs/apk/release/app-release.apk`。当前自用 Release 沿用旧版签名以支持覆盖安装，不用于商店发布。详细配置、复测和部署见 [构建与部署](docs/setup.md)、[测试计划](docs/test-plan.md)、[模拟器实测](docs/emulator-test.md)。

Android：Kotlin / Compose / MediaProjection / 开源 `io.github.webrtc-sdk:android:150.7871.04`。信令：TypeScript / Workers / SQLite-backed Durable Objects / 标准 WSS，支持 WebSocket 休眠。APK 不包含 Cloudflare 账户密钥或 TURN 长期密钥。没有开通付费服务。
