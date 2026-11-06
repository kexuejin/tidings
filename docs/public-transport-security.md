# 公网直连的传输安全：设计与分期（task-13）

> 状态：**设计稿，待裁决**。本文件只描述方案与分期，不改任何线上协议。
> 参考实现：`/workspace/src/third_source/DS-Bot-App`（Apache-2.0）——
> `ds-bot/src/mobile/ip-address.js`、`local-certificate.js`、`local-https.js`。
> 相关任务：task-12（配对码带公网 IPv6，进行中）、task-13（本文件）。

## 0. 结论速览（TL;DR）

1. **证书/pin 只放在"有连接"的那一层**：第一阶段（P2）只保护 **TCP**（文件、长文本、
   屏幕帧、大 agent 帧）。UDP 没有可移植的 TLS（DTLS 在 KMP 三端不齐），
   广播/组播发现**永远不可能**用 TLS 做身份认证。
2. **lead 已裁决（2026-10-18，见 §10）**：不接受"P2 期间公网对端还走明文 UDP 短文本"——
   公网对端的内容**只允许经 TLS 的 TCP 通道**（文本也走 TCP），UDP 只用于发现且不承载公网内容。
   所以 P2 的范围比"只加一层 TLS"大：要改 `Messenger` 的路由策略。**局域网**仍可走明文 UDP
   （不得让现有 LAN 路径失效）。
3. 让 UDP 在**公网**也保密，正确做法不是 DTLS，而是 **P3 的应用层 AEAD**：复用 TLS 握手
   （或一个 Noise 式握手）派生出的会话密钥，对 UDP 载荷做加密+完整性。P2 之后公网已无明文内容，
   P3 的价值主要是"回到低延迟的 UDP 通道也能保密"。
4. 现在**跨公网完全是明文**：没有 TLS、没有 AEAD、没有 HMAC（`core` 里只有
   `sha256Hex` 做哈希，见 `core/src/commonMain/kotlin/app/relay/core/crypto/Sha256.kt`）。
   `from` 是自称的、ACK 不认证、消息可被读取/篡改/注入。
5. **第一步（P1，可立即做且零风险）**：把 pin 变成一个**有严格校验的值类型**
   （`sha256:` + 64 位 hex）+ 存储位 + 能力位，**不碰传输**，LAN 明文路径逐字节不变。
   其中"配对载荷带 pin / 解析"**已经存在**（见 §7），P1 补的是"校验 + 存储 + 协商位"。

## 1. 现状：哪些是明文，暴露面多大

| 通道 | 端口 | 内容 | 今天是否加密 |
|---|---|---|---|
| UDP（单播+广播/组播） | 17878 | `presence`/`entry`/`alive`/`profile` 发现；**1:1 短文本 `msg`（≤800 字符）**；ACK；群发（逐成员单播） | **明文** |
| TCP | 17879 | 长文本回退（`{"type":"msg"}` + `msg-ack`，见 `TcpFramedChannel.kt`）；文件传输 `offer/pull/done`（断点续传 offset）；屏幕帧；大 agent 帧 | **明文** |
| HTTP | 8788 | 桌面 agentNode 控制面（回环为主） | 明文（回环） |
| 公网中继 | 用户配置 URL | `RemoteRelayConfig{url,token}`，走平台 HTTP client | 取决于 URL 是否 https；**与本任务的"直连"是两条路** |

关键事实（都在代码里可查）：

- 帧格式：UDP 是裸 UTF-8 JSON 信封；TCP 是 `4 字节大端长度 + UTF-8 JSON`（`TcpFrame.kt`）。
- 可靠投递：`Messenger`（`core/.../transport/Messenger.kt`）UDP 首发 + ACK，pending 键
  `peerId|msgId`；失败入队；对端上线 `flushQueue` **保序**补发；`resend=true` 只加在
  `text`/`group-text` 上。
- 群发：`NodeOutbound.sendGroupText` **一个信封、逐成员单播**（没有组播地址）。
- 断点续传：TCP `pull` 带 `offset`，是**应用层**的续传语义，与连接是否 TLS 无关。
- 分页：`agent-session-page` 请求/应答（`agp1`）走同一套帧；`since=` 增量拉取同理。
- 扩展点：`caps` 是官方协议扩展点——"数组 ≤16 项、每项 ≤16 字符"，**未知键不报错**
  （`MessageTypes.kt:143-153`）。这就是能力协商该用的地方。
