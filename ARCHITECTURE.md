<!-- SPDX-License-Identifier: GPL-3.0-only -->

# Tidings（捎话）系统架构设计

本文档定义 Tidings（捎话）跨平台局域网通信客户端与 Agent 控制面的完整系统架构、模块依赖边界、平台抽象契约及质量门禁规范。

---

## 1. 系统定位与设计原则

Tidings 是一款基于 Kotlin Multiplatform (KMP) 与 Compose Multiplatform 构建的开源、轻量级、去中心化局域网点对点通信应用与 AI Agent 协作工作台。

### 1.1 核心原则

1. **协议兼容性与对称性**：在局域网内与上游 P2P 协议全互联（UDP 单播/广播 + TCP 字节流）。信封编解码严格遵循零冗余键（`hasOnlyKeys`）及白名单校验。
2. **多平台共享内核（Write Once, Run Anywhere）**：所有协议解析、传输控制、重传与退避、节点状态机、会话编排及持久化存储全部收敛于 `:core` 模块，Android、iOS 与 Desktop 共享同一套业务内核。
3. **平台壳极薄化**：平台宿主层（`androidApp`、`iosApp`、`desktopApp`）仅负责初始化、权限申请、生命周期钩子及系统硬件契约的实现，不包含任何业务与协议逻辑。
4. **Agent 与 IM 协议解耦**：宿主机器上的 Agent 通过本地回环 HTTP 控制面（`127.0.0.1:8788`）接入，无需实现底层 P2P 发现、分片与网络协议。
5. **分表持久化与数据隔离**：人类日常聊天记录与 Agent 执行事件/任务队列在数据库层面完全分表隔离，保障聊天数据的整洁与 Agent 事件的可追溯性。

---

## 2. 模块拓扑与分层结构

```
                    ┌────────────────────────────────────────────────────────┐
                    │            androidApp / iosApp / desktopApp            │
                    │   平台宿主壳：生命周期、系统权限、硬件驱动、托盘与常驻  │
                    └───────────────────────────┬────────────────────────────┘
                                                │ 注入 PlatformContracts 实现
                    ┌───────────────────────────▼────────────────────────────┐
                    │                       composeApp                       │
                    │    Compose Multiplatform UI、响应式多端布局、设计规范   │
                    │      （CompactSinglePane / MediumTwoPane / Expanded）   │
                    └───────────────────────────┬────────────────────────────┘
                                                │ 驱动 UI 状态与事件 Flow
                    ┌───────────────────────────▼────────────────────────────┐
                    │                          :core                         │
                    │  ├── protocol/   纯 Kotlin 零 IO 编解码与信封定义        │
                    │  ├── transport/  UDP/TCP 传输、mDNS/单播发现、重发与拉取 │
                    │  ├── storage/    SQLDelight 多平台数据库与实体映射      │
                    │  └── session/    NodeRuntime 节点运行时核心             │
                    └──────────┬─────────────────────────────────┬───────────┘
                               │                                 │
              JVM (无 Compose) │                                 │ 多平台依赖
                    ┌──────────▼───────────────┐                 │
                    │        :agentNode        │                 │
                    │   Headless JVM 库组件    │                 │
                    │   本地 HTTP 控制面 :8788 │                 │
                    └──────────┬───────────────┘                 │
                               ▲                                 │
                               │ implementation                  │
                    ┌──────────┴───────────────┐                 │
                    │        desktopApp        │◄────────────────┘
                    │   桌面托盘、开机自启、   │
                    │   同进程内嵌 Agent 控制面│
                    └──────────────────────────┘
```

### 2.1 模块职责划分

- **`:core`**：核心基础库。包含协议定义、编解码器、网络通道抽象、传输队列、离线暂存、会话编排及数据持久化。
- **`:composeApp`**：UI 表现层。包含会话列表、聊天视窗、节点发现、Agent 看板、设置面板及多端自适应布局。
- **`:agentNode`**：Agent 控制面组件。提供本地回环 HTTP API（Ktor Server），供 AI 代理或本地脚本调度与交互。在桌面形态下作为库被 `desktopApp` 同进程引用。
- **`:androidApp`**：Android 原生壳工程。配置 `MainActivity`、CameraX 扫码、前台服务（后台消息接收）及 SAF 文件契约。
- **`:iosApp`**：iOS 原生工程（XcodeGen 管理）。配置 `MainViewController`、AVFoundation 扫码及 security-scoped 文件契约。
- **`:desktopApp`**：桌面端原生入口。提供窗口管理、系统托盘常驻，并常驻承载 `:agentNode` 控制面。

---

