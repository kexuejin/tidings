# luciola 文件传输 / 投屏数据面迁移设计

日期：2026-10-26 · 状态：**设计 + 可行性量具（未改生产路径）**

## 1. 目标与验收判据

把文件与投屏的**字节流**从 legacy TCP/TLS（17879 / 17881 / 17882）迁到 luciola 加密信道。
验收判据（真机，Pixel 6 ↔ macOS）：

1. 发一个 ≥50 MB 的文件：两端校验 sha256 一致；主机侧只有 `channel=TRANSPORT` 的传输记录；
2. `lsof` 在两端都看不到 17881（跨网文件）连接，17879 也未被使用；
3. 投屏 1080p 连续 5 分钟：帧率与延迟不高于现有路径的 90%；
4. 断网 5 秒恢复：文件从 offset 续传，投屏自动重连；
5. 回归：core/app/composeApp/desktopApp 四套测试全绿。

## 2. 现状（已核实）

| 部分 | 路径 | 是否已加密 |
| :-- | :-- | :-- |
| 消息 / 文件控制帧（file-ctl）/ 投屏信令（screen） | Messenger → DatagramTransport | ✅ luciola 模式走 SecureChannel |
| 文件字节流（局域网） | `TransferServer` / `TcpFramedChannel` 17879 | ❌ |
| 文件字节流（跨网） | `CrossNetworkRawDialer` → `tlsPort+1`（17881）TLS | ❌ |
| 投屏画面流 | `ScreenWire` / `screenPort` = `tlsPort+2`（17882） | ❌ |

`NodeRuntime` 在 luciola 模式下**仍然创建** `TcpFramedChannel`、`LinkServer/LinkClient`
（无条件），这是当前双栈的来源，也是"文件/投屏还能用"的原因。

## 3. 可行性量具（本次已跑）

`LuciolaChannelThroughputSpikeTest`（直接压 `SecureSession`，64 KB 帧，回环）：

```
THROUGHPUT frames=192 chunk=64KB total=12MB elapsed=2255ms mbps=5.32 queueFullRetries=501
```

结论与设计输入：

- **回环 5.32 MB/s（约 42 Mbps）**。真实局域网会更低；1080p 投屏若按 3–9 MB/s 估算，
  **余量很薄**，所以投屏必须用二进制帧、不能 base64（base64 再打 67 折）。
- 64 帧发送队列很容易打满（501 次 false）⇒ 需要**应用层窗口 + 背压**，不能 1ms 自旋。
- 单条 SecureSession 是单 TCP 流：文件与投屏共用会互相拖累；建议**一个用途一条会话**
  （或至少投屏独立会话），否则大文件会把投屏卡住。

## 4. 目标架构

### 4.1 新的二进制数据帧（已实现 codec）

`FileChunkFrame`（`core/src/commonMain/.../transport/FileChunkFrame.kt`）：

```
magic "TDCH" + ver u8 + tidLen u16 + transferId + offset u64 + len u32 + payload(≤256 KiB)
```

- 有单测覆盖：往返、坏 magic/版本、截断、超限、空 id —— 一律 `null`，**绝不"尽力解析"**；
- 每块带 `offset`：乱序/重复块按 offset 幂等写入 part 文件，天然支持续传。

### 4.2 入站归属（必须先解决的一个缝）

当前 `SecureChannelTransport` 的入站帧归属是**解 Envelope 取 `from`**（Messenger 语义）。
二进制数据帧没有 Envelope，必须改成：

```
会话 onState("connected", fingerprint)
  → TrustStore 按 fingerprint 找 deviceId（nodeId）
  → registry.get(nodeId) 得到 (ip, udpPort)
  → 该会话的入站帧归属到该端点
```

即给 transport 增加 `peerOfFingerprint: (String) -> PeerEndpoint?` 解析器（工厂已有
TrustStore + registry，可直接装配），二进制帧与 Envelope 帧**共用同一套归属**。
这一步是文件数据面迁移的前置条件。

### 4.3 分块、窗口、背压

- 块大小 64 KiB（实测帧大小；Wi-Fi 好时可提到 128 KiB）；
- 接收端窗口 W = 32 块（2 MiB in-flight）：每收一块回 `file-window-ack(transferId, offset)`，
  发送端窗口未确认不得继续；
- `send()` 返回 false 时：`delay(2ms)` 起步、指数退避到 50ms，**不用 1ms 自旋**；
- 单文件完成/失败都发 `file-ctl done`（已有帧），复用现有状态机与重试。

### 4.4 复用现有状态机

`TransferManager` 的 offer/accept/resume 状态机（含 part 文件、offset 持久化、sweeper）
**不动**；只把"字节搬运"从 `TransferClient/TransferServer` 换成
`SecureChunkSender/SecureChunkReceiver`：

```
TransferManager（现有，权威）
   ├── 控制：file-ctl（已走 Messenger/加密信道）
   └── 数据：DataPlane 接口
         ├── LegacyTcpDataPlane（现有 TransferClient/Server，保底）
         └── SecureChannelDataPlane（新：FileChunkFrame + 窗口 ACK）
```

数据面通过**能力协商**选择：Profile/caps 增加 `fd1`（file-data over channel）；
对端声明才用新路径，否则回退 legacy。这样灰度期两端都不会坏。

### 4.5 投屏