- 配对载荷：`QrPairing.kt` 已经能生成/解析 `pin`/`tlsPin`（§7）。

## 2. 参考实现（DS-Bot）逐条对照

| DS-Bot | 做法 | 捎话的差异 / 能否照搬 |
|---|---|---|
| `ip-address.js` | IP→DER 字节；IPv6 分类 `fc00::/7`=private、`2000::/3`=global，link-local 丢弃 | 已有 `IpAddressUtils.getIpv6Scope`（task-12 在用），可复用 |
| `local-certificate.js` | 手写 X.509（EC P-256）；SAN=`iPAddress`，含 127.0.0.1/::1/本机全部地址；**私钥持久化**，证书在"快过期或 SAN 缺当前地址"时用**同一把密钥**重签；`tlsPin=sha256:SPKI` | **可照搬**：DER 拼装是纯字节逻辑，能进 commonMain；密钥持久化保证重签后 pin 不变 |
| `local-https.js` | 候选排序（公网 v6 > 私网 v6 > v4；docker/tun/utun 扣分）；HTTPS **绑定到所选地址**；URL=`https://[v6]:port` | 排序思路可复用；**捎话是双向的**，不是"手机连主机的单向服务"（见 §4） |
| 二维码 `{url,code,serverId,tlsPin}` | 手机直连 + 证书固定 | 捎话的 QR 已有 `pin`/`tlsPin` 键与候选池，结构兼容 |

## 3. 决策一：证书/pin 放哪一层

### 候选方案

- **A. 只保护 TCP**（文件/长文本/屏幕/大 agent 帧）。UDP 保持明文。
- **B. TLS 覆盖 UDP 也要**：要么 DTLS，要么把所有内容改走 TCP。
- **C. TCP 走 TLS + UDP 走应用层 AEAD**（P2=A，P3=C 的后半段）。

### 取舍

| | A（TCP-only） | B（DTLS / 全 TCP） | C（TCP TLS + UDP AEAD） |
|---|---|---|---|
| 可移植性 | 高（TCP TLS 在 JVM/Android 成熟；iOS 见 §8 风险） | **低**：Ktor 无 DTLS；iOS/Android/JVM 三端 DTLS API 不齐 | 中：AEAD 可以纯 commonMain（如 ChaCha20-Poly1305/HMAC），但握手与密钥管理要自己写 |
| 覆盖内容 | 文件/长文本/屏幕/大帧（**数据量最大、最敏感**） | 全部 | 全部 |
| 对发现的影响 | 无（发现仍明文，它本来就只广播资料） | 发现无法 TLS，仍需例外 | 同 A |
| 短文本/群发 | **仍明文** | 覆盖 | 覆盖 |
| 与现有协议改动量 | 小（只在 TCP 连接上加一层） | **大**（UDP 主路径重写） | 中 |

**裁决结果：A 为骨架 + "公网内容不得走 UDP"这条策略**（lead 2026-10-18）。
- UDP 广播/组播是"我还在这里"的公开喊话，**不可能也不需要**对未知邻居做证书认证；
  TLS 是面向连接的，硬套上去只会得到一个更弱的"机会式加密"。
- 内容里真正走 UDP 的是**短文本**（≤800 字符）与**群发**。既然不让它们走明文 UDP，
  P2 就要把**公网对端**的这些内容改走 TLS/TCP（文本也走 TCP）——这会让 P2 的范围变大，
  但方向正确：否则"公网直连安全了"是假话。
- **LAN 例外**：局域网内维持今天的明文 UDP 路径（要求不得让现有 LAN 路径失效）；
  "公网 vs LAN"的判定用候选地址类型（`public_v6` / 非私网地址）。
- C 的密钥来源可以复用同一套身份：先用 TCP/TLS 完成**双向认证与密钥协商**，
  再把派生的会话密钥用于 UDP 的 AEAD（每个 peer 一条密钥，按 msgId 防重放）。

## 4. 信任模型：谁验谁

DS-Bot 是"主机服务、手机连接"的单向模型，**server-auth TLS 就够**。捎话不是：

- 两端都会主动发起（`sendGroupText` 逐成员单播、`sendText` 双向）；
- 文件传输的 `TransferServer` 在**接收方**监听，TLS 角色是**每条连接**决定的，不是每个节点；
- 群发是 N 条单播，每条都要独立认证。

