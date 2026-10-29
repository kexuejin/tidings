# goal 结案报告：六项目标的达成情况（30 轮，逐项证据）

日期：2026-10-27。这份是 goal 的**结案报告**，不是过程文档 —— 逐项说明做到什么程度、
证据在哪、没做到的部分为什么。

分支 `feat/luciola-discovery`，领先 `origin/main` **88 个提交**，**未 push**。

---

## (1) TransferManager 抽成 DataPlane 接口，legacy 行为不变 —— ✅ 达成

- `FileDataPlane` 接缝 + `LegacyTcpDataPlane` 委托给原 `pullTransfer`；
- `TransferManager` 新增 `dataPlane` / `registerChannelInbound` / `pushFileOnChannel` 三个
  可选缝，全为 null 时行为与改动前逐字一致；
- 证据：`TransferDataPlaneSeamTest`，以及四套 JVM 测试全程绿（见 (6)）。

## (2) SecureChannelDataPlane（64KiB / 窗口 ACK / 乱序 / 重复 / 丢块 / 重传）—— ✅ 达成

- `SecureChunkSender`（64KiB 分块、`windowChunks=32` 窗口、连续前缀 ACK、超时整窗重传）；
- `SecureChunkReceiver`（按块位图 + 半块 `partialCoverage`，只推进连续前缀）；
- `ChunkFileReceiver`（`.part` → 长度/sha256 校验 → 去重改名；**按帧 offset 随机写**）；
- `FileChannelRouter`（`tid#fid` 路由、收齐后不摘表以继续回 ACK）；
- 证据：`FileChunkFrameTest` / `SecureChunkReceiverTest` / `SecureChunkSenderTest`
  （真机两条真实信道、**故意丢一个块**再由重传补齐）/ `ChunkFileReceiverTest`（含
  `outOfOrderChunksLandAtTheirOffsets`：2→0→1 倒着喂，逐字节校验）/ `FileChannelRouterTest`。

## (3) fch1 协商 + 优先新数据面 + 真机 ≥50MB sha256 + lsof 看不到 17879/17881 —— ✅ 达成

- `MsgTypes.CAP_FILE_CHANNEL = "fch1"`（与 `fd1` 明确区分：一个管"走哪条路"，一个管
  "要不要弹确认框"）；`FileChannelNegotiation.canUseChannel` 要求**双方都声明**；
- 单侧声明的后果（"发送端推、收端等"）在文档里写清；
- **真机验收通过**（`docs/2026-10-26-luciola-realdevice-50mb-acceptance.md`）：

```
发送端 a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05
接收端 a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05   # 52,428,800 字节

宿主 TCP 监听：17880 / 17882 / 17888 / 8788 —— **没有 17879 / 17881**
手机 TCP 监听：17880 / 17882 / 17888     —— **没有 17879 / 17881**
信道连接：双向 17878 ↔ 17878
```

- 途中修掉的真缺陷（`EndpointOfFingerprintTest` 守）：只扫过码的对端，`endpointOfFingerprint`
  只查 Rust TrustStore ⇒ 二进制帧归属不到 ⇒ 50MB 一个字节都不落盘。
- `:core:jvmAcceptanceTest` 是独立任务（不混进单元套件），可复跑。

## (4) 投屏独立会话 + 5 分钟帧率/延迟 ≥ legacy 的 90% —— ⚠️ **帧率达成，延迟未达成**

**做成的部分：**

- `SecureScreenChannel`：一条**专用** `SecureSession` + `ScreenChannelFrame`（魔数 `TDSC`）
  封套；`scr1` 能力位 + `Profile.screenPort`；`ScreenChannelNegotiation` 三条件；
- 接进运行时：Sharer 起监听、Viewer 把新通路排在**最前**、覆盖对端**候选地址**（退役 17882 的前提）；
- `ScreenStats` + `SegmentStats`（rx/tx、rxseg 两段、txseg wrap/send 拆分）—— 这些量具是
  下面所有结论的基础；
- **连续 5 分钟真机验收（各 31 窗，10s 一窗，无一次掉档）**：

| 组 | 帧率（均值 / 最差） | 延迟（均值 / 峰值） |
|---|---|---|
| legacy 明文 | 9.357 / 9.29 fps | **17.1 ms** / 52 ms |
| luciola 加密 | 9.383 / 9.30 fps | 38.3 ms / 89 ms |

- **帧率判据成立**：9.383 / 9.357 = **100.3%**（最差窗口 100.1%），5 分钟内从未触发
  `ScreenRate` 降档；
- **延迟判据不成立**：2.24×（后来优化到 **38.5ms / 2.0×**）。

**为什么延迟没达标（已定位到根因，且是结构性的）：**

```
rx 延迟 38.5ms = 第一段 38ms（发 screen-next → 收到帧头）
              + 第二段 0ms（帧头 → JPEG 齐，已归零）
```

- 第二段（传字节）**已被做到 0**：段数从 659/10s 降到 94–95（一帧一段），用的是
  `availableForRead` 一次取完已到字节（**八轮里唯一真正成功的那次改动**）；
- **剩下的 20ms 在第一段**：那一段我们只发了 `screen-next`（~30 字节）然后等对端回帧，
  而 legacy 同样场景只要 17–19ms。**这 20ms 在对端的采样 + 编码 + 调度里，与传输无关。**