## 3. 架构依赖与约束规则

系统架构中强制执行以下单向依赖与设计规则：

1. **`:core` 不得依赖任何 UI 框架**：严禁引入 Compose、Android SDK 或 UIKit。确保其可在无图形界面的 Headless JVM、测试夹具及命令行环境中独立运行。
2. **`protocol` 包零 IO 约束**：`core.protocol` 内严禁出现任何网络套接字（`Socket`）、文件读写、协程分发器（`Dispatchers.IO`）。该层必须是纯确定性函数（Pure Functions），保障与线上多端向量完全对拍。
3. **平台抽象单一出口**：`:composeApp` 仅能通过 `PlatformContracts` 抽象向平台宿主索取原生能力，禁止在公共代码中穿透平台实现。
4. **宿主层纯装配原则**：`androidApp`、`iosApp`、`desktopApp` 仅提供平台配置与契约实现，严禁在宿主壳内编写任何业务流程或协议路由逻辑。
5. **`:agentNode` 无 UI 污染**：`:agentNode` 严禁应用 Compose Multiplatform 插件，保持轻量高效。

---

## 4. 源集结构与多平台组织

```
:core
├── commonMain     协议实体、编解码、状态机、SQLDelight 数据库定义、NodeRuntime
├── jvmMain        JVM 平台文件 IO、套接字与线程映射
├── androidMain    Android SAF 句柄转换、ConnectivityManager 网络探测
├── iosMain        iOS NSFileManager、NWPathMonitor、SecurityScoped 资源释放
└── commonTest     黄金测试向量、报文编解码对拍、重传与节点发现测试

:composeApp
├── commonMain     多平台 Compose UI、自适应导航骨架、状态管理、设计系统
└── commonTest     自适应布局断点、Reducer 状态转移与徽标状态派生测试

:agentNode
├── jvmMain        ControlPlane 本地 HTTP 路由定义与 Token 鉴权
└── jvmTest        控制面 HTTP 契约测试与进程内对拍

:androidApp
├── main           MainActivity、通知通道配置、前台生命周期托管
└── screenshotTest Compose 预览图逐像素截图回归测试集

:desktopApp
└── jvmMain        窗口管理、托盘图标、后台驻留与内嵌 AgentNode 服务
```

---

## 5. 核心协议与传输架构

### 5.1 信封模型与序列化

局域网内所有数据传输均封装为扁平 JSON 结构的 `Envelope`：

```kotlin
@Serializable
data class Envelope(
    val type: String,
    val from: String,
    val seq: Long,
    val to: String? = null,
    val payload: JsonElement? = null,
    val time: Long? = null,
    val groupRev: Long? = null,
    val targetId: String? = null,
    val adminSecretHash: String? = null,
)
```

- **序列化不变式**：开启 `encodeDefaults = false`，避免默认字段序列化导致对端因键白名单严格比对而丢弃报文。
- **协议帧长**：UDP 报文严格遵守限制（单包安全上限建议 ≤ 800 字节）。大于 1200 字节的载荷自动平滑切换至 TCP 字节流。
- **TCP 帧结构**：采用 4 字节大端网络序整数（Big-Endian Int32）标识 JSON 载荷长度，后接 UTF-8 编码的 JSON 字符流。

### 5.2 节点发现与在线状态管理

- **发现广播与单播**：节点通过 UDP 监听端口（默认 `17878`）接收 `entry`、`alive`、`exit` 与 `presence` 报文。
- **mDNS 自动辅助发现**：在支持 Bonjour / NSD 的平台上广播 `_relay._udp` / `_teahouse._udp` 服务，便于跨子网及未配置组播的局域网自动配对。
- **动态端口绑定保障**：若默认端口被占用，节点自动递退回系统动态分配端口，并将真实绑定的 `tcpPort` 广播于 Profile 报文内，防止同机多实例冲突。
- **地址漂移防御**：`PeerRegistry` 记录各节点的验证地址与 `profileRev`。当节点 IP/端口发生漂移时，必须接收全量带有递增版本的 `profile` 报文才允许更新路由。

### 5.3 可靠传输与文件拉取机

1. **可靠 ACK 机制**：`Messenger` 为 `msg`、`file-ctl`、`group` 等可靠帧维护发送队列与超时指数退避（初始 500ms，最高 8s）。连续重发超限后尝试降级走 TCP 发送。
2. **文件传输状态机**：
   - 发送方发出 `file-ctl offer`。
   - 接收方确认接收后，通过 TCP 发起 `file-ctl pull` 请求并附带 `offset`（支持断点续传）。
   - 发送方回执 `pull-ok` 后无缝切换为原始二进制流直传。
   - 传输过程中未完成的文件暂存为 `.part` 文件，校验 SHA-256 完整通过后重命名为正式文件。