**已裁决：双向固定（mutual pinning）**（lead 2026-10-18）——每个节点持有自己的静态 EC P-256 密钥；
配对时**互相交换 SPKI pin**；每条 TLS 连接双方各出示自己的证书、各自校验对方 pin。
这样"谁在跟我说话"与"我发出去的东西只有他能看"同时成立。

退路只作为**明示的降级**：若 iOS 的 client-cert 接线真的过不去，才退到 server-auth，
并且必须在 UI/文档里写明"主机到手机方向的推送在传输层未被认证"——不能悄悄降级。

**pin 的对象是 SPKI 而不是整张证书**：
- 地址变了、证书重签了，只要**私钥不变**，pin 不变 ⇒ 不需要重新配对；
- 私钥丢了/重装 = 必须重新配对（这条要写进用户可见文案与排障）。
- 格式固定 `sha256:` + 64 位小写 hex（严格校验，见 P1）。

## 5. 与现有协议的兼容边界

**总原则：信封格式（`Envelope` JSON）一个字节都不改，只在"字节流"外面加一层。**
这样 ACK/重传/离线队列/去重/分页/续传的逻辑全部不用动。

| 机制 | 现状 | 加 TLS 后 | 旧对端（没有 `tls1`） |
|---|---|---|---|
| 发现/在线 | UDP 明文 | **保持明文**（无连接，无法认证未知邻居） | 不变 |
| 1:1 短文本 | UDP 明文 | P2 不变；P3 用 AEAD | 明文 |
| 长文本 | TCP 明文 | P2 走 TLS，帧格式不变（`{"type":"msg"}`/`msg-ack`） | 明文 |
| 文件/续传 | TCP 明文 | P2 走 TLS；`offset` 在应用层，续传语义不变；每次新连接重新握手 | 明文 |
| 屏幕/大 agent 帧 | TCP 明文 | P2 走 TLS | 明文 |
| 群发 | 逐成员单播 | 每成员各自 TLS；**混合群**按成员能力分别发（旧成员那一路仍明文，泄漏面要如实告知） | 明文 |
| ACK/重传/离线补发 | pending=`peerId|msgId`；队列原样重放 | 完全不变（信封不变） | 不变 |
| 分页 `agp1` | 帧 | 完全不变（只换承载） | 不变 |
| 中继 | HTTPS+token（由 URL 决定） | 不在本任务范围 | 不变 |

**协商与降级**：
- 用 `caps` 加一个 `tls1`（安全：官方扩展点、≤16 项、未知键忽略）。
  现有 `MOBILE_CAPS` 8 项，加 1 项仍在上限内。
- 端口：**已裁决：新增 `tlsPort` 可选键，不做同端口嗅探**（lead 2026-10-18）——
  老对端发的是 4 字节长度前缀，TLS 记录以 0x16 开头，混在同一端口是事故。默认关闭。
- **默认策略（已裁决）**：公网对端 `required` —— 没有 pin 就**拒绝承载内容**；不用 opportunistic。
- **中继路径（已裁决）**：与直连同一套 pin（决策 ⑤）。
- **自动续签（已裁决）**：P2 就要做——私钥不变、只重签证书（快到期或 SAN 缺当前地址时触发），
  否则一年后大家集体连不上；轮换/提示 UI 仍在 P4。
- **有 pin 就 fail-closed**：手抖/中间人把 `tls1` 抹掉、或握手失败时，**不回退明文**
  （否则 pin 形同虚设），而是把这条消息标成"无法安全送达"。

## 6. 分期路线

| 期 | 交付 | 用户可见变化 | 验收 | 回滚 |
|---|---|---|---|---|
| **P1** | pin 值类型（严格校验）+ 配对时存储 + `tls1` 能力位（不生效）+ 文档 | 无（或"已记录安全指纹"只读展示） | 单测；LAN 明文逐字节不变；无 pin 的 QR 照常配对 | 删字段即可，无协议变化 |
| **P2** | TCP 表面 TLS（文件/长文本/屏幕/大帧）+ **公网对端内容只走 TLS/TCP（文本也走 TCP）** + 双向 pin + `tls1` + 新增 `tlsPort` + **证书自动续签（私钥不变）** + 中继同套 pin | "与这台设备的安全连接"；公网对端默认 required；LAN 行为不变 | 三端 spike（**已完成**，见 `docs/spike-kmp-tls.md`）；文件续传/ACK/队列回归；LAN 明文路径回归；续签后旧 pin 仍有效 | 策略改回 off = 今天的行为 |
| **P3** | UDP 载荷 AEAD（会话密钥来自 P2 握手）+ 密钥轮换/防重放 | "短文本与群消息也加密" | 抓包看不到明文；重放被拒；群发逐成员各自密钥 | 关闭 AEAD 回到 P2 |
| **P4** | 证书轮换/到期**提示 UI**（自动续签已在 P2）、重装重配对引导、审计日志 | 排障文案 | 手动改 SAN/时钟偏移用例 | — |

