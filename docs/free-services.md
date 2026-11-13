# 免费信令与 TURN 评估

## 本版实际配置

- Android 只使用开源 WebRTC 制品、OkHttp 和系统 MediaProjection；没有商业音视频 SDK。
- 信令是已发布的 Cloudflare Workers + SQLite-backed Durable Objects，Pages Functions 提供 WSS 入口；无需购买域名。
- 信令转发少量 SDP/ICE 与会话消息，不接收视频或声音。公网媒体优先走 ICE 直连，直连失败时经 Metered 托管 TURN 中继。
- TURN 已于 2026-11-12 启用：Metered 托管服务，免费额度每月 50 GB，凭据由信令服务端按会话下发，APK 不含任何 TURN 账号或长期密钥。
- 没有调用付费升级接口，未开通按流量计费服务。账户当前套餐需以账户控制台为准；当前登录权限不能查询账单订阅。

## 免费额度与运行机制

官方核验入口：[Workers limits](https://developers.cloudflare.com/workers/platform/limits/)、[Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)、[Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)、[WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)。

Workers/Pages 请求、DO 请求与运行时长、SQLite 行读写和存储分别计量。Free 超额可能拒绝请求/操作；不能用“有免费额度”承诺永久可用。具体当前数值、是否要求付款验证、账户是否保持 Free 必须在上述官方文档及账户控制台复核。本轮最终检查访问 Cloudflare 文档遇到 HTTP 403，官方源码镜像读取超时，未将记忆中的数字写成已核实额度。

Hibernation API 接受 WebSocket，连接附件保存角色和连接身份，SQLite 保存房间和凭据摘要；不依赖 Worker 全局变量保活。客户端每 10 秒发文本 ping，DO 的自动 pong 不需要唤醒业务代码。SDP/ICE 等业务消息仍计入请求/执行，存储写入、alarm 清理也消耗额度。普通单元/本地集成通过不证明云端确实发生休眠。

5 分钟待配对过期、1 分钟加入确认、2 分钟等待采集、90 秒媒体协商和 1 小时会话上限均有服务端期限与 alarm 清理。客户端另有响应、媒体、断线和心跳超时。部署或平台故障仍可能使已有会话中断。

本机和模拟器访问默认免费域名既有成功也有间歇断线；国内手机网络需要用两机的 WSS 配对、实际帧解码及持续变化分别验收。不要求用户改路由、VPN、端口映射或公网 IP。

## TURN 候选与决定

1. [Cloudflare standalone TURN](https://developers.cloudflare.com/realtime/turn/)：前期官方页面核验为出站 $0.05/GB；免费与 Realtime SFU 的特定组合不能当作本项目独立 TURN 免费。默认不接入。
2. [Metered Open Relay](https://www.metered.ca/tools/openrelay/)：2026-11-12 重新读取服务商页面，标注每月 20 GB 免费，要求注册账号取得 API_KEY。是否绑卡、超额是否硬停、是否自动收费和有效消费上限尚未核实；没有创建账户或自动启用。

阶段 4 的评估结论：保留 `IceConfiguration` 注入接口，当前免费约束下保持 TURN 关闭。只有以后能确认费用限制并获得相应授权，才实现托管服务端持有长期密钥、签发短期 TURN 凭据给 APK；本版没有该凭据接口，也不声称已经完成 TURN 中继。

## 自托管 Coturn（阶段 1，已实现，默认关闭）

2026-11-12 实现了可选的自托管 Coturn 接入，默认仍为阶段 0（TURN 关闭）：

- 服务端在 Worker secret 中保存 `TURN_SECRET`（长期密钥），`TURN_URIS` 配置中继地址；两者都配置且格式有效时 `/health` 返回 `stage: 1, turnEnabled: true`。
- 每次配对接受时，服务端按 Coturn REST API 生成短期凭据（username 为过期时间戳，credential 为 base64 HMAC-SHA1），随 `session.accepted` 的 `ice` 字段下发两端；凭据有效期 2 小时，覆盖 1 小时会话上限。
- APK 不包含任何长期密钥；凭据按会话即时下发，会话结束即失效。旧版 APK 会忽略 `ice` 字段，行为不变。
- `TURN_URIS` 或 `TURN_SECRET` 缺失或格式无效时自动回退阶段 0，不影响配对；`/health` 的 `stage` 字段可用于确认配置是否生效。
- Coturn 侧要求 `use-auth-secret` 且 `static-auth-secret` 与 `TURN_SECRET` 一致；`realm` 只存在于 Coturn 配置，客户端从 TURN nonce 交换中获取。中继流量计量属于自托管服务器成本，与 Cloudflare 免费额度无关。

直连失败时：先在同一 Wi-Fi 对照，检查实际选中候选、发送/接收字节和解码帧，再尝试换网重新配对。无法连接属于免费无中继方案的已知边界，不会自动转入付费服务。