### 5.4 长连接下行通道（K1 / K2）与传输安全两档

手机主动拨主机，建立一条 TCP 长连接（能力位 `lnk1`，由**主机**声明；手机据此决定要不要拨号 —— 不声明就按老端对待，一个拨号都不发）：

| 档 | 条件 | 承载 |
|---|---|---|
| **K1 明文** | 对端在局域网（`isLanAddress`） | 局域网内长连接；**默认关闭**（开关在发起侧的手机上） |
| **K2 TLS** | 公网对端 | TLS + 双向 SPKI pin，**fail-closed** |

- **「局域网」判据唯一**：`IpAddressUtils.isLanAddress(peer, localAddresses)` = 私网/回环 ∨ 链路本地（`fe80::/10`）∨ ULA ∨ **与本机某个地址同 /64**。拨号、拨号诊断、接受裁决、地址复查四处共用它（曾经不一致，后果是把 K1 静默关掉 —— 见 `docs/public-transport-security.md` §11）。**IPv6 的 GUA ≠ 公网可达**：家庭 LAN 常分配 `2408:...::/64` 这类全局地址，只认私网判据会让 K1 一个目标都不拨。
- **fail-closed**（`peerPinAccepted`）：对端没给证书 / 本机没有期望 pin / pin 不一致 ⇒ **拒**；TLS 验不过**不回退明文**。明文只允许局域网：接受侧对非局域网的明文连接回 `not-lan`；地址变化后的复查侧只摘「声明自己是明文**且**已不在局域网」的那条（fail-safe，不误摘 TLS 连接）。
- **身份与 pin**：EC P-256（secp256r1）自签证书，SAN 含本机地址；pin = `sha256:` + SHA-256(SPKI DER)。私钥不变，证书到期**自动续签**（轮换与到期提示仍在计划中，P4）。
- **建连即更新对端地址**；断线自恢复（退避 1s→60s，±20%）。`NetworkWatch`（Android `ConnectivityManager` 回调 / iOS `NWPathMonitor`）在换网时**立即重拨**（真机实测 ≈3s，要求 ≤10s）。
- **免心跳与省电**：有数据往来时不发心跳，保底 Ping 兜底（心跳 15s、45s 判死 ⇒ 判死阈值 > 2×心跳）；Doze（`PowerManager.isDeviceIdleMode`）时降频。**不用息屏作判据** —— 息屏时 NAT 映射往往还活着，拉长心跳反而更容易掉线。

### 5.5 跨网（公网 IPv6）直连与候选池

- **候选池**：配对码可携带 `lan_v4` / `lan_v6` / `public_v6` 三类候选；按本机地址表决定先打哪个（同网段优先 lan，跨网才用 public_v6）。
- **地址漂移重学**：对端地址变化后按新地址重建拨号目标，并复查明文策略（§5.4 的复查侧）。
- **离线积压补发**：出站可靠帧进持久队列；对端上线握手后按序补发。
- **跨网文件传输**：公网场景**另开第二条 TCP**，复用 `file-ctl` / 裸流 / 断点续传；公网必须 TLS + 双向 pin。**屏幕投屏仍仅局域网**（跨网屏幕未实现）。
- **前提**：对端路由器放行 IPv6 入站。本应用不做打洞、不代改网络设置。

---

## 6. 平台契约设计 (`PlatformContracts`)

系统通过接口抽象消解移动端与桌面端的系统级差异：

| 契约接口 | 功能定义 | Android 实现 | iOS 实现 | 桌面 JVM 实现 |
|---|---|---|---|---|
| `FilePicker` | 选择外部文件并获取抽象句柄 | `ACTION_OPEN_DOCUMENT`（保留 URI Grant） | `UIDocumentPickerViewController` | `JFileChooser` / 本地原生面板 |
| `SaveDirectoryPicker` | 选择存储目录并持久化访问授权 | `ACTION_OPEN_DOCUMENT_TREE` | Security-scoped 目录访问 | 本地目录文件路径 |
| `QrScanner` | 相机扫描二维码并返回内容 | CameraX + ML Kit 离线分析 | AVFoundation 视频流捕捉 | 不提供（桌面端展示二维码供扫） |
| `CameraGate` | 相机硬件访问权限状态与申请 | `Manifest.permission.CAMERA` | `AVCaptureDevice.requestAccess` | 恒为 `Granted` |
| `NetworkMonitor` | 实时监测 Wi-Fi/蜂窝/VPN 网络状态 | `ConnectivityManager.NetworkCallback` | `NWPathMonitor` | 本地网卡轮询与状态判定 |
| `MdnsAdvertiser` | mDNS 节点服务发现与注册 | Android `NsdManager` | iOS Bonjour (`NWListener`) | JmDNS 或系统服务 |
| `PushNotificationHandler` | 本地消息与前台提示通知 | `NotificationManager` 渠道管理 | `UNUserNotificationCenter` | 桌面托盘气泡通知 |