## 7. 最小可验证的第一步（P1）规格

**已经存在的 seam（不要重复造）**：
- `composeApp/src/commonMain/kotlin/app/relay/app/QrPairing.kt:37` `ParsedQr.LanPeer.tlsPin`；
- 同文件 `:65-69` 解析 JSON 的 `pin`/`tlsPin`；`:138-150` 解析 `?pin=`/`?tlsPin=`；
- 同文件 `:222-241` `multiCandidatePairingPayload(tlsPin=...)` 生成；
- 测试 `composeApp/src/commonTest/kotlin/app/relay/app/QrPairingTest.kt:191/208/215`。

**P1 要补的（按依赖顺序）**：
1. **`TlsPin` 值类型**（`core/.../crypto/TlsPin.kt`，本轮已实现，见提交说明）：
   `parse` 严格接受 `sha256:` + 64 位 hex（大小写归一、去空白）、`ofSpki` 由 SPKI DER 计算、
   `matches(spkiDer)`、`wire` 可直接进 QR。**不校验证书、不连网、不被任何路径调用**。
2. **存储位**（需扩 writeScope，建议 `RelaySettings.peerPins: Map<nodeId, TlsPin>` 或
   `ManualPeerEntry.tlsPin`）：配对成功时把 `ParsedQr.LanPeer.tlsPin` 存下来；
   **同 nodeId 只增不改**（防止别人用新 pin 顶掉旧 pin = pin 降级）。
3. **能力位**：`MessageTypes.CAP_TLS = "tls1"`（本轮**不加**，等 P2 一起加，避免空声明）。
4. **只读展示**：节点详情显示"安全指纹：sha256:abcd…"（截断）+ "未验证/已验证"。
   —— 需要改 UI 文件（不在本轮 scope）。
5. **不动传输**：`QrPairing.kt` 的 pin 解析保持现状（mac-fs 正在改候选选择，见 §9）。

**P1 验收（必须全绿）**：
- 无 pin 的旧 QR：配对流程与今天**完全一致**（回归 `QrPairingTest`）；
- 带 `?pin=` 的 QR：pin 被解析并通过严格校验；**乱格式 pin 只被丢弃，不让配对失败**
  （宁可少一个安全声明，也不要让用户配不上对）；
- LAN 明文路径逐字节不变（用现有 `RelayControllerGroupTest`/`e2e_messaging` 回归）。

## 8. 风险、回滚、"不做会怎样"

**技术风险（按严重度）**
1. **KMP 上的 TLS 不是一行代码**：仓库目前**没有** `ktor-network-tls` 依赖，`io.ktor.network.tls`
   只在部分目标可用；iOS 侧服务端 TLS 大概率要落到 Network.framework。⇒ P2 开始前必须先做
   **三端 spike**（JVM/Android/iOS 各起一个自签 TLS 监听并完成 pin 校验），
   spike 不过就不要动协议。
2. **自签证书生成**：参考实现用手写 DER + Node crypto。KMP 需要平台 EC 密钥生成 + ECDSA 签名
   + 可移植 DER 拼装；纯字节部分能进 commonMain，密钥与签名必须 expect/actual。
3. **时钟偏移**：证书 `notBefore` 必须回拨（参考实现 -5 分钟），否则设备时钟慢会直接握手失败。
4. **地址漂移**：公网 IPv6 前缀/隐私地址会变。SPKI pin 不受影响，但**SAN 必须含当前地址**，
   主机要能在地址变化时**用同一把密钥**重签；否则手机连上但证书校验失败。
5. **群里的旧成员**：混合能力群里，旧成员那一路只能明文。这是**信息泄漏面**，必须如实告知，
   不能因为"群是加密的"就默认全部安全。
6. **降级攻击**：没有 pin 时机会式 TLS 可以被中间人剥掉。有 pin 必须 fail-closed。

**回滚**
- P1：只加字段/类型，删掉即回到今天；无磁盘格式迁移（新增可选键，`ignoreUnknownKeys` 已开）。
- P2：策略开关 `transportSecurity = off` 精确恢复今天的明文行为；TLS 走**新端口**，
  关掉就是"端口不监听"。
