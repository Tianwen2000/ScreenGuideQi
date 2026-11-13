# 双 Android 模拟器复测

当前 0.5.0 MVP 结果与最终 APK 指纹见 [验证记录](verification.md)。使用两个专用 API 34 AVD：emulator-5554 共享端、emulator-5556 指导端，720×1280，WHPX / swiftshader。两个 AVD 通过 wifi-server-port / wifi-client-port 30001 连接虚拟 Wi-Fi，在系统设置连接 AndroidWifi。

所有 UI 脚本操作相同模拟器，必须逐个完成，不能并发。Python 3 与 Pillow 用于检查实际截图；脚本拒绝操作真实手机。

## Debug 本机信令与合成语音

1. 安装 Debug APK。信令目录运行 `npm run dev -- --persist-to .wrangler/独立测试目录`，避免连续自动回归触发生产限流；没有放宽线上限制。
2. `python scripts/emulator-voice-test.py prepare` 显式设置只属于模拟器 Debug 的合成音/播放计数标记。模拟器启动不要使用 `-audio none`；不启用主机真实麦克风。
3. `python scripts/emulator-smoke.py run --signal ws://127.0.0.1:8787/signal`。脚本仅在测试设备设置 adb reverse。
4. 当前屏幕会话上运行 `python scripts/emulator-voice-test.py run` 或 `python scripts/emulator-fullscreen-test.py`。后者核对静止画面全屏/横屏/切回竖屏、语音与结束清理。
5. `suite` 从当前成功视频会话测试持续画面、拒绝授权和重复会话；`extra` 测试共享端旋转与投屏通知栏停止；`emulator-mvp-test.py` 从当前成功会话测试锁屏、进程终止、媒体断网与不自动恢复。

首次麦克风权限回归：在专用模拟器撤销 RECORD_AUDIO、清除其测试拒绝历史，再配对传屏后运行 `python scripts/emulator-permission-test.py`；它通过真实弹窗拒绝/允许，不直接授予权限。

## 最终 Release

安装 `app/build/outputs/apk/release/app-release.apk`。可在专用模拟器执行 pm clear 模拟全新安装，清除旧测试标记。

```powershell
python scripts/emulator-smoke.py run --signal wss://screenguideqi-2f916560.pages.dev/signal
# 上一步确实收到并解码屏幕后再运行
python scripts/emulator-release-test.py
```

Release 检查不可 run-as、正常音频路径确有对端解码采样、静止画面和旋转实际截图、关闭麦克风后视频继续及退出释放。输入为模拟器静音麦克风；不注入合成音，不代表物理音质验证。

公网 WSS 历史上存在失败。失败结果以非零退出码报告，不把健康检查、配对或接收轨道当作成功传图；WebSocket 信令走真实云端，媒体依旧走虚拟 Wi-Fi，不能据此声明跨运营商 NAT 通过。
