# luciola-only 下链路建不起来：入站帧源端口被当成监听端口

## 现象（A29 留下的未决）

双端同为 luciola 后，空闲 8.7 分钟在线没翻面、无「宿主设备睡眠」横幅，但
`linkTotal=0`、活跃链路 0。宿主心跳 `targets=32 sent=2 linked=0`，30 个目标全部
`[xport] send refused ... (no session / unpaired)`；手机侧
`[xport] out ... state=retrying detail=handshake failed: no candidate address reachable`，
`dialAttempts` 累计 85、`lastError=connect-timeout`。也就是「在线没翻」靠的是 presence
帧勉强通了，链路与会话其实一条都没建起来。

## 定因（两条独立缺陷，本文件只修第一条）

### ① 源端口污染（本轮修复）

`ReceivedDatagram.port` 是**发送方的源端口**。legacy 时代每个节点的 UDP socket 绑在
固定端口上，源端口就是监听端口，回包发回去天经地义；luciola 的会话键是
「地址:监听端口」（17878），而发送侧用的是临时端口。删 legacy 时只把发现引擎那条
登记路径归一了，其余入站登记路径原样留下：

| 路径 | 原来的行为 |
| --- | --- |
| `PresenceIngest.onDatagram` → `rememberSource` | 把 `host:临时端口` 塞进候选池 |
| `PresenceIngest.learnAddresses` | 档案声明的每条地址都配上**同一个临时端口** |
| `handleEntry` / `handleAlive` / `handleProfile` → `registry.touch` | 节点表当前端口被写成临时端口 |
| `handlePresence` → `touch` + 补发 `entry` | 续期与刷新都拨临时端口 |
| `scheduleAliveReply` | alive 回包发到临时端口 |
| `Messenger.handleReliable` → `touch` / `sendAck` | 可靠帧把端口改回临时端口、ACK 发不出去 |
| `NodeRuntime.start()` → `knownPeerStore` / `peerEndpointStore` | 上一版落盘的临时端口开机原样装回名录与候选池 |
| `LuciolaDiscoveryEngine.applyPeer` | **已**用 `profile.luciolaPort` 归一（唯一正确的一处） |

后遗症是三重的：出站全部拨到不存在的端点；`pinFor(host, port)` 查不到会话 ⇒
`no session / unpaired`；`PeerRegistry.touch` 的 `sameAddress` 判据含 `udpPort`，
端口不一致又让在线节点的 presence 被判「源地址漂移」整帧拒收。

这正是「同一值两处各写回退链迟早只改一处」的又一实例。修法不是再加一份回退链，
而是把端口解析收成 `sendingPortFor(profile, sourcePort)`（`PeerRegistry.kt`），
优先档案里的 `luciolaPort`、缺失或越界才回退源端口，三条登记路径共用。
老端档案没有这个键，行为一字不变。

### ② K1 拨的是一个被有意关闭的端口（未修，另立案）

宿主在 luciola 模式下 `legacyTcpListener = !fileChannelEnabled = false`，
`TransferManager` 不绑 17879（`NodeRuntime.kt:1366`，注释明确说「旧协议的入站在
这个模式下不存在」）。但 `RelaySettings.toProfile()` 仍广播 `tcpPort=17879`、
`tlsPort=17880`，且宿主 `caps` 含 `lnk1`（`advertiseAgent` 同开同关）。
手机 `LinkClient.linkTargets` 对私网地址一律按 K1 拨明文 `tcpPort` ⇒
`17879` 永远没人听 ⇒ `connect-timeout`。`linkConnected` 因此在 luciola-only 下
恒为 false，「在线判据只认链路」的豁免长期不生效。

两条候选修法都动到既有裁决，**未擅自改动，等拍板**：

- **A（契约对齐）**：luciola 端不再广播 `lnk1` / 不提供 K1 目标，把链路豁免改为认
  luciola 会话。要同步改 `CapabilityAndNotificationSettingsTest` 里「宿主必须声明
  lnk1」的断言，并重新定义「在线」判据。