- P3：AEAD 开关关闭 = 回到 P2。

**"不做会怎样"（必须写清）**
- 在**公网直连**（本任务的目标场景）上，今天的捎话是**完全明文**：同一路径上的任何人
  可以读走你的消息、文件、屏幕帧与 agent 帧，也可以**篡改或注入**——
  `from` 字段是自称的，ACK 没有认证，除了去重表没有任何防重放/防伪机制。
- 只做 P1（pin 存下来但不启用）**不改变任何暴露面**：它只是为后续分期留出可验证的接缝。
- 局域网默认也是明文；威胁模型不同（同网段可信度更高），但"LAN 就安全"是错误假设。

## 9. 与 task-12 的边界（已与 mac-fs 对齐）

- mac-fs（task-12）在 `QrPairing.kt` 改：候选**类型命名/选择**（`lan_v4`/`lan_v6`/`public_v6`）、
  IPv6 方括号形式、公网 v6 仅 `2000::/3`、同网段优先。
- 本任务（task-13）**这一轮不落 `QrPairing.kt`**；等 mac-fs 改完再加 pin 相关字段，
  且**只加可选键**（例如每条候选可带自己的 pin），不改 `type/ip/port` 三个的语义。

## 10. 已裁决（lead，2026-10-18）

| # | 问题 | 裁决 | 落点 |
|---|---|---|---|
| ① | mutual 还是先 server-auth | **mutual pinning**；退路只作为明示降级并写文案 | §4 |
| ② | 公网默认策略 | **required**（没 pin 就拒绝承载内容），不用 opportunistic | §5 |
| ③ | TLS 端口 | **新增 `tlsPort` 可选键**，不做同端口嗅探 | §5 |
| ④ | UDP 短文本/群发 | **不接受 P2 期间公网对端还走明文 UDP**：公网对端的内容只允许经 TLS 的 TCP 通道（文本也走 TCP），UDP 只用于发现；**LAN 明文路径不得失效** | §3 / §5 |
| ⑤ | 中继路径 | **同一套 pin** | §5 |
| ⑥ | 证书到期 | **自动续签放 P2**（私钥不变、只重签证书）；轮换/提示 UI 放 P4 | §6 |

**P2 前置门槛（已完成）**：KMP 三端 TLS 可行性 spike，见 `docs/spike-kmp-tls.md`。
结论：**P2 走 TLS 可行**（JVM 已实证双向握手；iOS 已实证带 TLS 的监听器；零新依赖、无需 Swift）。

## 11. 「局域网」的判据（task-53，2026-10-18 真机 + 审查）

**判据只有一条：`IpAddressUtils.isLanAddress(peer, localAddresses)`** ——
私网/回环（RFC1918、`127/8`）∨ 链路本地（`fe80::/10`）∨ ULA（`fc00::/7`）
∨ **对端 IPv6 与本机某个地址同 /64**。

四处**必须**共用它（曾经不一致，后果是把 K1 静默关掉）：

| 处 | 位置 | 作用 |
|---|---|---|
| 拨号 | `Link.kt` 的 `LinkClient.linkTargets` | 局域网 ⇒ 明文目标（K1）；否则 ⇒ 只有 TLS（K2） |
| 拨号诊断 | 同文件 `skipReason` | 与上面**必须一致**，否则诊断会把"没拨"的原因说错 |
| 接受 | `linkDenyDiagnostic`（`LinkServer.register` 调用） | 明文只允许局域网（fail-closed） |
| 复查 | `enforcePlaintextPolicy` | 地址变了之后**只**摘"自己声明是明文"且已不在局域网的那条（fail-safe） |

**为什么必须有「同 /64」这一条：IPv6 的 GUA ≠ 公网可达。** 中国宽带的家庭 LAN 常分配全局单播地址
（真机 `2001:db8:2020:cb44::/64`），按地址分类它"就是公网"，可它其实与本机同一段网。
只认 `isPrivateOrLocal` 时，这类网络里 **K1 一个目标都不拨**（诊断只说 `public-address-needs-tls`），
就算拨了也会在 accept 那一刻被拒成 `not-lan` —— K1 被静默关掉，而用户看到的只是"跨网连不上"。

**不放松的那一半**：真正的公网（既非私网、也不与本机同 /64）仍**必须** TLS + 双向 pin；
明文永不出局域网。取不到本机地址（空表）时只认私网那一半 —— 不做同前缀推断（保守）。

