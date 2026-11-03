# ADR — 捎话 Tidings 的真机缺陷史

这份文档记录**在真机上暴露过、且可能被人"顺手改回去"**的决策。
每条四段式：**症状 → 根因 → 修复 → 为什么不能改回去**。

与 `PROTOCOL-RATIONALE.md` 的分工：

| | 记什么 | 读者 |
|---|---|---|
| `PROTOCOL-RATIONALE.md` | wire 级约束（"这个字段能不能有默认值"） | 改 `core/protocol/` 的人 |
| 本文 | **只在真机上出现过**的缺陷史（"这个判据为什么不能用看起来更合理的那一个"） | 任何想"改进"这段代码的人 |

这类知识最容易丢：症状是"某台手机上偶发"，根因是"一行看起来多余的判据"，
没有记录的话下一个人会提出同一个"优化"。

---

## ADR-001 iOS 的回环地址没有前导斜杠

**症状**（2026-10-04 真机）：每一帧 TCP 长文本都被拒收，UI 上停在"已排队"。
`PeerRegistry` 里明明有那个对端，就是配不上。

**根因**：JVM 与 iOS 对回环地址的 `toString()` 不一致 ——
JVM 给 `/127.0.0.1:55106`，iOS Native 给 `InetSocketAddress(127.0.0.1:55106)`（无斜杠）。
不归一化则表里的裸地址与它**永不相等**。

**修复**：地址比对前归一化（大小写 + 前导斜杠）。

