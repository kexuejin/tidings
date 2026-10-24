# 捎话 Tidings — 协议约束与理由

这份文档回答一个反复出现的问题：**协议里的某条规矩，为什么必须是这样。**

它是 `docs/relay-comment-audit.md`（注释盘查）抢救出来的 36 条 wire 级约束的落点 ——
那些约束原本散落在源码注释里，而源码注释会被重构冲淡、会随行号腐坏。凡是「看代码
推不出来、改错了却只有跑起来才发现」的知识，都该在这里。

每条四段式：**约束 / 为什么 / 违反后的症状 / 钉住它的测试**。

---

## 怎么读这份文档

- 只想知道「这个字段能不能有默认值」→ 看 §4 的 wire 级编码规则。
- 改任何 `core/protocol/` 下的代码前，至少读完对应族文件的 §。
- 觉得某条规矩「太严格了，顺手放宽」→ 先看它的**症状**段，那是它存在的原因。

代码里的 KDoc 会用锚点指回来，例如「见 `PROTOCOL-RATIONALE.md#group-no-field-whitelist`」。
锚点就是下面每条的小标题，去掉空格转小写。

---

## 1. 群消息：严格与不严格并存（`CodecGroup` / `CodecMsg`）

### group-no-field-whitelist

**约束**：`validateGroup` **刻意没有**字段白名单。

**为什么**：上游 TypeScript 那边不调 `hasOnlyKeys`，我们单方面加严等于把新版本踢下线 ——
`description` / `announce` 两个字段就是这么在 v0.54 上线之后才加的。

**症状**：老端收到新字段的群档案 → 整份拒收 → 握手超时 `peer-unreachable`，且**本地日志里没有任何
"字段不认识"的线索**，因为拒收发生在档案校验层，比消息层更早。

