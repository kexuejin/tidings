# 真机验证清单：IPv6 通路 / 授信设备 / 断 WiFi 降级（2026-10-18）

> 真机：红米 24117RK2CC（adb `<device-serial>`，App 进程名 `app.relay`，桌面端 peer 表里昵称 **REDMI K80**，nodeId `05607a4e-9282-4b0a-8437-a931e18406ad`）
> 桌面：macOS，`app.relay.desktop`，控制面 `127.0.0.1:8788`
> 本清单**只读代码 + 只读现场读数**得出，不改生产代码。每条结论都带 `文件:行`。
>
> **这份是 2026-10-18 当天的现场读数留档**（历史证据，不要据此当流程用）。流程已经固化到活文档：
> 公网 TLS / 长连接 / 跨网文件的**标准验收步骤**见 [`docs/physical-acceptance-sop.md`](physical-acceptance-sop.md) 的「模块 4」，
> 逐项判定与留白见 [`ACCEPTANCE.md`](../ACCEPTANCE.md) §8/§9，判据本体见 [`docs/public-transport-security.md`](public-transport-security.md) §11。

---

## 0. 现场基线（开始测之前先记下来，用来做 before/after 对比）

| 项 | 基线值（2026-10-18 12:2x 实测） | 取法 |
|---|---|---|
| 桌面 peer 表里红米那条 | `ip = 192.0.2.140`（**IPv4**），`online = true`，`udpPort=17878`，`tcpPort=17879` | `curl -s http://127.0.0.1:8788/v1/peers` |
| 桌面 UDP 监听族 | `udp46 *.17878`（**双栈**） | `netstat -an -p udp | grep 17878` |
| 桌面 TCP 监听族 | `tcp46 *.17879 (LISTEN)`（**双栈**） | `netstat -an -p tcp | grep 17879` |
| 手机 UDP/TCP 监听 | `/proc/net/udp6` 有 `::`:45D6（17878）；`/proc/net/tcp6` 有 `::`:45D7（17879）LISTEN —— 也是双栈 | `adb -s <device-serial> shell "grep 45D6 /proc/net/udp6; grep 45D7 /proc/net/tcp6"` |
| 手机 → 桌面 IPv6 可达性 | `ping6 2001:db8:2020:cb44:1400:fdbb:f0d0:7679` → 2/2 收到，0% 丢包 | 同上 shell |
| 手机 wlan0 IPv6 | `2001:db8:2020:cb44:2464:8530:42ee:7170`(temporary) + `…697d:ca16:bbce:974c` | 同上 |
| 桌面 en1 IPv6 | `2001:db8:2020:cb44:1400:fdbb:f0d0:7679`(secured) 等，与手机同 /64 | `ifconfig en1 | grep inet6` |

**基线含义**：现在这条连接是纯 IPv4 的（peer 表里是 v4 地址）。任何"IPv6 成功"都必须表现为**上面第一行变成 IPv6 字面量**，而不是"能收发消息"——IPv4 也能收发。

---

## ① IPv6 通路逐段判定

### 1.1 候选地址从哪来

- `desktopApp/src/main/kotlin/app/relay/desktop/DesktopPlatform.kt:171-183 localCandidateAddresses()`：枚举所有非回环、非 `fe80:`、非 VPN 样式的地址 → **IPv4 + IPv6 都在**。
- **只有一处**用它：`desktopApp/src/main/kotlin/app/relay/desktop/DesktopTrustedDevicesPanel.kt:51-63`（设置 → 授信设备）→ `multiCandidatePairingPayload`（`composeApp/.../app/QrPairing.kt:222-241`）。
- 其余所有配对入口都只给 **IPv4**，因为它们取的是 `state.network.localAddresses.firstOrNull()`，而桌面端这个列表来自
  `DesktopPlatform.kt:115-124,160-168 localIpv4Addresses()`（**只取 IPv4**）：
  `ui/desktop/DesktopStatusBar.kt:75,82`、`ui/desktop/DesktopConversationsPane.kt:210,277`、`ui/desktop/DesktopShell.kt:805`、
  `feature/nodes/NodesScreen.kt:521`、`feature/settings/SettingsConnectionSubpage.kt:181`、`App.kt:915`、
  `feature/agents/WorkspaceDetailView.kt:662`。

> 所以"手机上看到的配对码里有没有 IPv6"取决于**用哪个入口**：只有「设置 → 授信设备」那张码带 v6。

### 1.2 一个会误导人的类型不匹配（真机表现：随机选中一个候选）

