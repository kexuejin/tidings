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

## 5. 已知边界 / 后续

1. `luciola-android 0.4.4` 只在 mavenLocal，**Maven Central 未发布**（0.4.3 为 404）；
   发版前走 `scripts/publish-maven-local.sh` 的 Central 分支并复核。
2. Android 入站仅 IPv4（Rust 侧 `0.0.0.0`），IPv6 入站需 luciola 侧补。
3. 二维码 153 字节预算下 `l`（luciola）与 `p`（legacy TLS pin）互斥。
4. 实验开关默认关；开启后 legacy 自动发现停止，需重启节点。
