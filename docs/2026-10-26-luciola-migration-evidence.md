# luciola 迁移收尾证据（2026-10-26）

本文件只记录**可复核的事实**与**明确边界**，不重复设计叙述。设计见
`core/src/jvmSharedMain/kotlin/com/kapp/tidings/core/transport/LUCIOLA_WIRING.md`。

## 1. 桌面安装包内置原生库（已完成）

- 构建：`./gradlew :desktopApp:createDistributable`（BUILD SUCCESSFUL）
- 包内文件：`Tidings.app/Contents/app/resources/native/libluciola_uniffi.dylib`
- 真实进程（打包产物、无环境变量）：
  ```
  $ Tidings.app/Contents/MacOS/Tidings --headless
  relay: headless mode; ui not started. ctrl-c to stop.
  [luciola] host configured port=17878 fp=sha256:b552f
  ```
  无 `UnsatisfiedLinkError`，且 `luciola/` 数据目录生成 `ed25519-seed.hex` 与 `state.json`。
- 打包前校验：`copyLuciolaNative` 复制后检查库确实存在（缺失即打包失败）。

## 2. Android 宿主（已实现并打包，运行时未验）

- luciola 仓 `0.4.4`（本地发布）：AAR `AndroidManifest.xml` = `minSdkVersion 24`，
  内含 `jni/arm64-v8a/libluciola_uniffi.so`（2,978,224 B）。
- 捎话侧：`core/androidMain` 依赖 `luciola-android`；传输/发现/配对代码下沉
  `jvmSharedMain`（Android 与桌面同一份实现）；`kotlin.io.encoding.Base64`
  替代 `java.util.Base64`（minSdk 24 < API 26）。
- `AndroidLuciolaHost`：`filesDir/luciola` 落 seed/state；`nativeLibraryDir`
  交给 `jna.library.path`；`AndroidAppPlatform.luciolaHost` 接入。
- 验证：`:composeApp:compileAndroidMain` 通过；`:androidApp:assembleDebug` 通过，
  APK 内含 `lib/arm64-v8a/libluciola_uniffi.so` 与 `libjnidispatch.so`。
- **真机运行时（Pixel 6 / Android 15 / arm64-v8a）已验**（2026-10-26）：
  - 安装 debug 变体（`com.kapp.tidings.debug`，新增 `applicationIdSuffix`，不动真机上
    正式签名的用户数据），`run-as` 写入 `"experimentalLuciola": true` 后重启：
  - 原生库加载成功（无 `UnsatisfiedLinkError`；logcat 出现 `luciola-uniffi-` 线程）；
  - `files/luciola/ed25519-seed.hex`（64 hex）与 `state.json`（含 `trust`/`pairSecret`）
    已生成；**强停重启后 seed 哈希不变**（身份持久化）；
  - 加密信道监听落地：从 Mac `nc -z 192.168.2.22 17878` → `succeeded`（TCP 17878 已开；
    Android 上 Rust 的 `TcpListener::bind` 正常）；
  - luciola 发现在真机网络上工作：节点页显示 `1 个节点 · 1 在线`（`demo-mac-97`
    192.168.2.82:17878）。
- **两台设备之间的完整配对：2026-10-26 已用「二维码图片导入」验证通过**（无需相机）：
  1. 桌面 headless 宿主（`TIDINGS_DATA_DIR=/tmp/luciola-e2e-host`，实验开关开）；
  2. 用它的 settings + seed（→Ed25519 公钥）+ pairSecret 生成**真实二维码 PNG**，
     经 `adb push` 到手机、在 App 里「从图片导入二维码」；
  3. 手机解析 → `pairPeer`（pin+secret 落盘，可在手机 state.json 里看到）→
     `requestPairing` 发 TLPAIR1 → 主机三关校验通过：
     `[luciola] paired node=4e76052f-... fp=sha256:87a5ecd...`，
     主机 TrustStore 出现该 nodeId/full；
  4. 手机在聊天里发 "QR-import-pairing-works" → 手机气泡显示 **✓ 已送达**，
     主机 `relay.db` 的 `MessageEntity` 落库：
     `conv/peer/sender=4e76052f-... | text=QR-import-pairing-works | ts=1792981154471`。
- 这次真机验证抓到并修掉两个真实缺陷：
  a) 扫码后**只发一次**配对帧，握手未完成时帧被丢弃 ⇒ 主机永远不信任；
     修法：信道把帧存到 "connected" 再补发（附回归测试）；
  b) 配对帧里的 nodeId 传成了**对端 id**，主机把自己的 id 记进信任库；
     修法：帧内固定用本机 nodeId（`profile.nodeId`）。

## 3. 配对链路（协议层与宿主包装层）