**反面规则**：白名单是有的，但方向相反 —— 群**文本消息**的 key 集合是严格白名单，见
[group-text-strict-whitelist](#group-text-strict-whitelist)。

### group-text-strict-whitelist

**约束**：群文本消息的 `GROUP_TEXT_KEYS` 是严格白名单，多一个字段**整帧丢弃**。

**为什么**：群文本可以改的东西（正文、引用、表情、话题）都在这张表里，是协议**显式列出**的；
表外的键意味着对端发送了本版本不该发的东西。

**症状**：`recv` 回来了、对端日志显示"已送达"，但本端什么都不显示 —— 帧在 `decode` 阶段就被丢掉，
没有错误日志，因为「字段多了」被当成了正常的协议不匹配。

### hasonlykeys-semantics

**约束**：`hasOnlyKeys` 要求 key 集合**完全相等**（不是子集）。

**为什么**：它是白名单的执行者，子集语义会让所有"严格"的约束一起失效。

**症状**：同上，但表现为「某些帧能解、某些不能」，取决于对端多发了什么。

**钉住它的测试**：`neg-group-text-extra-field`（黄金向量对拍，上游 TS 的向量库里有这一条）。

---

## 2. wire 级编码规则（`Codec.wireJson`，门面里唯一实例）

### encode-defaults-false

**约束**：`encodeDefaults = false` ⇒ **必须上线的字段一律不给默认值**。

**为什么**：等于默认值的字段**不会输出**到线上。一个"必须存在"的字段若给了默认值，
在它恰好等于默认值的那次发送里就会从帧里消失，对端按缺失处理。

**症状**：功能在开发机上永远正常（默认值很少被显式设成那个值），上线后偶发丢字段，
且复现需要构造出恰好等于默认值的数据。

### explicit-nulls-false

**约束**：`explicitNulls = false` ⇒ 值为 null 的字段整个键省略。

**为什么**：`Envelope` / `Profile` 的所有可选字段都靠这条保持线上帧短小。老端遇到不认识的
键会走白名单比对（见 [group-text-strict-whitelist](#group-text-strict-whitelist)），
所以"多写一个 null"和"多写一个新字段"对老端是同一件事。

**症状**：帧被对端整帧丢弃，且本地看不出原因。

### wirejson-single-instance

**约束**：`wireJson` 全仓**唯一一份**，写在门面 `Codec` 里。

**为什么**：多写一份 `Json { }` 就意味着配置漂移（`encodeDefaults`、`explicitNulls`、
`ignoreUnknownKeys` 任一处不同步）。

**症状**：**部分**消息解不出 —— 取决于哪条路径用了哪一份，编译期毫无线索。
所以门面 KDoc 里明写「谁都不许写第二份」。

---

## 3. 载荷校验的取值判据

### text-limit-in-utf8-bytes

**约束**：文本上限按 **UTF-8 字节**算，不是字符数。

**为什么**：上线的数据是按字节限的（中文 3 字节）。用 `String.length` 判会让中文超限三倍。

**症状**：中文长文本被对端拒收，本端看起来"发送成功"（超限判断发生在对端）。

### intof-strict

**约束**：`intOf` 只认 JSON number 且为整数值；`JsonNull` 与字符串一律返回 null。

**为什么**：对端可能发 `"123"` 或 `null`，若当数字收下就等于替对方做了类型提升，
而这些字段参与协议版本判断。

**症状**：seq 号变成 0 或 -1，增量拉取从头再来（表现为重复消息）。

### group-ref-pairing

**约束**：群引用一致性 —— 有 `groupId` 就必须有**非负** `groupRev`，反之亦然。

**为什么**：两字段是配套的，缺一半意味着对端在描述一个我们无法定位的状态。

**症状**：群消息能收但在 UI 里找不到对应会话，消息消失且无日志。

### pk-strict

**约束**：`validatePk` 的 key 集合（`PK_KEYS`）严格比对。

**症状**：同上（PK 消息整条消失）。

---

## 4. 文件传输的帧结构

### file-ctl-offer-key-order

**约束**：`FileCtlOffer` 的 **key 顺序照抄上游 TS**（15 个键），顺序即线上键序。

**为什么**：老端按 `hasOnlyKeys` 严格比对 key 集合，而部分老实现按位置解析。

**症状**：**重构时把键"顺手排序"** ⇒ 只在真机上出现（本端能自洽），帧被老端拒收。

### tcp-frame-must-be-wrapped

**约束**：裸信封会被对端 `decodeTcpFrameObject` 白名单拒掉，必须包成
`{"type":"msg","envelope":…}`。

**为什么**：TCP 帧有外层信封，与 UDP 裸帧不同。

**症状**：对端日志显示收到数据但什么都没解出来。

### tcp-frame-strict-keys

**约束**：`decodeTcpFrameObject` 每种帧都用 `hasOnlyKeys` 严格比对。

**症状**：帧被静默丢弃。

### wait-only-to-tw1

**约束**：`wait` 帧**只能发给声明了 `tw1` 能力的对端**。

**为什么**：老端遇到不认识的帧类型**直接断链**，不是忽略。

**症状**：与一台老版本节点传大文件 ⇒ 连接直接断开，且错误只出现在对端。

### never-err-on-wait

**约束**：**绝不能对 `wait` 回 `err`**。

**为什么**：续传时接收方先发 `wait`；回 err 会让它在 `awaitPullOk` 收到 `peer:busy`，
整条续传失败。

**症状**：小文件能传、大文件永远停在 0%，且**重试多少次都失败**。

### wait-heartbeat-after-stream

**约束**：**`wait` 心跳只能在裸流发完之后开**。

**为什么**：数据面是「`pull-ok` 后紧跟 len 字节裸流」，插在中间的心跳帧会被当文件内容吃掉，
后面全部错位。

**症状**：传完的文件哈希不匹配；因为前面的字节"看起来对"，错位发生在大文件后半段。

### resumable-part-is-offset

**约束**：续传坐标系 —— `<final>.part` 已存在的字节数**就是** offset；
先整读一遍喂哈希，再从 offset 续拉。

**为什么**：对端按 offset 续传，本端必须知道"已经有什么"。

**症状**：每次续传都从头重拉（表现是进度条反复回到 0）。

### prehash-two-paths

**约束**：`offset > 0` 时的预哈希有两条路径，差别**只有一个原因**：
老端不能收 `wait`。

**为什么**：tw1 端先发 `wait` 把对端 15s 握手空闲推到 60s 再预哈希；
老端会按 15s 超时踢人，所以**先断开、预哈希完再重连**。

**症状**：对老端续传时连接被踢，日志是握手超时而非传输失败。

### part-suffix-shared

**约束**：`.part` 后缀是**跨模块共享的字面量**，清扫侧 `sweepStaleParts` 必须用同一个。

**为什么**：两处各写一个就变成"清扫扫了个寂寞" —— 下载目录无限增长。

**症状**：删掉的文件在磁盘上留下 `.part`，长期占空间；磁盘满后所有传输失败。

### cancel-keeps-part

**约束**：**取消不删 `.part`**（决议 #211），留给下次续传。

**为什么**：用户点取消常常是"先不传了"，不是"这条不要了"。

**症状**（若删了）：用户取消一次大文件传输，下次要重头开始。

### pull-exception-reasons

**约束**：`PullException.reason` 与上游 `pullTransfer` 的 fail reason **逐字一致**
（`hash-mismatch` / `size-mismatch` / `path-escape` …）。

**为什么**：两端日志要能对上，排障时能直接 grep。

**症状**：日志里出现上游不认识的 reason，无法判断是哪一端判的失败。

### part-to-final-never-overwrite

**约束**：`.part` 转正不覆盖已有文件：`name.ext` → `name(1).ext` 试到 999，再时间戳兜底。

**为什么**：接收方的下载目录里可能已经有同名文件，覆盖等于丢用户数据。

**症状**：同名文件被静默覆盖。

---

## 5. 能力声明与身份

### tw1-gates-wait

**约束**：`tw1` 才允许收 `wait`。

**症状**：见 [wait-only-to-tw1](#wait-only-to-tw1)。

### fd1-is-only-a-request

**约束**：`fd1` **只是一个请求**，收端本地开关说了算，被拒时对方什么都不做。

**为什么**：能力声明表达的是"我准备好了"，不是"你必须接受"。

**症状**（若当成许可）：本地关掉直连后仍被要求直连，用户无法阻止。

### no-mobile-in-protocol

**约束**：**协议里没有 `mobile`**，手机端按约定伪装 `platform="linux"`；
agent 档案多声明 `CAP_AGENT`，桌面端一行都不用改。

**为什么**：能力声明是**可扩展**的 —— 新角色（手机/agent）通过多声明一个 cap 表达，
而不是让对端改代码识别新平台名。

**症状**（若加了 `mobile`）：桌面端会把它当未知平台，某些路径直接不显示。

### phones-must-not-declare-upd1

**约束**：**手机端 caps 不能声明 `upd1`**。

**为什么**：桌面端看到 `upd1` 就会推送自更新包。手机端没有那个自更新实现。

**症状**：桌面端向手机推一个它不认识的更新包，手机端报错。

### caps-for-agents

**约束**：agent 档案多声明 `CAP_AGENT`，而**桌面端一行都不用改**（见
[no-mobile-in-protocol](#no-mobile-in-protocol)）。

**症状**（若对端加类型判断）：每加一个角色就要改全部对端。

---

## 6. 端口与监听

### second-node-must-change-tcpport

**约束**：同机第二节点**必须**改 `tcpPort`。

**为什么**：默认节点通常占用 TCP `17879`，同机启动第二个节点未改端口会导致请求被路由到错误节点。

**症状**：撞上不是"收不到文件"，而是**投递错人** —— 更难查。

### never-port-zero

**约束**：**绝不能写 `copy(tcpPort = 0)` 表示"我没有 TCP"**。

**为什么**：`Codec.validateProfile` 要求 `1..65535`。声明 0 让对端**整份拒收**。

**症状**：握手超时 `peer-unreachable` —— 比端口撞车更难查，因为日志里看不出是端口被拒。

### broadcast-the-real-bound-port

**约束**：能守住的唯一不变量 —— **广播出去的端口必须是真的绑上的那一个**（绑定失败退到
系统端口，真实值读 `boundPort`）。

**为什么**：广播出去的是给对端连的，写 0 或写请求端口都可能连不上。

**症状**：本机明明在监听，对端却连不上。

---

## 7. 限额

### group-max-members-200

**约束**：`GROUP_MAX_MEMBERS = 200`（上游 TS 那个数；决议 #198 由 50 调至）。

**为什么**：**此处曾错写 256，是猜的，不是读出来的**。有值冲突时一律以 TS 为准。

**症状**：与上游不互通（群成员数到 200 就互相看不见）。

### scan-ranges-per-packet

**约束**：`SCAN_RANGES_PER_PACKET = 10`（CIDR + 元数据，保证 ≤ `UDP_MAX_PAYLOAD`）。

**症状**：扫网段的包超 MTU 被 IP 层分片/丢弃 ⇒ 扫不出结果，且没有任何错误。

### avatar-preset-range

**约束**：头像预设值域 —— `-1` = legacy 初始头像，`0..199` = 底色 × 动物。

**症状**：对端渲染不出头像（越界值没有兜底）。

---

## 8. 发现与节点表（`PeerRegistry` / `Discovery`）

三条规则是从上游 TS 读出来的，**别凭直觉改**：

### unknown-peer-needs-full-profile

**约束**：未知节点须有**完整 profile** 才进表。

**症状**：只发 `presence` 的节点永远不出现。

### online-peer-rejects-address-drift

**约束**：已在线节点**拒绝源地址漂移**。

**为什么**：防止有人伪造来源把已在线节点的流量劫走。

**症状**（若放宽）：每帧长文本都被当"地址漂移"拒收（这条与
[adr-ios-address-normalisation](#adr-ios-address-normalisation) 是同一类症状的两面）。

### updated-before-online

**约束**：事件顺序**先 Updated 再 Online**。

**为什么**：先 Online 会让监听方在处理 Online 时查不到 profile。

**症状**：刚上线的节点立即收到一条以它为目标的消息失败。

---

## 9. 可靠帧与去重

### ack-before-dedup

**约束**：可靠帧**无条件先回 ACK（重复帧也回）**，再去重投递。

**为什么**：去重表可能已过期或已被剪枝；对端没收到 ACK 就会一直重传。

**症状**：对端重传到队列被剪枝，一条消息都没投递成功。

### all-reliable-types-acked

**约束**：必须覆盖**全部** `RELIABLE` 类型。只处理 `msg` 会让
`file-ctl` / `group` / `avatar` / `update` / `share` 既不 ACK 也不投递。

**症状**：新加一种可靠类型忘了加进 ACK 分支 ⇒ 该类型在真机上"静默失败"，
且因为编译通过、测试不覆盖网络分支，很可能到发布后才发现。

### screen-skips-touch

**约束**：`screen` 例外 —— **不能先 `touch`**（否则把不可信来源变成当前地址）；
地址不符时连 ACK 都不回。

**为什么**：`touch` 会更新"当前对端地址"，而 screen 帧的来源校验必须先做。

**症状**：地址不符时如果回了 ACK，对端会继续重试；"对端的重试会自己停下来"是这里的设计意图。

---

## 10. 手机端的角色边界

### phone-is-a-leaf

**约束**：手机端是**叶子节点** —— 不做节点表散播（`peers`）、不扫网段（`scan-ranges`），
`UNHANDLED = {update, peers}`。

**为什么**：省电与权限（Android 后台限制）；且手机不需要发现别人。

**症状**：给手机加上这两个 handler 会让它在后台持续发包，耗电且招系统杀。

### screen-share-frames-not-persisted

**约束**：`screen` / `share` 帧**刻意不写 messageStore**。

**为什么**：否则它们会混进 `/v1/inbox` 的增量回放里成为没人认领的噪声帧。

**症状**：`agentNode` 的 inbox 回放越积越多，且回放里有一堆无法处理的历史噪声。

---

## 11. 平台相关的 wire 约束

### part-path-always-slash

**约束**：协议里的相对路径**永远用 `/`**。

**为什么**：`C:/` 是 Windows 绝对前缀，会让路径逃逸检查（`path-escape`）误判。

---

## 12. 传输安全与长连接（2026-10-18）

### lan-predicate-single-source

**约束**：判断一个对端是不是「局域网」**只有一条实现**：`IpAddressUtils.isLanAddress(peer, locals)`
（私网/回环 ∨ 链路本地 ∨ ULA ∨ 与本机某个地址**同 /64**）。拨号、拨号诊断、接受裁决、地址复查四处必须共用它。

**为什么**：这四处曾经各写各的。只认私网的那版会让家庭 LAN（IPv6 GUA、与本机同 /64）**一个目标都不拨**，
诊断还只说 `public-address-needs-tls` —— K1 被静默关掉，用户只看到「跨网连不上」。详见 `docs/public-transport-security.md` §11。

**症状**：判据一漂移，症状不在报错里，而在「明明在同一个网里却连不上」这种没有错误码的现象里。

### plaintext-never-off-lan（接受严格、复查保守，方向相反是刻意的）

**约束**：接受一条**明文**长连接时按 `!isSecure` 判（认不出安全就不许从公网明文接进来，fail-closed）；
地址变化后的复查按 `isPlaintext` 判（认不出明文就**不摘**，fail-safe）。

**为什么**：两个方向相反对应两种代价。接受侧错放的代价是「公网上出现明文」；复查侧错摘的代价是
「把一条合法连接反复摘掉、两端互相重拨」（task-40 真机实测：每摘一次手机就重拨一次）。

**症状**：复查侧写严了会看到 `close=recheck` 反复出现而链路建不稳。

### tls-never-falls-back-to-plaintext

**约束**：TLS 验不过（没证书 / 没期望 pin / pin 不一致）⇒ **拒**，绝不退回明文。

**为什么**：`peerPinAccepted` 的三条 fail-closed 是公网段的全部安全依据；「验不过就退回明文」等于把
公网流量降级成明文，而且用户完全看不出来。

### tofu-must-be-diagnosable

**约束**：TOFU 首次固定必须**记录来源**（`pairing` / `tofu`）并落盘（`stores/peer-pins.json`）；
诊断里能读到 `pinSource`；旧文件没有该键时一律读成 `unknown`，**绝不假装 `pairing`**。

**为什么**：TOFU 有首次使用竞态（谁先答谁被固定），它作为降级路径可以存在，但**不能变成新的静默**。

**症状**：读不到来源时，一台「其实还在 TOFU」的设备看起来和正常配对完全一样。

### external-pairing-needs-confirmation

**约束**：来自 App 自身 UI **之外**的配对入口（`relay://` / `relay-pair://` 深链、debug 的
`TEST_INJECT_QR` 广播）只登记、不落盘；用户确认才写节点/候选池/pin，**拒绝零副作用**。

**为什么**：外部输入的密钥一旦存下就是**信任根**，属于不可逆的信任建立 —— 用户点了一条链接 ≠ 同意固定一枚密钥。

**症状**：不给这条闸门，设备上任意应用（debug 包里 adb 谁都能发广播）都能让本机与它控制的节点配对。

### no-confirm-broadcast-bypass

**约束**：**不**提供「确认广播」这类自动化后门；测试与 e2e 也要**真的点**那个确认框。

**为什么**：debug 包里能发注入就能发确认，后门一开闸门立刻变成摆设。点得通 = 真实用户点得通，顺带把闸门也测了。

### flush-before-close

**约束**：关连接前必须**排空**：`flush` → `channel.close()`（让写泵读到 EOF 把剩余字节写完）→ **等泵退出** → 关 socket。

**为什么**：函数返回 ≠ 字节已发出。第一版是「关通道 → 取消泵 → 关 socket」，跨网大文件**尾部总丢一段**
（客户端读到一半拿到 `PullException: closed`），真机表现就是「大文件总是差最后一段」。

### cap-list-single-source

**约束**：每新增一个能力位，必须能指出**唯一真源**，或让每一份清单都被测试钉住。

**为什么**：今天两次同形状事故（`lnk1`、`att1`）都是「代码支持、只加进了某一份清单、线上永远不生效」。
**症状**：表现是「对端说我们没这个能力」，而本端代码里明明有实现。守卫见 `CapabilityDeclarationTest`（core 台账）
与 `CapabilityDeclarationSourceTest`（源码级枚举）。

### keepalive-idle-only-when-quiet

**约束**：有数据往来时**不发心跳**；保底 Ping 兜底（心跳 15s、45s 判死 ⇒ 判死 > 2×心跳）；Doze 用
`isDeviceIdleMode` 判，**不用息屏**。

**为什么**：息屏时 NAT 映射往往还活着，为了省电拉长心跳反而更容易被中间设备判死；反过来，一直在
发心跳会让「有流量」这件事失去信息量。

**症状**：Windows 节点发来的路径被拒收（表现为 `PullException` 的 `path-escape`）。

---

## 身份恢复 L2 为什么是 fire-and-forget（Q5/Q11）

`recovery-req`/`recovery-data` 的传输语义来自两条失败形态的推演：**可靠重传**要求对端
认识这张帧并永远回 ACK——旧版本对端静默丢帧，队列就会无限重试到剪枝，恢复方还以为
「在途」；**水位增量**需要有序游标，而消息 id 是 UUID、时间戳有时钟偏差，自造水位
就是再造一种「没收到」。所以首版裁决：请求用「可靠但不排队」的 ACK 窗口止损，
数据全量重发、按消息 id 幂等合并——重复无害、丢失不追。安全边界复用现有 TLS 双向 pin：
恢复方持有旧私钥本身就是认证（§5.5），不需要新握手。