**为什么不能改回去**：这是"看起来完全等价"的两条路径，只有真机能分辨。
参见 `PROTOCOL-RATIONALE.md` 的 [online-peer-rejects-address-drift](#online-peer-rejects-address-drift)。

## ADR-002 必须解除 `profileConfirmationOnly` 封印

**症状**（2026-10-04 实测）：节点改了端口或能力之后，本端**永远按旧端口连**，且不接受新资料。

**根因**：漏掉了解除封印这一步，与上游 TS 的 `peer-registry.ts` 不一致。

**为什么不能改回去**：漏掉 ⇒ 封印永久 —— 一次失误造成的后果是不可逆的。

## ADR-003 `direct` 不是 `decline`

**症状**：挂起的邀请被删掉并向对方发 `Declined`，用户看到"对方拒收了"，
而对方正在等你接收。

**根因**：`onDirectRequest` 曾把 `direct` 当成 `decline` 处理。
触发条件是**对方声明了 `fd1` 且本端免确认开** —— 这个组合在真机上比正确路径更容易走到。

**为什么不能改回去**：`fd1` 只是请求（见
`PROTOCOL-RATIONALE.md` 的 [fd1-is-only-a-request](#fd1-is-only-a-request)），
把它当拒绝会让 UI 说谎。

## ADR-004 `pendingTransferIds` 为什么要对账

**症状**：UI 上出现一张永远不动的"待接收"幽灵卡。

**根因**：三份状态会不一致 —— 内存表重启即失、磁盘留下 `.part`、UI 卡片被持久化。
任何一份单独看都说"我在等"。

**修复**：启动时按磁盘与内存对账，把不再有对应 `.part` 的项收掉。

**为什么不能改回去**：这不是冗余检查，是三份独立持久化状态的必然结果。

## ADR-005 广播与单播是并列的

**症状**（2026-10-03 Pixel 6）：手动配过一个节点之后，本机再也不广播，
同网段再也发现不了它，而且它的回复全部进了排队。

**根因**：曾写成二选一（配过就不广播）。

**为什么不能改回去**：手配节点是为了连一个**不在同网段**的机器，
和"扫本机所在网段"是两个独立需求。

## ADR-006 `profileRevOf` 返回 -1 也算"对不上"

**症状**（2026-10-07 Pixel 6）：对端重启后再也发现不了。

**根因**：对方重启后只发 `presence` 不再发 `entry`；若"等它的 entry"就永远等不到。
`profileRevOf` 返回 -1（从没见过它的资料）必须当成"对不上"，同样要单播 `entry`。

**为什么不能改回去**：把 -1 当"相等"看着很自然，但 -1 的含义是"我不知道"，
而不是"我确认相同"。

## ADR-007 广播目标列表要能被单测钉住

**症状**：无。这是预防性决策。

**决策**：`broadcastTargets` 抽成纯函数，好让"广播永远在列表里"这条不变量
**被单测直接断言**。

**为什么不能改回去**：它一旦变回带副作用的函数，这条不变量就只能靠人记得。

## ADR-008 启动必须先订阅再开泵

**症状**：开机瞬间的 `alive` 广播会掉。

**根因**：`SharedFlow` 的 `extraBufferCapacity` 只是缓冲，**唯一**的保证是
订阅先于 bind 发生。

**为什么不能改回去**：把订阅挪到开泵之后看起来等价（反正有缓冲），
但那只在低负载时成立。

## ADR-009 `TransferServer.start` 的返回值必须留下

**症状**：端口没拿到时**永远没人知道** —— 没有日志、没有异常，节点只是不工作。

**为什么不能改回去**：返回值被丢弃是"看起来无害的清理"，实际是删掉唯一的诊断线索。

## ADR-010 Android 掉 WiFi 后的网络快照自相矛盾

**症状**：掉 WiFi 之后本端仍认为自己在局域网内，且**不会自愈**。

**根因**：快照里 `activeNetwork` 还在，但地址已清空。

**修复**：判据用 `localAddresses` 而非 `NET_CAPABILITY_INTERNET` ——
Tidings 只走 IPv4 广播/单播，"能上互联网"与"在局域网"不是一回事。

**为什么不能改回去**：`NET_CAPABILITY_INTERNET` 是最自然的写法，
且在大多数情况下给出正确答案。

## ADR-011 `DataDirectoryProvider` 是第一个必须真实实现的契约

**症状**：每次重启都变成一个新联系人（`nodeId` 变了）。

**根因**：`nodeId` 不持久化。

**为什么不能改回去**：这是"留空 = 内存实现 = 开发期最省事"的契约的代价。

## ADR-012 纠正收口在接口默认实现里

**症状**：真机上出现过两个"接口在、没人调"的缺陷。

**决策**：把纠正逻辑放进接口的默认实现，平台层**没有忘的机会**。

**为什么不能改回去**："每个平台各自实现一遍"读起来更干净，
但它把"忘了调用"变成一种可以静默通过编译的错误。

## ADR-013 iOS 局域网权限只能靠 Info.plist

**症状**：直接拒绝访问且**不弹提示**（用户看不到任何说明）。

**根因**：`NSLocalNetworkUsageDescription` + `NSBonjourServices` 缺任一项。
另外后台裸 socket 不被允许。

**为什么不能改回去**：这是平台行为，改代码改不动；
但**知道它必须在 plist 里声明**这件事，代码里看不出来。

## ADR-014 iOS 与 Android 的接收文件动作三点不同

**事实**：iOS 不需要 FileProvider、不需要"可分享目录"检查、必须主队列弹；
且**不许抛异常**。

**为什么诊断文案是 ASCII**：`:cjkLiteralGate` 分不清"用户可见文案"与"诊断日志"，
一律按字面量计数；把诊断改回中文会让门禁失败。

**为什么不能改回去**：这三点差异每次重写这个文件都会再错一遍，
因为它们在任何语言层面都看不出区别。

## ADR-015 screen 会话为什么要有 `Error` 相位

**症状**（旧）："对方拒绝"、"连接断了"、"我自己挂了"在屏幕上**完全一样** ——
以前失败也写 `Ended`，UI 一道守卫就把弹窗抹掉。

**为什么不能改回去**：那道守卫看起来是正确的防御（终态就关窗），
正是它掩盖了三者的区别。

## ADR-016 `isEnded` → `isTerminal` 的语义扩张，以及一次"理由写反了"的更正

**记录**：判据改名并扩张语义时，初稿写的理由（"否则守卫会把失败态误判成空闲"）
**是写反的**。

**为什么保留这段**：这是阻止下一个人重新提出**同一个错误论证**的唯一证据。
删掉它，下一个人会重新写出那个错误的理由，并且因为它读起来很有道理而通过评审。

## ADR-017 agentNode 控制面：为什么只绑 127.0.0.1

**决策**：控制面存在的理由是**隔离**（不与数据面混在一起）；
只绑 `127.0.0.1`；**默认不要 token**。

**为什么默认不要 token**：用户明确拒绝过"必须手写配置文件"的形态。
代价是本机任何进程都能访问控制面 —— 这是被接受的风险，不是疏漏。

**两条 agent 路由的约束**：对方没声明 `agn1` ⇒ 返回 409 并建议改用 `/v1/send`。

**`sessions` 台账必须与 `Main` 的入站订阅是同一个实例**，否则面板只看到一半。

## ADR-018 失败的屏幕会话终态必须留下

**症状**：见 [ADR-015](#adr-015-screen-会话为什么要有-error-相位) ——
三种收场在屏幕上完全一样。

**为什么不能改回去**：把失败当"已经结束"处理是 UI 里很自然的写法（弹窗该关了），
而这正是问题本身。

## ADR-019 后台进程不被发现：缺的不是 socket，是"还在前台做事"这句话

**症状**（真机）：应用退到后台后，进程活着、`UDP 17878`/`TCP 17879` 端口仍绑着、
系统也没冻结它（`isFrozen=false`），但它处于 cached 状态（`state: cur=CAC`）。
对端约 90 秒后按离线判定把它摘出 peer 表，消息全部积压进队列（实测一次积压 40 条）。
把它切回前台，对端**立刻**重新发现它、新消息当场到达。

**根因**：Android 会把退到后台的进程打成 cached 并收紧其网络，UDP 广播停发。
socket 还开着不代表它还能说话。

**修复**：`ConnectionKeepAliveService`（前台服务，`foregroundServiceType="remoteMessaging"`）
+ 设置里的「后台保持连接」开关（**默认开**，关掉真的 `stopService`）。

**为什么不能改回去**：删掉这个前台服务，或把开关默认改成关，就等于**默认收不到消息** ——
症状是"某台手机上偶发收不到"，根因是一句看起来多余的保活。这是信使应用的产品性错误，
不是省电优化。类型也不要用 `dataSync`：Android 15 起它每天累计 6 小时上限，到点被系统停掉。

## ADR-020 配对候选必须**剥掉** IPv6 的 `%scope`，不是丢掉整条地址

**症状**：配对码里一个公网 IPv6 都没有，只剩局域网 IPv4；而单测全绿。

**根因**：macOS 的 JDK 给**每一个** IPv6 地址都带接口后缀（`2408:…:7679%en1`），
不只是链路本地。按"见到 `%` 就丢"过滤 ⇒ 本机 5 个公网 GUA **一个都不剩**。

**修复**：先剥掉 `%scope` 再判作用域（`QrPairing.peerCandidateTypeOf`）；链路本地仍按
`fe80::/10` 挡掉。

**为什么不能改回去**：`%` 确实"看起来像"链路本地专属，但那只是常见的巧合。
丢掉整条地址是静默失败：码照画、扫得动，只是永远连不到公网地址。

## ADR-021 relay.db 的并发必须由应用侧串行化，不能交给 SQLite 的锁

**症状**：应用活着、控制面 HTTP 200、界面能用，但库被锁住 —— journal 冻结数分钟、
外部读全部 `database is locked`、多条协程卡在 `NativeDB.step`。

**根因**（两层，第二层是探针量出来的）：
1. `createMessageStore` 与 `createAgentStore` 各自开一条 `JdbcSqliteDriver` ⇒
   同一个库两条连接，建表/迁移还各跑一遍；
2. 即使补上 WAL + `busy_timeout`，真正的冲突是 **`SQLITE_BUSY_SNAPSHOT`**
   （读 → 别人提交 → 同一条连接再写）。SQLite 对这类局面**跳过 busy handler 立即返回**，
   超时值再大也没用。

**修复**：驱动**按库路径缓存**（每库每进程一条、迁移只跑一遍）并包一层
`SerializedAccessDriver`，把查询/执行/**最外层事务**全部串行化；每连接下发 WAL 与
`busy_timeout`。

**为什么不能改回去**：把并发交给 SQLite 的锁"看起来更省事"，但 `BUSY_SNAPSHOT` 这条路
让 `busy_timeout` 形同虚设，症状是偶发卡死而不是报错。

**一个必须知道的代价**：WAL 之下 `sqlite3 -readonly` 会报
`unable to open database file (14)` —— 这是 SQLite 的**文档化只读限制**
（只读连接不能写 `-wal`/`-shm`），**不是锁**。验证"库有没有被锁"要用普通连接跑 `SELECT`。
## ADR-022 产品级语汇裁决：界面只回答「人此刻需要知道什么」

**症状**：桌面端与手机端的界面里混进一批**工程语汇** —— 标题栏/聊天头部印 `ip:端口`、
输入框常驻「局域网直连」的路由说明、会话列表写「N 待发」与「，按最近一条消息排」。
单看每条都「信息更全」，合起来是把诊断面板摊在用户脸上：普通用户不知道
`192.168.1.1:49213` 是哪台机器，也不知道「UDP」意味着什么；而他要回答的问题只有两个 ——
对方在不在线、我这条消息发没发出去。

**裁决**（同一条原则的六个落点）：

| 位置 | 退场 | 留下 |
|---|---|---|
| 标题栏 / 聊天头部 | `ip:端口` | 「在线 · 最后在线」（`peerPresenceLine`）；地址只在详情栏与节点页 |
| 输入框路由提示 | 常态的「局域网直连」 | 只有改走 TCP（例外）与超限（发不出去）才说话 |
| 会话列表待发 | 「N 待发」文字 | 一颗时钟图标（精确条数点开会话看得到） |
| 会话列表副标 | 「，按最近一条消息排」 | 只报条数 |
| 气泡时间行 | 左对齐独占一行 | 右对齐、与投递标记同行（成熟 IM 的摆法） |
| 间距档位 | 继续新增档位 | 17 档封顶，新代码先选语义名 |

**根因**：原型（`prototype/relay.html`）是**设计意图的载体**，不是逐字契约。它把诊断信息
摆在头部，是因为画图时需要一个可见的占位；逐字搬进产品，等于把「画图时的脚手架」
当成了「用户要的东西」。时间行尤其明显：原型自陈「气泡只装话」，时间本来就是代码侧补的，
摆法从来不受原型锁定。

**为什么不能改回去**：每一条单独看都像「信息更全 / 更周到」，而且都能举出「某类用户可能
想知道」。这正是它们会被加回去的原因 —— 加回去不会让任何测试变红，只会让界面重新变成
诊断面板。判据不是「这条信息有没有用」，而是「它是不是此刻这个人要回答的那个问题」。

**落点**：代码里 11 处注释（9 个文件）引用本 ADR（`DesktopChatPane` / `DesktopChrome` /
`DesktopComposer` 两处 / `DesktopConversationWidgets` / `MessageComposer` /
`ChatBubbles` / `ConversationRow` / `ConversationsScreen` / `Spacing`），
且都**不再带日期** —— 一条结论不该看起来像「有时效的临时决定」。

## ADR-023 加密信道的帧上限不是 UDP 的 800B，且 gossip 不能等 ACK

**症状**（2026-11-03 真机）：两端 `[xport] ... connected/detail=sha256:...` 都正常、
`in trusted=true`，但 `[send] ... acked=false` 持续刷屏；宿主 `online` 被周期翻成
`false`，待发队列从 47 涨到 125 条、`left` 永不下降。手机侧只能看到 `scan-ranges`
（557B）帧，没有任何 `group`/`agent-event` 帧进入 Messenger。

**根因**（四个叠在一起，缺一条都不会好）

1. `SecureChannelTransport.senderOf` 用 `Codec.decode`（UDP 的 800B 上限）解
   加密信道的入站帧。luciola 信道是**流式**的（单帧 8MB），6KB 的
   `group`/`agent-event` 被判成坏帧 → 在进 Messenger 之前静默丢弃、不回 ACK。
2. `Messenger.onDatagram` 又用 `Codec.decode` 解一次 —— 即使 transport 放行，
   帧还是会在这里第二次被丢掉。入站解码必须「先 UDP、失败再 TCP」。
3. 大帧（>800B）走 `sendViaTcp` 回退 **17879 明文 TCP**。luciola 模式下对端根本
   没有 17879 监听器，于是**队列头的第一条大消息把整条队列永久堵死**。
   加密信道能承载 8MB 帧，`preferTransport` 时大帧必须走 transport。
4. `scan-ranges` 这类 gossip 用 `sendReliableNoQueue` 发送，内部走
   `sendAwaitAck` 等 UDP ACK；但接收端由 Discovery 的 gossip 通道消费，
   **永远不在 Messenger 的可靠分支里回 ACK**。每 5s 一次失败 + `flushQueue`
   失败时 `markOffline`，把对端周期性打成离线，进而拖死整个队列。

**修复**

- `SecureChannelTransport.senderOf` 与 `Messenger.onDatagram` 都接受 TCP 上限
  （`Codec.decodeTcp`；后者保留「先 UDP 再 TCP」以不放松 legacy 通道的校验）。
- `preferTransport == true`（luciola 模式）时，大帧不再回退明文 TCP，直接走
  transport（`sendUserMessage` / `sendReliableNoQueue` / `flushQueue` 三处）。
- `sendReliableNoQueue` 对**非 RELIABLE 类型**改为 `sendBestEffort`：gossip 本来
  就不该等 ACK。

**验证**：真机 A/B 同一对设备，修复前手机只收到 `scan-ranges`、宿主 `acked=false`；
修复后 125 条积压一次排空（`[flush] from=125 left=0`），`acked=true` 122 条、
`acked=false` 0 条，6410B 的 `agent-event` 与 1099B 的 `group` 都在 10–27ms 内 ACK；
手机发群消息 `acked=true in=39ms` 且宿主 `[msg] inbound len=16`。回归钉子：
`gossipFramesDoNotWaitForAck` / `largeFrameInTransportModeUsesTransportNotPlaintextTcp` /
`reliableFrameOverUdpBudgetIsDeliveredAndAcked`。

**为什么不能改回去**：这四处单独看都「更保守」（按 UDP 上限卡更安全、大帧走 TCP
更传统、gossip 也等 ACK 更可靠），但它们在流式信道上分别造成**静默丢帧、队列
永久堵塞、把在线对端打成离线**。判据不是「哪种更严格」，而是「接收端到底在哪一条
路径上回 ACK」——ACK 语义只存在于 `MsgTypes.RELIABLE`，流式信道不是 UDP。