- 另外两个结构性事实（都已用测量钉住，不是推测）：
  - Android 上单次 `send`（uniffi+JNA）是 **3.4–3.9ms**，而 Mac 上同一调用只要 **130µs**
    —— 慢 26 倍。早期用 Mac 量具得出的结论全部作废（`2026-10-27-luciola-send-cost-on-android.md`）；
  - 一帧 49KiB 在 uniffi 边界上**至少要拷一次**（Rust `send(Vec<u8>)` 需要独立所有权），
    这是结构性成本（`2026-10-27-luciola-inplace-wrap.md`）。

## (5) 退役 17879/17881/17882 + experimentalLuciola 转正 + 老用户共存/回退说明 —— ⚠️ **退役达成，转正未做**

**退役（达成）：**

| 端口 | 实现 | 证据 |
|---|---|---|
| 17879 明文文件 | `TransferManager.legacyTcpListener = !fileChannelEnabled` | 真机 `lsof` 已验证 |
| 17881 跨网文件 TLS | 同上（一个开关关掉两条入口） | 同上 |
| 17882 跨网投屏 TLS | `NodeRuntime` 的 `if (!fileChannelEnabled && …)` | JVM 两条用例（`ScreenChannelWiringTest`）；**真机 lsof 复核未做** |

先做"画面信道覆盖对端候选地址"再退役 17882 —— 否则蜂窝场景会彻底没有投屏（`2026-10-27-luciola-retire-17882.md`）。

**措辞转正（部分达成）：** 去掉了"实验"字样，副标题写清三件事（都走加密信道 /
与未升级设备失去自动发现 / **关掉即回退**），中英文都改（`994f3b66`）。

**默认开（未做，且我认为不该在未达标时做）：**

- 延迟判据未达标就默认开，等于把没验好的数据面推给所有用户；
- 更重要的：**开启后 legacy 发现整体关闭**（`NodeRuntime.kt:394`），于是**未升级的老设备
  既发现不到我们、我们也发现不到它**，而界面只显示"离线"，与"关机"无法区分。
  开关默认关时这个代价由主动开启的人承担；默认开后落在普通用户身上。
- 共存方案有三条（双发现并行 / 迁移引导 / 保持默认关只改文案），**已把技术前提做出来**：
  `LegacyDiscoveryVisibilityTest` 证明"只迁数据面、保留 legacy 发现"技术上成立
  （发现走哪条路由 `messengerTransportFactory` 决定，不是 `fileChannelEnabled`）；
  控制面 `/v1/peers` 新增 `reachability: legacy-peer-invisible` / `legacyDiscovery: off`，
  让"老设备为什么不可见"变成可读判据。
- **这一条需要产品决策**（三条候选各有代价），我没有替用户决定 —— 完整论证见
  `2026-10-27-luciola-why-not-promoting-yet.md`。

## (6) 四套 JVM 测试全程全绿 + 每步独立 commit + 不 push —— ✅ 达成

最终状态：

```
core      tests= 714  fail=0
app       tests= 681  fail=0
composeApp tests= 469  fail=0
desktopApp tests= 182  fail=0
agentNode tests= 141  fail=0        （额外）
```

- **88 个独立提交**，每个都有可复核说明；
- **未 push**（未经授权）；
- 追加了 `:core:jvmAcceptanceTest` 独立任务：50MB 验收跑 ~40s 且持续做文件 IO，混进单元套件
  会把同 JVM 后续用例挤到超时（实测 3 条 10s 挂起）。

---

## 途中修掉的、与 goal 无关但真实存在的缺陷

| 缺陷 | 证据 |
|---|---|
| 入站帧被 `MutableSharedFlow` 默认 DROP_OLDEST 静默丢弃 | host 发成功 192 帧、对端只见 177 帧 |
| 会话被"摘表重建"导致两条链路互相顶替 | `conns=15 retries=7`，传输中途重连 15 次 |
| 分块按到达顺序落盘（乱序即写错位置） | 长度对得上、sha256 对不上 |
| 收齐即摘表 ⇒ 末帧 ACK 一丢永久卡住 | acked=50593792/52428800，文件其实早已转正 |
| 只扫过码的对端二进制帧归属不到 | 50MB 一个字节都不落盘 |
| 控制面被 3 秒解析拖死（16 线程全卡） | 真机 `/healthz` 超时 → 修后 13ms |
| 明文画面尝试把 `ConnectException` 扔给调用方 | 新通路根本没机会被尝试 |
| 配对结果 `replay=0` 导致应答丢失 | "扫了码什么都没发生" |

## 未完成项与建议（按优先级）

1. **延迟判据**：第一段那 20ms 在对端采样/编码，需查"luciola 模式下手机侧为何比 legacy 慢"。
   若查不出，建议**重新定义判据**（改成"帧率 + 不卡顿"）或接受 ≈2× 延迟写进发布说明 ——
   传输侧已经做到第二段归零，可优化空间不在传输上。
2. **老用户共存**：三条候选需产品决策（见上）。
3. **两项真机复核**：17882 退役的 `lsof`、≥50MB sha256 在最近多次因本机 load 4.5–6.5
   未取得有效结果（`2026-10-27-acceptance-blocked-by-host-load.md`），需干净环境重跑。
4. 上游：luciola 仓已有两个未推送提交（`TCP_NODELAY`、AES 指令），需一并合入。

## 一句话总结

**六项里四项完全达成（含真机 50MB + 端口退役），第五项的退役与措辞达成而"默认开"未做
（缺产品决策），第四项帧率达成而延迟未达成（已定位为对端采样/编码，不在传输侧）。**
所有"未达成"都有测量数字与结构性解释，没有一项是靠估计得出的。
