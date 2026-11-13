# 构建与部署

## Android

需要 JDK 17、Android SDK Platform 36、Build Tools 35.0.0、Platform Tools。Gradle Wrapper 固定为 8.13，发行包通过 SHA-256 校验。Android Studio 也可以直接打开根目录。

1. 配置 `JAVA_HOME` 指向 JDK 17；不要使用 Java 25 运行此 Gradle 版本。
2. 创建不提交的 `local.properties`，例如 `sdk.dir=C\:/Users/yourname/AppData/Local/Android/Sdk`（Windows 盘符冒号需要转义）。
3. 在项目根目录运行 `./scripts/build-android.ps1 -Verify`。默认构建非调试 Release；开发用可传 `-Variant Debug`。脚本也能使用用户缓存 `.cache/screenguideqi/toolchains/jdk` 内的项目专用 JDK。
4. 安装交付文件 `dist/ScreenGuideQi-0.5.0.apk` 到两部手机，或使用原始输出 `app/build/outputs/apk/release/app-release.apk`。

也可设置好 JDK 后直接运行 `./gradlew.bat :app:assembleRelease --console=plain`。

`local.properties`、工具链、Gradle 缓存和签名文件不进入版本控制。交付包为非调试 Release，但为了覆盖升级旧版，沿用 Android 调试签名；仅限自用测试，不用于商店发布。

## 信令本地检查

```powershell
cd signaling
npm ci
npm run check
npm test
npm run dev
```

`npm test` 会先打包，然后在 Miniflare/workerd 中运行真实 WebSocket 与 SQLite-backed DO 集成测试，不需要 Cloudflare 登录，不会发布云端资源。

默认开发入口是 `http://127.0.0.1:8787/health` 和 `ws://127.0.0.1:8787/signal`。Android 模拟器对应宿主地址 `10.0.2.2`。

若要在可信家庭局域网用两部真机测试，可运行 `npm run dev -- --ip 0.0.0.0`，仅放行系统防火墙的专用网络入站端口 8787。两机填写 `ws://电脑局域网IP:8787/signal`。调试 APK 仅允许本机或私有 IPv4 地址使用明文 WS；发布版本只接受 WSS。不要在公网暴露明文开发入口。

## 发布到免费托管平台

以下步骤需要账户所有者完成登录。没有登录时无法取得属于你的公网地址；项目不包含共享账号或第三方长期密钥。

1. 注册 Cloudflare 账户，在控制台确认 Workers Free。不要升级 Paid、不要启用 TURN，不需要购买域名。
2. 在 `signaling` 目录运行 `npx wrangler login`，在浏览器授权。
3. 运行 `npx wrangler whoami` 确认账户身份，不把输出中的账户信息发布到公开日志。
4. 如需避免 Worker 名称冲突，可修改 `wrangler.jsonc` 的 `name`。
5. 运行 `npm run deploy`。SQLite-backed DO 会由 `new_sqlite_classes` migration 创建。
6. 检查部署输出的 HTTPS 地址加 `/health`，应返回 `stage: 0` 和 `turnEnabled: false`。
7. 将地址的 `https://` 改为 `wss://` 并加 `/signal`，填写到两台手机。

默认域名形如 `https://screenguideqi-signaling.你的子域.workers.dev`。手机只保存这个服务地址，不保存协助码、管理凭据或 SDP/ICE。

如果控制台要求开通付费套餐，先停止，不接受默认升级。账户注册验证、国内网络可达性和两机实测需要分别确认。

### 可选：接入 TURN 中继（阶段 1，支持自建与托管两种模式）

直连失败的蜂窝网络环境可以接入 TURN 中继。Worker 只下发凭据，不转发媒体。

**模式 A：自托管 Coturn**（服务器本身及中继流量是自有成本）。Coturn 必须按 REST 短期凭据模式配置（`turnserver.conf` 关键项）：

```conf
listening-port=3478
listening-ip=<服务器内网IP>
relay-ip=<服务器内网IP>
external-ip=<公网IP>/<内网IP>
realm=<你的域>
server-name=<与 realm 相同>
use-auth-secret
static-auth-secret=<随机长密钥>
fingerprint
min-port=49160
max-port=49200
no-cli
```

云服务器防火墙/安全组必须放行 UDP 3478 和整个 `min-port`~`max-port` 中继端口段。`external-ip` 缺失是最常见故障：中继候选会携带内网地址导致对端不可达。可用 `turnutils_uclient -u <用户名> -w <凭据> -v <服务器IP>` 验证后再接 App。

Cloudflare 侧配置（在 `signaling` 目录）：

```powershell
npx wrangler secret put TURN_SECRET   # 与 Coturn static-auth-secret 完全一致
npx wrangler secret put TURN_URIS    # 例如 turn:你的服务器:3478?transport=udp,turns:你的服务器:443?transport=tcp
npm run deploy
```