- 桌面生成时写的 type 是 `"lan"` / `"ipv6"`（`DesktopTrustedDevicesPanel.kt:54`）。
- 手机解析时的优先级却找 `"lan_v4"` → `"public_v6"` → 否则 `first()`（`QrPairing.kt:84-86`）。
- 两边对不上 ⇒ **永远落到 `candidates.first()`**，而 `localCandidateAddresses()` 的顺序由网卡枚举决定，不保证 v4 在前。
  → 扫码路线**不确定走 v4 还是 v6**；要确定性验证 IPv6，用手填（见 1.5）。

### 1.3 扫码/填地址之后连接走哪条路

1. `RelayController.kt:831-841 handleQrCode`：把 `best` 落成手动节点（`addNamedManualPeer`，`:894-924` → `settings.manualPeers`），
   **并且对每一个候选都单播一次 ENTRY**（`:836-840`）——这一句就是 IPv6 能进来的门。
2. 单播 ENTRY：`Discovery.kt:138-145 announceTo` → `UdpTransport.kt:104-108 send`（`InetSocketAddress(host, port)`，**v6 字面量可用**）。
3. 对端地址**只从数据报源地址学**：`Discovery.kt:219-235,268-269 registry.touch(..., datagram.host, datagram.port, ...)`；
   源地址由 `UdpTransport.kt:93-95` + `HostAddress.jvmShared.kt:19-23 literalHost()` 还原成数值 IP（v6 也是数值，不会反解）。
4. 之后所有回包/心跳都发回这个地址：`Discovery.kt:132,161,187`（`broadcastTargets` = 广播 + 手动节点 + **已在线节点**）。
5. TCP 单播（长文本回退 / 文件拉取 / 投屏）：
   `TcpFramedChannel.kt:49-59`（由 `Messenger.kt:249` 在 >800 字节文本时触发）、
   `TransferClient.kt:211`（文件拉取，地址来自 `NodeRuntime.kt:231-233 = registry.ip + profile.tcpPort`）、
   监听端 `TransferServer.kt:150,199`。

### 1.4 IPv6 在哪一段被砍 / 哪一段能走

| 段 | 代码 | IPv6 |
|---|---|---|
| UDP 广播发现 | `Discovery.kt:72-73`（`255.255.255.255`）+ `:367-378` | **砍**（只 v4；但广播只是"发现"，不是唯一路径） |
| UDP 单播（手动节点/已学节点） | `UdpTransport.kt:56-58,86-88`（`bindHost="0.0.0.0"`，`NodeRuntime.kt:87,105`） | **代码写 v4 通配，实测是双栈**（`udp46 *.17878`）⇒ **能走** |
| 手机侧 UDP | 同 `UdpTransport`（Android 共用 jvmShared） | **能走**（`/proc/net/udp6` `::`） |
| TCP 单播 | `TransferServer.kt:199`（`0.0.0.0`） | **实测双栈**（`tcp46 *.17879`）⇒ **能走** |
| UI/配对码给地址 | `localAddresses` 只 v4（`DesktopPlatform.kt:160-168`） | **砍**（除了授信设备那张码） |
| 候选优先级 | `QrPairing.kt:84-86` 的 type 不匹配 | **不可控**（落到 first） |

**结论**：传输层（UDP 单播 + TCP）在双栈监听下**本来就支持 IPv6**；被砍的只有"广播发现"和"UI 只发 IPv4"。
所以「手机用 IPv6 连桌面」**不需要改代码就能做到**，前提是把桌面的 v6 地址**手填**进手机（或让候选列表里的 v6 恰好排在 first）。

### 1.5 最小可复现步骤（确定性走 IPv6）

1. 桌面拿到 en1 的全球 v6（跳过 `fe80:` 与 `deprecated`）：
   `ifconfig en1 | grep inet6` → 例如 `2001:db8:2020:cb44:1400:fdbb:f0d0:7679`。
2. 手机：节点页 → 「添加节点」→ 地址填 **`[2001:db8:2020:cb44:1400:fdbb:f0d0:7679]:17878`**（带方括号；端口是 **UDP 17878**，不是 17879）。
   代码依据：`RelayController.kt:1076-1105 splitHostPort` 认 `[v6]:port`；`:1070-1074 isPlausibleHost` 接受该串；`:922` 立刻 `announceTo`。