- **B（保留 K1 入口）**：luciola 模式下仍绑 17879 给 K1 下行用（与
  `legacyTcpListener` 当前语义相悖，等于把刚删掉的明文监听开回来）。
- C：只保留判据与诊断，接受 K1 在 luciola 下废弃，靠 presence + 加密信道维持在线。

## 代码改动

- `core/.../transport/PeerRegistry.kt`：新增 `sendingPortFor()` 作为唯一端口归一入口；
  `candidatesOf()` / `allCandidates()` 在读口归一、`restoreOffline()` 在启动恢复时归一，
  落盘快照因此写回正确端口，旧版本留下的临时端口候选与名录端口都会自愈
  （启动顺序是候选先于名录恢复，所以资料缺席时原样返回，下一次读取即归一）。
- `core/.../transport/PresenceIngest.kt`：一次解析 profile 与发送端口，传给所有
  登记/回包/刷新路径；日志仍打印真实源端口（诊断需要）。
- `core/.../transport/Messenger.kt`：可靠帧的 `touch` 与 ACK 目标同口径。
- `core/.../transport/LuciolaDiscoveryEngine.kt`：删掉自己的那份回退链，改调共享函数。

不动协议字段、不新增可选键、不碰帧预算；不逐消费点打补丁，归一收在读口，
心跳 / 消息重试 / 发现引擎 / 落盘快照共用一份判据。

## 判据

`core/src/jvmTest/.../LuciolaSendingPortTest.kt` 七个用例：带声明端口的 entry
（节点表 / 候选池 / alive 回包三处都必须是 17878）、老端回退、越界值兜底、
心跳刷新按已知档案端口、可靠帧 ACK 不污染节点表、落盘旧候选在读口归一、
启动恢复的名录端口归一；后两者同时断言老端行为不变。

三次**反向探针**（每次改一处、跑完即恢复）：
1. `sendingPortFor` 改成恒返回源端口 ⇒ 5 个用例中 4 个判红（老端回退按设计仍绿）；
2. `normalizedCandidates` 改成原样返回 ⇒ 6 个用例中仅「落盘旧候选」那条判红；
3. `restoreOffline` 改回原样写入 ⇒ 7 个用例中仅「名录恢复」那条判红。
恢复源码后 7/7 绿。

## 真机复验

`measurements.json` 记了三轮读数：只修入站登记 → 再修候选池读口 → 再修名录恢复。
每一轮都换包 + 重启源码宿主，逐轮暴露下一条历史数据路径，最终装配是 22:56 包。

**已生效**：在线对端的当前端点与消息投递端口均为 `:17878`，宿主日志出现
`[send] peer=25222a45 ep=192.168.2.22:17878 acked=true ... regEp=192.168.2.22:17878`
（A29 时一条 `acked=true` 都没有，全是 `no session / unpaired`）；新登记的目标
全是 `ip:17878`。空闲期间 `[sweep] offline` 0 条、界面无「宿主设备睡眠」横幅。

**没生效的那部分，不假装修好**：心跳与手机拨号里仍大量出现临时端口
（宿主 86 处、手机 345 处）。这些是上一版落盘的历史身份与候选端点，其 profile
不含 `luciolaPort` —— 这个键由运行时 `selfProfile` 补，旧档案和扫码合成档案没有，
而三条归一都依赖它；且这些端点多数属于离线历史节点、host 常是本机 IP，
改对端口也发不出去。清理它们需要一条不依赖 `luciolaPort` 的独立判据
（涉及「什么样的端口算垃圾」的产品判断），本轮未动。

`linkTotal=0` / `dialLastTarget=...:17879` / `connect-timeout` 仍在，属上面 ②
的 K1 契约缺口，不是端口修复能解决的。逐项结论见 `ACCEPTANCE.md` A30。