配置生效后 `/health` 返回 `stage: 1, turnEnabled: true`；缺一或格式无效则自动回退 `stage: 0`，不影响配对。旧版 APK 会忽略凭据字段，升级版 APK 在配对接受后自动获得 TURN 候选，UDP 被限时/限速的蜂窝网络仍可通过 `turns:tcp` 兜底。

**模式 B：托管 TURN 服务**（如 Metered Open Relay 等免费额度）。服务商提供固定 URI、username 和 credential，无需自建服务器：

```powershell
npx wrangler secret put TURN_URIS         # 服务商提供的 turn:/turns: 地址
npx wrangler secret put TURN_USERNAME     # 服务商提供的固定用户名
npx wrangler secret put TURN_CREDENTIAL   # 服务商提供的固定凭据
npm run deploy
```

静态凭据会按会话透传给两端，仍只保存在 Worker secret 中，不进 APK。注意托管免费额度（如每月 20 GB）超限后中继不可用，是否绑卡、超额是否硬停须以服务商页面为准。两种模式互斥：`TURN_USERNAME`/`TURN_CREDENTIAL` 成对出现时优先于 `TURN_SECRET`。

## 两机操作

1. 两机使用相同信令地址。当前 APK 已预填部署好的 Pages 网关地址，可直接使用。
2. 共享端点击“创建协助码”。
3. 指导端输入八位码，点击“申请加入”。
4. 共享端接受或拒绝。接受后两端显示配对已确认，协助码失效。
5. 共享端点击“开始共享屏幕”，允许通知并在系统弹窗中授权本次录屏；两分钟未启动采集会超时结束。授权弹窗打开时如果对端已断开，返回后不再启动采集。
6. 指导端应出现“对方屏幕”。共享端切换到系统设置等普通页面，确认指导端看到持续变化的画面，并检查每三秒更新的发送/接收字节和编码/解码帧。
7. 先用同一 Wi-Fi 验证，再让其中一台改用移动网络，重新配对和授权。记录连接状态、候选类型、计数是否增长及实际画面；不必分享完整协助码、IP 或 SDP。
8. 指导端可点“全屏查看”，按返回先退出全屏。开启语音时确认共享端能听到，然后验证关闭麦克风后视频继续。
9. 分别测试共享端停止、指导端断开、系统停止投屏、锁屏及断网；结束后必须停止采集并移除前台通知。下次重新共享需重新授权。

指导端点击“开启语音指导”后，才请求麦克风和通知权限；共享端不请求麦克风。允许后，指导端 microphone 前台服务显示常驻通知，共享端通过扬声器播放指导声音。关闭按钮或语音通知栏动作只关闭麦克风，屏幕仍可继续；断开会话、锁屏或退出则结束全部采集。没有摄像头或系统音频采集。收到视频轨道并不证明媒体已经到达；解码计数大于零也仍需人工确认显示和画面持续变化。

路径中的 `host`、`srflx`、`prflx`、`relay` 分别表示本机候选、STUN 映射候选、对端反射候选、中继候选。类型组合不能单独证明公网成功；同一局域网及 IPv6 公网直连均可能使用 host 候选。本版本 TURN 默认关闭，正常不会使用 relay；接入自托管 Coturn 后 `relay` 候选才会出现。

本机曾出现 JDK 17 C2 编译线程崩溃。若遇到相同 JVM 崩溃，可临时降低 JIT 层级并限制构建并发，避免修改系统 Java 配置：

```powershell
$env:JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m'
```

## 免费 Pages 网关

本机访问 Worker 的默认域名出现 DNS/TLS 问题，因此额外部署了 Pages Functions 入口。它直接绑定同账户内 `screenguideqi-signaling` 的 `AssistHub`，不转发媒体，不引入商业 SDK 或新服务商。

- 健康检查：`https://screenguideqi-2f916560.pages.dev/health`
- App 信令：`wss://screenguideqi-2f916560.pages.dev/signal`
- 网关配置：`signaling/pages-gateway/wrangler.jsonc`

先部署 Worker，再在 `signaling` 目录运行 `npm run deploy:gateway`。如果在自己的新账户部署，先创建对应 Pages 项目，并修改配置和命令中的项目名。Pages Functions 同样受 Workers 免费调用额度限制。

`npm run smoke:public` 检查真实 HTTPS/WSS 配对。只在排查 DNS 时，可给该测试进程设置 `SGQI_SMOKE_IP` 为经可信解析确认的服务 IP；脚本仍验证域名证书。这不修改系统 DNS，也不代表目标手机已通过测试。不要把服务 IP 硬编码进 APK。