3. 判据 A（**唯一无歧义**的判据）：桌面 `curl -s http://127.0.0.1:8788/v1/peers` → REDMI K80 那条的 `"ip"` 变成 **IPv6 字面量**且 `online=true`（基线是 `192.0.2.140`）。
4. 判据 B：桌面 `netstat -an -p tcp | grep 17879` → 出现 `tcp6 <手机v6>.x <桌面v6>.17879 ESTABLISHED`。
   想立刻逼出一条 TCP：在会话里发 **>800 字节**的长文本（`Messenger.kt:249` 走 TCP 回退），或发一个文件。
5. 判据 C（手机侧）：`adb -s <device-serial> shell "grep 45D7 /proc/net/tcp6"` → 出现 remote 为桌面 v6 的 `01`(ESTABLISHED) 行。

### 1.6 失败长什么样

- peer 表 `ip` 仍是 `192.0.2.140` → 手机压根没走 v6（手动节点没生效 / 被同地址去重 / Android 路由选了 v4）。
- peer 表里出现 v6 但 `online=false` 且 `lastSeen` 不前进 → ENTRY 发出去但桌面没收到（防火墙？`udp46` 被占？）。
- peer 表 v6 + online，但 TCP 判据不成立 → TCP 17879 的 v6 不可达（对端 `tcpPort` 不匹配 / 中间设备拦 v6）。
- 手机 UI 只显示"离线"而桌面已在线 → 回包走了 v4 到旧地址（registry 里 ip 被后到的 v4 数据报覆盖，见 `Discovery.kt:221-234` 每次都 `touch`）。

---

## ② 授信设备全流程

### 2.1 取值域（四档 access / 三种 scope / 三档 policy / 三种 state）

- `core/src/commonMain/kotlin/app/relay/core/protocol/MessageTypes.kt:370-380 AgentGrantAccess`：`ro` / `rw` / `admin` / `owner`。
- `:360-367 AgentGrantScopes`：`all`（整台设备）/ `agent`（nodeId+agentKey）/ `workspace`（nodeId+agentKey+wsId）。
- `:382-389 AgentGrantPolicies`：`auto`（任何已添加节点全放行）/ `byDevice`（白名单，缺省拒）/ `request`（**默认**：可申请→桌面批）。
- `:353-358 AgentGrantStates`：`approved` / `denied` / `revoked`。
- 帧与 cap：`:77-87`（`agent-grant-request` / `agent-grant` / `agent-grant-list`）、`:238-245`（cap `agr1`）。

### 2.2 台账与执行点

- 落盘：`agentNode/.../AgentGrants.kt:61`（`AgentGrantStore(file)`），`:409-458 persistLocked`（temp+rename，0600），
  `:308-389 load/failClosed`（文件坏 → 退回最严 `request` 并记 `loadError`，**绝不静默退回 auto**）。
- 执行（唯一门控点）：`AgentGrants.kt:124-153 allows(nodeId, agentKey, wsId, requireRw, requireApproval)`；
  调用点 `ControlPlane.kt:1048,1140,1194`（queue/enqueue/run 等 `requireRw=true`）。
- 桌面进程内的实例：`desktopApp/.../DesktopExtension.kt:295-296`（`<dataDir>/agent-grants.json`）→ 面板拿它：
  `desktopApp/.../DesktopTrustedDevicesPanel.kt:70-73 snapshot`。

### 2.3 界面上在哪设（谁操作）

**桌面端**：设置 → 「授信设备」= `DesktopTrustedDevicesPanel.kt`
- 策略三选一：`:172-206`（radio → `grantStore.setPolicy`）。
- 待办申请队列：`:210-294`（「批准为我的设备」`:243-253` / 「批准为普通设备」`:262-272` / 「拒绝」`:281-286`）。
- 设备列表：`:296-419`；**「标记为我的设备」**`:383-400`（写 `scope=all, access=owner, isMyDevice=true, delegatedApproval=true, grantedBy="desktop-ui"`）；
  「撤销」`:402-412`（`revokeAll`）。

**控制面 HTTP**（给 DSH 桥 / 外部用，**只有这条会通知手机**）：`ControlPlane.kt:213-214` 路由
`GET/PUT /v1/agent/grants`，处理体 `:868-980`；其中 `grantReply` `:940-972` 才发 `agent-grant` 帧。

**手机端**：工作区详情 → 「授权管理」按钮（`feature/agents/WorkspaceDetailView.kt:425-426`，资源 `workspace_grant_mgr_btn`）
→ `WorkspaceGrantDialog` `:680-690,698-780`：**只读列表**，展示 `state.agentGrants`（我的设备 / 群主 / 协同管理员 / 协作者 / 原样 state）。

### 2.4 ⚠ 两个会影响你真机操作的代码事实（必须先知道）