所有文件句柄均抽象为 `FileHandle`，不暴露原始文件路径字符串，由各平台独立维护生命周期，避免移动端沙盒权限过期导致的读取失败。

---

## 7. AI Agent 控制面集成

### 7.1 拓扑关系

`:agentNode` 模块以无界面 Headless 模式运行，在桌面端由 `desktopApp` 同进程启动：

- **HTTP 监听**：默认绑定 `127.0.0.1:8788`。
- **安全认证**：本地随机生成强密钥（Bearer Token），存储于本地受限文件（权限 0600）。
- **RESTful 端点规范**：
  - `POST /v1/send`：向指定节点或群组发送消息、附件或任务指示。
  - `GET  /v1/peers`：查询局域网当前存活的节点快照与 Profile 信息。
  - `GET  /v1/inbox`：按游标（`since`）拉取入站消息与回执事件。
  - `GET  /v1/healthz`：控制面与节点运行时健康状态探测。

### 7.2 协议扩展支持 (PPE-1)

通过 `agent-*` 系列协议帧扩展，支持结构化智能代理通信：
- **`agent-session`**：代理会话的创建与状态同步，关联特定的工作区路径与代理 ID。
- **`agent-event`**：增量事件流推送（支持增量 cursor 与幂等去重）。
- **`agent-queue` / `agent-run`**：代理任务队列与执行状态机流转。
- **`agent-approval` / `agent-ack`**：需要人类介入确认的高风险动作审批（未确认 ≠ 已执行）。
- **`agent-artifact`**：任务产物文件元数据交换。

---

## 8. 持久化存储设计

数据持久化使用 SQLDelight 构建跨平台统一的 SQLite 存储层：

### 8.1 聊天消息表 (`relay.db`)

- **`messages`**：存储人类聊天记录（单聊、群聊），自增 `seq` 作为单调排序键。
- **`conversations`**：会话元数据（会话 ID、未读数、最后消息摘要、置顶与静音标记）。
- **`peers`**：节点 Profile 缓存（NodeId、名称、IP、端口、caps、更新版本）。
- **`send_queue`**：离线暂存队列，存储尚未完成 ACK 确认的报文。
- **`dedup`**：基于 `(msg_id, peer_id)` 的滑动窗口去重表。

### 8.3 TLS pin 存储（`stores/peer-pins.json`）

每个 `nodeId` 一条 pin，**并记录它的来源**（`pairing` / `tofu`）。旧文件没有 sources 键 ⇒ 一律读成 `unknown`，**绝不假装 `pairing`**。诊断出口两处：宿主 `/v1/peers` 与手机 `files/relay/diag/link.json` 的 `pinSource`。TOFU 是**降级路径**（首次使用竞态），所以它必须可诊断 —— 这是它留在实现里的唯一前提。

### 8.2 Agent 独立分表

严格遵循数据隔离规范，防止高频 Agent 流水日志污染日常聊天会话：
- **`agent_sessions`**：Agent 会话记录，主键为 `sessionId`，包含所属工作区 `wsId`、工作区目录 `wsPath` 等属性。
- **`agent_events`**：Agent 执行流水线增量事件，支持按 `(sessionId, cursor)` 快速续读与分页。
- **`agent_tasks`**：任务执行单元表，维护队列调度与终态记录。

### 8.4 `stores/` 下的旁路存档（JSON / 追加日志）

不是所有状态都值得进 SQLite。这几份是**旁路存档**，各有自己的失效语义：

| 文件 | 内容 | 失效/上限 |
|---|---|---|
| `stores/peer-pins.json` | 每个 `nodeId` 的 TLS SPKI pin 与来源（`pairing` / `tofu`） | 见 §8.3 |
| `stores/peer-endpoints.json` | 配对码带来的候选端点池（换网后按序兜底） | 对端每次宣告会覆盖 |
| `stores/known-peers.json` | **曾经见过**的对端档案（nodeId / 昵称 / 最后地址 / 最后露面） | 90 天 TTL |
| `stores/messages.log` | 信封追加日志，供 `/v1/inbox` 的 `since` / `cursor` 续读 | **当前无上限**（见下） |
| `stores/queue.log` | 离线补发队列 | 7 天 TTL / 每对端 200 条 |