- 复用同一帧格式但独立 `transferId/sessionId` 与**独立 SecureSession**（避免被大文件挤占）；
- 投屏帧 ≤ 256 KiB（1080p JPEG 一帧通常 100–300 KiB；超出则分两块）；
- 丢帧策略沿用现有 `ScreenWire`：只保留最新帧、旧帧丢弃（实时性优先）；
- 验收门槛：帧率/延迟不低于 legacy 的 90%，5 分钟不掉线。

## 5. 迁移步骤（每步独立可验收、可回退）

1. **传输层归属扩展**：`peerOfFingerprint` + 二进制帧直通（先只做 echo 测试；
   不改任何业务路径）——单测 + 回环集成测试；
2. **DataPlane 抽象**：把 `TransferManager` 的字节搬运抽成接口，legacy 实现行为不变
   （纯重构，四套测试必须全绿）；
3. **SecureChannelDataPlane**：分块/窗口/续传 + 单测（乱序、重复、丢块、断线重传）；
4. **能力协商 `fd1`** + luciola 模式下优先新数据面、失败回退 legacy；真机验收 ≥50 MB；
5. **投屏独立会话** + 真机 5 分钟验收；
6. **收口**：luciola 模式下不再创建 17879/17881/17882 监听；开关转正。

## 6. 风险与对策

| 风险 | 对策 |
| :-- | :-- |
| 单流带宽不够（回环仅 5.3 MB/s） | 投屏独立会话；块 128 KiB；必要时限制投屏码率 |
| 队列打满导致丢帧/卡顿 | 应用层窗口 + 确认；发送侧背压；不 1ms 自旋 |
| 二进制帧与 Messenger 抢 `incoming` | 在 transport 层按 magic 分流，业务层无感知 |
| 灰度期两端能力不一致 | `fd1` 能力位协商，不声明就回退 legacy |
| 迁移期间破坏现有文件传输 | DataPlane 纯重构先行，行为不变；每步有测试与回退开关 |

## 7. 已落地（按迁移步骤）

- **步骤 1 完成**（2026-10-26 晚）：传输层二进制帧直通 + 指纹归属。
  `SecureChannelTransport.peerOfFingerprint`：会话 `connected` 拿到的指纹 → TrustStore
  → nodeId → registry 端点；二进制帧（magic `TDCH`）按该端点原样投递，
  Envelope 帧仍走原来的 `from` 归属。工厂已装配。回归用例：
  `binaryChunkFrameFromTrustedPeerCarriesPeerEndpoint`（帧到达且带对端 registry 地址）、
  `binaryChunkFrameFromTrustedPeerWithoutRegistryEndpointIsDropped`（查不到地址就丢弃）。
- `FileChunkFrame` 编解码 + 单测（`FileChunkFrameTest`）；
- 吞吐量具 `LuciolaChannelThroughputSpikeTest`（回环 5.32 MB/s、队列 64 帧实测）；
- 本文档。**生产业务路径未改动**：文件/投屏仍走 legacy，等步骤 2/3。

## 8. 下一步（待做）

1. ~~`TransferManager` 的字节搬运抽成 `DataPlane` 接口~~ ✅ 第 1 步已完成（见 10）；
2. `SecureChannelDataPlane`：把 `SecureChunkSender` 接进 `FileDataPlane`，补接收端 helper
   （连续前缀 ACK 的接收侧封装，测试里已有一份逻辑可提炼）+ 发送端要把 offer 的文件
   按 offset 读出来（现在 sender 吃整块 ByteArray）；
3. `fd1` 能力协商 + 真机 ≥50 MB 验收；
4. 投屏独立会话 + 5 分钟验收；
5. luciola 模式关 legacy 监听；开关转正。

## 10. 步骤 1 完成（2026-10-26 晚）

- 新增 `FileDataPlane` 接缝 + `LegacyTcpDataPlane`（逐字等于原 `pullTransfer`）；
- `TransferManager` 增可选参数 `dataPlane`：**null = 今天的明文实现**，四套测试证明行为未变；
- 抽缝时刻意**不抽**续传/校验/去重/取消语义（那些在 `PullSession` 里，与"字节从哪条连接来"
  无关），否则就变成一次行为重写；
- 回归 `TransferDataPlaneSeamTest` 两例：默认路径照旧把报价处理掉；注入的数据面
  **真的被调用**且拿到 transferId/files/saveDir（这是下一步接加密信道的前提）。
  写用例时踩到两个前置条件（`peerAddress` 必须非 null、`pull` 只认 `acceptedOffers`），
  两条都写进了用例注释——否则测的会变成"对端离线"而不是接缝。

## 9. 追加落地（2026-10-26 晚，步骤 2/3 核心件）

- `FileChunkAck`：窗口确认帧（只确认**连续前缀**，空洞必须重传）+ 严格解码 + 单测；
- `SecureChunkSender`：64KiB 块、32 块窗口（可调）、ACK 超时从已确认位置重传、
  发送面 false 时退避不自旋；尚未接进 `TransferManager`；
- 集成测试 `SecureChunkSenderTest`：两条真实 SecureChannelTransport 回环，
  接收端**故意丢一个块**，发送端靠 ACK 超时重传后字节完全一致（sha 级断言用
  `assertContentEquals`），并断言"确实丢过"。