- core 集成测试 `LuciolaSecureChannelTest`（真实引擎 + TCP 回环）：双向收发、
  未配对拒绝、未受信入站拒绝/超时、扫码发起配对（TLPAIR1）→ 主机放行 → 业务帧。
- 桌面包装层 `DesktopLuciolaHostTest`：两个真实宿主走完
  出码 → `pairPeer` → `requestPairing` → 主机信任 → 业务帧到达。
- **未完成**：两台物理设备之间的真机配对（无第二台设备、无相机）。
  这是本机只能以“打包进程 + 宿主包装 e2e”替代的部分。

## 4. 回归

`:core:jvmTest :app:jvmTest :composeApp:jvmTest :desktopApp:test` 全绿。

## 4.1 发现替换的归因实验（2026-10-26 晚，真机 + 主机控制面）

在本机把「发现」与「传输」分开测，结论如下：

- **发现（luciola）可用**：主机 `/v1/peers` 能看到手机；心跳修复后 `online=true`。
- **心跳是真问题**：luciola 只推 found/updated/lost，而 registry 的在线判定是
  "lastSeen 超时即离线" ⇒ 一个在线对端会在一个 sweep 窗口后显示离线（实测
  `online=false`，消息直接进队列）。已加 5s 心跳（`heartbeatMs`）+ 回归测试
  `heartbeatKeepsOnlinePeerFromSweepingOffline`。
- **端口身份不一致**：发现上报的手机端口是 `52213`（logcat 无 bind_failed），而它的
  加密信道监听在 `17878`（从 Mac `nc` 验证）。按 registry 里的地址拨号会拨错端口；
  需要一个显式的「信道端口」（例如加进 Profile 可选字段），不能继续拿发现端口兼用。
- **数据面仍在走 legacy 长连接**（关键结论）：主机通过控制面 `POST /v1/send` 发消息，
  返回 `outcome=sent-via-link`、`link=true`，日志 `[send] peer=d1e2136f channel=LINK`；
  手机端确实收到（UI 可见）。也就是说当前是「**发现换成了 luciola，传输仍优先走
  legacy K1 长连接**」，传输替换没有完成。
- 引擎错误面已接上（`bind_failed` 等不再静默），发送通道归因日志
  `[send] channel=LINK|TCP|TRANSPORT` 已加。

## 4.2 数据面切到加密信道（2026-10-26 晚，真机验证通过）

在 4.1 之后补了三处，真机重跑同一归因命令：

1. **档案里加 `luciolaPort`（可选键）**：发现引擎上报的 `udpPort` 可能是临时端口
   （实测 40332/52213），对端拿它拨号必错。运行时 `selfProfile` 把加密信道的实际
   监听端口写进档案；发现引擎用 `profile.luciolaPort ?: peer.udpPort` 登记 registry。
   实测：registry 从 `40332` 变为 `17878`。
2. **反向拨号的 pin 查找**：主机通过入站配对只拿到 nodeId+公钥，没有端点映射。
   新增 `PeerRegistry.findByEndpoint`（同一端点有多条历史记录时挑**在线且最近见过**
   的那条 —— 实测踩过：先挑到旧 nodeId，握手对不上，症状是 no-ack 后退回 TCP）。
   工厂 `pinFor` 走「配对表 → registry 端点 → TrustStore」。
3. **Messenger.preferTransport**：luciola 模式下消息不再先走 legacy K1（仍保留失败回退）。

真机最终结果（主机控制面 POST /v1/send）：

```
{"lastSend":"outcome=sent-via-udp elapsed=100ms endpoints=[192.168.2.22:17878 acked 100ms] link=true"}
[host log] [luciola] paired node=0f39bd5f-... fp=sha256:3754378087d
[host log] [send] peer=0f39bd5f channel=TRANSPORT
```

即：**发现走 luciola、消息走加密信道、100ms 内 ACK**。legacy TCP/TLS 仅作为回退保留
（文件传输/投屏尚未迁移，仍依赖它们）。回归测试覆盖：`luciolaPort` 编解码、
registry 用档案端口、dial-back 反查、`preferTransport` 跳过链路。

## 5. 已知边界 / 后续

1. `luciola-android 0.4.4` 只在 mavenLocal，**Maven Central 未发布**（0.4.3 为 404）；
   发版前走 `scripts/publish-maven-local.sh` 的 Central 分支并复核。
2. Android 入站仅 IPv4（Rust 侧 `0.0.0.0`），IPv6 入站需 luciola 侧补。
3. 二维码 153 字节预算下 `l`（luciola）与 `p`（legacy TLS pin）互斥。
4. 实验开关默认关；开启后 legacy 自动发现停止，需重启节点。