`known-peers.json` 存在的理由只有一条：**重启后、对端还没被发现在线时，节点列表也要显示真名**。
没有它时，冷启动会先按 nodeId 前 8 位造一个合成名（`node-a1b2c3d4`），用户看到的是一台
自己明明配对过的设备变成了陌生人。`PeerRegistry.restoreOffline` 只**想起它是谁**：
`online = false`、不刷新 `lastSeen`、不产生事件、不覆盖已存在的实时记录。

> **已知待办**：`FileMessageStore.trimTo(maxRows)` 已实现并单测，但**生产路径没有调用它** ——
> `NodeRuntime.prune` 只清队列与去重表。于是 `messages.log` 会一直长（真机桌面端一天
> 12.9 万行 / 72 MB，而消息表只有 1235 条：日志里还含 Agent 事件信封）。接线时要处理
> 一个语义问题：`/v1/inbox` 的旧 `cursor` 可能指向被裁掉的行，需要约定「过期游标从最早一行续读」。

### 8.5 全文检索索引（FTS5）的维护纪律

`MessageFts` 是**外部内容表**（`content='MessageEntity'` + `tokenize='trigram'`），
索引**不是**自己维护的，靠三条触发器。它们守的是一条不变量：

> `MessageFts_docsize` 的行数必须**恒等于** `MessageEntity` 的行数。

两条真机上翻过车的坑，都写在触发器旁边：

1. `INSERT OR REPLACE` 走的是「隐式删除 + 插入」，而**隐式删除不激活 `AFTER DELETE`** ⇒
   旧行的索引条目留下来、新行又插一条，每条被重复写入的消息留一个孤儿。
   修法：写消息前**显式**执行 `insertMessageDropStaleIndex`（FTS5 的 `'delete'` 命令）。
2. `AFTER UPDATE` 必须**只**在 `text` 真的变了时才动索引（`WHEN old.text IS NOT new.text`）。
   交付状态/回执/文件卡片这些与检索无关的更新，原来每次都会重建一遍索引。

这两条叠加时索引长到 **4,554,999 行 / 21 GB**（真实消息 941 条）。
排查与修复过程见 [`design/runtime-evidence/relay-db-bloat-20261019.md`](design/runtime-evidence/relay-db-bloat-20261019.md)。

**改触发器时要留意**：`.sq` 里的 `CREATE TRIGGER` 只在**新建库**时执行；已存在的库不会
因为装了新版本就换掉旧触发器 —— 修完代码还要对存量库做一次替换 + 重建索引。

---

## 9. 响应式 UI 与自适应布局

`composeApp` 根据设备屏幕可用宽度与形态自动切换四种布局模式（`AppLayoutMode`）：

```mermaid
flowchart TD
    W[窗口宽度 / 形态] -->|宽度 < 600dp 竖屏| M1[CompactSinglePane 手机竖屏]
    W -->|宽度 < 600dp 横屏| M2[CompactLandscape 手机横屏]
    W -->|600dp <= 宽度 < 840dp| M3[MediumTwoPane 平板双栏]
    W -->|宽度 >= 840dp 桌面/宽屏| M4[ExpandedListDetail 桌面三栏展开]
```

- **CompactSinglePane**：单栏导航，底部 NavigationBar，页面间采用栈式跳转。
- **MediumTwoPane**：会话列表与聊天视窗并列呈现，导航收缩为侧边 NavigationRail。
- **ExpandedListDetail**：三栏式桌面视图（导航栏 + 会话树/节点树 + 内容主视窗），充分利用宽屏空间。

---

## 10. 构建、测试与质量门禁

为保障跨平台代码质量与协议实现的长期稳定，构建流程强制执行如下门禁：

1. **协议黄金向量对拍**：`core/commonTest` 包含上游标准实现的全量向量序列化测试用例，确保双向编解码逐字节对称。
2. **多平台单元测试**：
   ```sh
   ./gradlew :core:allTests :composeApp:allTests :agentNode:test
   ```
   覆盖核心协议、节点状态机、离线队列退避、自适应断点与控制面端点。
3. **Compose Preview 截图比对**：
   ```sh
   ./gradlew :androidApp:validateDebugScreenshotTest
   ```
   对 6 屏主要交互界面在深浅色、不同字号及四种自适应形态下进行全量回归渲染比对。
4. **代码整洁与门禁扫描**：
   运行静态检查工具，确保生产代码中无内部调试痕迹、无未授权的绝对路径依赖，所有公共依赖均来自标准公共 Maven 仓库。