1. **桌面面板批准不会主动通知手机**：`DesktopTrustedDevicesPanel.kt:243-253/383-400` 只写本机 store + 刷新界面，
   **没有** `sendAgentGrant`；发帧只在控制面 `PUT /v1/agent/grants` 的 `grantReply` 分支（`ControlPlane.kt:940-972`）。
   ⇒ 只点面板按钮，手机侧「授权管理」看不到任何变化。
2. **手机侧目前没有"申请授权"的界面入口**：发申请的唯一实现是
   `RelayControllerAgentComponent.kt:416-450 handleSendGrantRequest`（门控 `ag1`+`agr1`，`:424,431`），
   公开门面 `RelayController.kt:411-417`，**全仓没有 UI 调用点**；`pendingGrantRequests`（`RelayUiState.kt:112`）
   也没有任何 UI 消费方。⇒ 桌面的"待办申请"队列在真机上不会被手机填满。

### 2.5 真机上"把这部红米设成授信设备"的最小步骤

1. 先握手：让红米与桌面互相出现在节点列表（基线里 `GET /v1/peers` 已能看到 REDMI K80）。
2. 桌面 ⌘, → 「授信设备」→ 设备列表里找到 **REDMI K80** → 点 **「标记为我的设备」**（这一步 = 写台账）。
3. 想让它**在手机上可见**，再从桌面本机发一次决定帧（控制面只在回环，必须在 Mac 上执行）：

   curl -s -X PUT http://127.0.0.1:8788/v1/agent/grants -H 'Content-Type: application/json' -d '{"grant":{"nodeId":"05607a4e-9282-4b0a-8437-a931e18406ad","scope":"all","access":"owner","isMyDevice":true,"delegatedApproval":true,"grantedBy":"manual-test"},"grantReply":{"nodeId":"05607a4e-9282-4b0a-8437-a931e18406ad","state":"approved","scope":"all","access":"owner","isMyDevice":true,"delegatedApproval":true}}'

   （nodeId 用 `GET /v1/peers` 里红米那条；body 结构见 `ControlPlane.kt:861-862,885-924,940-972`。）
4. 桌面侧核对：`curl -s http://127.0.0.1:8788/v1/agent/grants`，或直接看
   `~/Library/Application Support/捎话 Tidings/agent-grants.json`（应出现该 nodeId 的 `access:"owner"`、`isMyDevice:true`）。
5. 手机侧核对：工作区详情 → 「授权管理」→ 该 Mac 那一行应显示 **我的设备**（`WorkspaceDetailView.kt:741`）且 state=`approved`（`:768`）。

### 2.6 解锁/限制了什么

- `requireRw=true`：`ro` 会被拒（`AgentGrants.kt:134-137`）→ 下发任务/入队/运行/取消等（`ControlPlane.kt:1048,1140,1194`）。
- `requireApproval=true`：只有 `isMyDevice` / `owner` / (`admin` 且 `delegatedApproval`) 能批（`AgentGrants.kt:138-142`）；
  手机侧对应判据 `ChatScreen.kt:475-479`、`AgentSessionDetail.kt:183-187`、`WorkspaceDetailView.kt:152-156`。
- `ro` 在手机侧会收起管理动作：`feature/agents/AgentWorkspaceBoard.kt:360-361`。
- policy=`request`/`byDevice` 且没有该 nodeId 的 grant ⇒ **agent 帧整段不投递**（沉默 = 未授权，`AgentGrants.kt:31-45,124-153`）。

### 2.7 失败长什么样

- 手机「授权管理」里始终 `workspace_grant_none`（`WorkspaceDetailView.kt:732-735`）→ 决定帧没到（多半只点了面板没走 `grantReply`）。
- `grantReply` 返回里带"未发送：对方未声明 agr1"（`ControlPlane.kt:959-961`）→ 手机版本不认识 grant 帧（cap 没声明）。
- `agent-grants.json` 被写坏 → 重启后策略退回 `request` 且面板带 `loadError`（`AgentGrants.kt:382-389`）；**这是 fail-closed，不是丢失授权后的静默放行**。
- 授权后仍收不到 agent 数据 → 检查该帧是否被 `allows()` 拒（policy/scope/access 三者对不上，`AgentGrants.kt:143-151`）。

---

## ③ 断 WiFi（只留移动数据）的预期

### 3.1 LAN 直连必然不可达（代码层面）

- 发现层广播目标是 `255.255.255.255`（`Discovery.kt:72-73`）+ 手动节点 + 已知在线节点（`:367-378`），
  全部是"局域网内地址"；手机切到运营商 `2001:db8:…` 前缀后，与桌面 en1 的 `2001:db8:…/64` **不同前缀**，且桌面在 NAT 后 ⇒ 无路径。
- 桌面 peer 表里的红米地址会在 `offlineAfter`（默认 90s，`DEFAULT_TIMINGS`）后被判离线（`Discovery.kt:193-198 registry.sweep`）。

### 3.2 是否存在 remote relay 通路

- 有，但**必须显式配置**：`RelaySettings.kt:195 remoteRelay`，入口 `RelayController.kt:217-219 setRemoteRelay`
  → `RelayControllerSettingsComponent.kt:197-219`（存盘 + 启动轮询），开机自动恢复 `RelayController.kt:576-578`。
- 客户端：`RemoteRelayClient.kt:16-53`（`/healthz`、`GET /v1/inbox?since=`、`POST /v1/send`），轮询 3s（`RelayControllerSettingsComponent.kt:235-248`）。
- 怎么配：扫一张 `{"type":"relay","url":…,"token":…}` 或 `relay://relay?url=…&token=…` 的码（`QrPairing.kt:98-106,113-133` → `RelayController.kt:843-856`）。
  **仓库里没有内置公网中继**——url 指向的是某个 agentNode 控制面。
- 现场基线：`settings.json` 里**没有** `remoteRelay` ⇒ 当前断 WiFi 就是彻底不可达。

### 3.3 正确表现（如实降级）

| 场景 | 应当看到 | 代码依据 |
|---|---|---|
| 未配中继、断 WiFi | 红米那条节点变**离线**（灰点/`common_offline`）；发出的消息标 **已排队**，不是"已送达" | `Discovery.kt:193-198`；`NodeOutbound.kt:45-46`（`QUEUED`=已入队等对方上线）；`RelayControllerMessagesComponent.kt:118`；`MessageUi.kt:9`；`RelayUiState.kt:41,444` |
| 输入栏提示 | 单聊显示「对方离线」类提示 | `ChatScreen.kt:749-754`、`MessageComposer.kt:281-283` |
| WiFi 恢复 | 队列自动排空、徽标从"已排队"翻成"已送达" | `RelayControllerMessagesComponent.kt:523-546,570-580` |
| 配了中继且健康 | 消息经 `/v1/send` + `/v1/inbox` 轮询进出 | `RelayControllerSettingsComponent.kt:235-248,260-280` |

**判"正常访问"的判据（断 WiFi 下）**：要么明确显示离线 + 排队（LAN 模式），要么中继健康且消息真的到达对端（`curl <url>/healthz` 为 2xx 且 `/v1/inbox` 有来件）。**不接受**"界面显示已送达/在线但实际没有对端 ACK"。

### 3.4 ⚠ 一个"假装在线"的具体缺陷（真机要专门看一眼）

`RelayController.kt:963-977`：只要 `remoteRelay.enabled && nodeId != null`，`refreshPeers()` 就**无条件**往 peer 表塞一条
`PeerUi(online = true, ip = remoteConfig.url)` —— 不看中继是否活着。
而唯一能判活的方法 `RemoteRelayClient.checkHealth()`（`RemoteRelayClient.kt:27-30`）**全仓没有调用点**。
⇒ 中继 URL 配了但服务挂了时，界面会显示那个"中继节点"在线。真机验证时请专门记录这一点（属于"假在线"，与诚实性纪律冲突）。

### 3.5 失败长什么样

- 断 WiFi 后消息仍显示"已送达" → 说明发送侧判据被绕过（应看 `DeliveryState`，`MessageUi.kt:9`）。
- 节点长时间不转离线 → `offlineAfter` 是 90s（省电档 270s，`DesktopPowerSaver.kt:37-42`），**等够再看**，不要 10 秒就判缺陷。
- 配了中继仍收不到 → 先 `curl -s <url>/healthz`；客户端所有失败都被 `runCatching{}.getOrDefault` 吞掉（`RemoteRelayClient.kt:27-52`），界面上不会告诉你。

---

## 附：三条最省事的"一眼判据"

1. **IPv6 是否真的走了**：`curl -s http://127.0.0.1:8788/v1/peers` 里红米那条的 `ip` 是不是 IPv6 字面量（基线是 `192.0.2.140`）。
2. **授信是否真的生效**：`curl -s http://127.0.0.1:8788/v1/agent/grants` + 手机「授权管理」对话框那一行是不是「我的设备」。
3. **断网是否如实降级**：消息气泡是"已排队"还是"已送达"，节点点是不是灰的。
