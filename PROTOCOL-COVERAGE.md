<!-- SPDX-License-Identifier: GPL-3.0-only -->

# 协议覆盖表：每一种帧，要么处理，要么写明不做

上游协议里一共有 **17 种信封类型**（`entry` / `alive` / `exit` / `presence` / `profile` /
`peers` / `scan-ranges` / `msg` / `ack` / `file-ctl` / `group` / `avatar` / `update` /
`share` / `screen` / `agent-session` / `agent-event`）和 **5 种 `msg` 的 kind**
（`text` / `group-text` / `recall` / `nudge` / `pk`）。

本仓在 PPE-1 上又扩了 **11 种**（`agent-queue` / `agent-run` / `agent-approval` /
`agent-cancel` / `agent-ack` / `agent-artifact` / `agent-workspace` /
`agent-grant-request` / `agent-grant` / `agent-grant-list` / `agent-session-page`）—— 合计 **28 种**，
就是下面这张表要覆盖的全集（`MsgTypes.KNOWN` 与它逐项对齐，由
`MsgTypeDispositionTest` 钉住"新增一种帧就要当场表态"）。

这份文档回答一个问题：**对端能发来的每一样东西，本端到底怎么处置？**

规矩是：**没有"没想过"这一档**。每一种要么有处理路径（写清在哪一层、哪一行），要么是
写明了理由的产品决策。这条规矩由
`core/src/commonTest/kotlin/app/relay/core/protocol/MsgTypeDispositionTest.kt`
钉住：`MsgTypes.HANDLED` 与 `MsgTypes.UNHANDLED` 必须构成 `MsgTypes.KNOWN` 的一个划分。

代码里的权威名单：
`core/src/commonMain/kotlin/app/relay/core/protocol/MessageTypes.kt` 的
`HANDLED` / `UNHANDLED`（理由写在 `UNHANDLED` 的 KDoc 里）。

---

## 一、会处理的（28 种）

> 数量口径 = `MsgTypes.HANDLED` 的元素个数（下面的表按**帧族合并**成行，所以行数少于类型数）。
> `HANDLED` 与 `UNHANDLED` 必须构成 `KNOWN` 的一个**划分**，由
> `core/src/commonTest/kotlin/app/relay/core/protocol/MsgTypeDispositionTest.kt` 钉住 —— 新增一种帧就要在那里当场表态。

> 2026-10-18 复核：本节标题原写「15 种」，是 agent 工作区/授权/分页与投屏/头像移入之前的旧数；表体内容当时已补全，只有数字没跟上。

| 类型 | 在哪一层处理 | 做什么 |
|---|---|---|
| `entry` | `core/.../transport/Discovery.kt` `handleEntry` | 入节点表；回 `alive`（带抖动与去重） |
| `alive` | `Discovery.kt` `handleAlive` | 入节点表并采纳全量资料 |
| `exit` | `Discovery.kt` `onDatagram` → `PeerRegistry.markOffline` | 立即判离线，不等 90 秒 presence 超时 |
| `presence` | `Discovery.kt` `handlePresence` | 续期；`profileRev` 与本地不一致时单播一条 `entry` 换回全量资料（节流：同一节点 10 秒） |
| `profile` | `Discovery.kt` `handleProfile` | 采纳更新的资料（不传 `confirmed`：采纳与否交给 `PeerRegistry` 的新旧判据） |
| `msg` | `composeApp/.../app/RelayController.kt` `onInbound` | 四种 kind 见下节；`pk` 不做 |
| `ack` | `core/.../transport/Messenger.kt` `handleReliable` | 结清投递状态、推进离线补发队列 |
| `file-ctl` | `core/.../transport/NodeRuntime.kt` → `TransferManager` | 文件与文件夹传输状态机 → 文件卡片（`RelayController.onTransfer`）与断点续传 |
| `group` | `RelayControllerMessagesComponent.kt:291`（`RelayController` 的逐类型内联分派） | 群元数据 `op:"info"`/`op:"need"`：进本地群列表、回快照、群会话管理 |
| `agent-session` | `RelayControllerMessagesComponent.kt:238`（cap `agn1`） | Agent 会话宣告 → Agent 控制台；四个可选键（`agentId`/`wsId`/`wsTitle`/`wsPath`，cap `aws1` 门控）持久化至数据库 |
| `agent-event` | `RelayControllerMessagesComponent.kt:242` | 会话事件 → 控制台，按 cursor 去重、`takeLast(200)` |
| `agent-queue` / `agent-run` / `agent-approval` / `agent-cancel` / `agent-ack` | `RelayControllerMessagesComponent.kt:246`–`:262`（cap `agq1`） | 队列任务 / 执行状态 / 审批请求与处置 / 取消 / 回执，独立分表跨重启留存；审批的处置结果由 `agent-ack` 推进，**未确认 ≠ 已送达** |
| `agent-artifact` | `RelayControllerMessagesComponent.kt:266`（cap `agn1`） | 只收**元数据**（`fileId` 指向已发过的 `file-msg`），不自动下载二进制 |
| `agent-workspace` | `RelayControllerMessagesComponent.kt:272`（cap `aws1`） | 工作区宣告 → 工作区看板；覆盖「工作区存在但零会话」场景（`declaredOnly`） |
| `agent-grant-request` / `agent-grant` / `agent-grant-list` | 收：`RelayControllerMessagesComponent.kt:280` / `:288`（cap `agr1`）；执行：agentNode `ControlPlane.resolveAgentTarget` 投递前过滤 | 设备申请 / 宿主决定 / 宿主回清单。四档状态（未申请/待批/已授权/已拒绝）+「申请查看」；**撤销不回收历史**（只拦未来投递） |
| `agent-session-page` | 发：`RelayController.kt:522` `requestAgentSessionPage`（cap `agp1`）；收后排队：agentNode `AgentSessionPageStore`；应答：宿主插件 | 设备向宿主要**一页**历史会话（`wsId`/`offset`/`limit`）。宿主用已有的 `agent-session` 逐条回填，触发点是按需打开工作区 |
| `avatar` | `RelayController`（cap `av1`） | 自定义头像获取与广播：`op:"get"`、`op:"data"`、`op:"miss"`，SHA-256 缓存 |
| `share` | 传输层 ACK，业务层**只有壳** | 共享文件柜：`share` ∈ `RELIABLE_TYPES` ⇒ 入站帧在传输层被 ACK，业务层不发 `share:list` |
| `scan-ranges` | `RelayControllerCidr` | CIDR 网段扫描探活与手动网段配置 |
| `screen` | 投屏与桌面镜像（cap `rv1`/`rvs1`） | 桌面捕获/推流与屏幕会话展示 |

> **2026-10-18 复核**：本表「在哪一层处理」这一列原先写着 `onAgentSession` / `onAgentEvent` / `onAgentArtifact` /
> `onAgentWorkspace` / `onGroupControl` / 各 `handleOnAgent*` —— 这些函数名**在代码里并不存在**（真实实现是
> `RelayControllerMessagesComponent.kt` 里逐类型的**内联 `if (envelope.type == …)` 分派**，行号已按今天的位置改正）。
> **待复核**：这一列的其余单元格（`Discovery.handle*` / `Messenger.handleReliable` / `TransferManager` 等）我逐个抽查过存在，
> 但**没有**逐格核对「做什么」的描述是否仍然准确 —— 需要时再按源码复核一遍。

## 二、`msg` 的 kind（5 种）

| kind | 处置 | 说明 |
|---|---|---|
| `text` | 收发都有 | 严格校验 |
| `group-text` | 收发都有 | 群文本是 `msg` 的一个 kind，**不是** `group` 报文。收端按 `groupId` 归到 `group:<groupId>` 会话、并把**发言人**存下来 —— 优先用载荷里的 `senderId`（设备可能在代别人说话：DSH 事件由桌面端转发进群，`envelope.from` 只是那台发送设备），没带才退回 `envelope.from`（`PROTOCOL-EXTENSION.md` §6.10），发端一个信封扇出 N 份逐成员单播（采用群聊 v0.3 简化策略：不做按成员回执）。`mentions` 里的本机 nodeId 与保留值 `"@all"` 都算"点到我了"，只影响提醒、不影响投递（协议 §3、`:176`、`:584`）；本端没有 @ 入口，从不产生 `mentions` |
| `recall` | 收发都有 | 自己 5 分钟内的文本**和媒体**都能撤回；媒体要求发出的 `offer` 带过 `msgId`，撤回时先取消进行中的传输再发 `recall`，本地隐藏原消息（不删用户已落盘的文件）。收到对端撤回 → 隐藏原消息、留一行提示；乱序到达会挂起等原消息入库 |
| `nudge` | 收发都有 | 仅单聊、可靠 ACK、**不入离线队列**；限流同一对端 60 秒 2 次、相邻 ≥15 秒。**收端**：限流通过后除了写本地提示行，还会发系统通知并把界面定位到该会话（`focusRequest`），静音时段内不打扰（`shouldNotifyNudge`）。通知本身**可点**（Android 挂 `PendingIntent` + `launchMode="singleTop"` + `onNewIntent`，点开直接落在那个会话；iOS/桌面收下参数但不生效）。已知未解决：应用在后台被系统冻结时戳不到（协议不允许排队，所以没有 ACK 就没有通知） |
| `pk` | 不做 | `Codec.validateMsg` 直接 `return false`（失败关闭），不放坏形状通过 |

## 三、协议层有意不处理的（2 种）

这 2 种属于**局域网 P2P 点对点基础协议层面的信封类型**。本端没有对外声明对应的 cap（如 `upd1`），所以对端不会按"你能收"的方式发过来。
**特别澄清**：协议层的 `update`（`upd1`）特指**局域网 P2P 桌面二进制安装包（.exe/dmg）分发中继**，与**本客户端自身的软件版本检测与升级（App Update）**是两回事；本客户端在应用层提供独立的版本检查与更新引导通道。

| 类型 | 协议层为什么不处理的理由 | 代价与平台定性 |
|---|---|---|
| `update`（局域网 P2P 更新源，cap `upd1`） | **协议机制**：局域网 P2P 分发桌面版安装包（.exe/dmg）。移动端为兼容原版伪装为 linux，若声明 `upd1`，局域网 Windows 电脑会强推桌面 exe 安装包，且移动端沙盒/App Store 严禁静默覆盖安装。<br>**应对**：应用自身的软件升级通过标准应用层通道（「设置」→「检查更新」与 Release 下载）实现，不依赖局域网 P2P 推送。 | 协议层不向别人推更新包，也不接受对端的桌面二进制包；客户端自身的版本升级走独立应用层通道。 |
| `peers`（gossip 节点摘要） | 手机端是叶子节点，为省电不参与全网洪泛扩散；入站摘要有显式分支丢弃。 | 节点表依赖 `entry`/`alive` 与 mDNS/扫码，不主动参与 gossip 多跳路由（技术上可单向被动吸收）。 |

**这两种都有显式代码分支**（`Discovery.onDatagram` 里 `MsgTypes.PEERS -> Unit`；
`RelayController.CHAT_INBOUND_TYPES` 只留 `MSG`，而 `update` 靠 `MsgTypes.UNHANDLED` 与核心层的 `handleReliable` 兜住）。
`group`、`scan-ranges`、`screen`、`avatar` 已移入 [HANDLED] 并在业务层闭环；`share` 也在
[HANDLED] 里，但它的依据只是**传输层会 ACK 它**（`RELIABLE_TYPES`）—— 业务层只有壳、入站帧被
`CHAT_INBOUND_TYPES` 丢弃，见 §三那一行与 §五。

## 四、能力声明（caps）

**声明的（`MsgTypes.MOBILE_CAPS` 常量 + `RelaySettings.caps()` 按平台能力追加）**：
`tw1`（传输排队/wait 帧）、`mrec1`（媒体撤回）、`agn1`（agent 会话帧）、`tbl1`（表格图片文字视图）
是常量里固定的四位；`rv1`/`rvs1`（投屏，见 §5 与 `ACCEPTANCE.md` 的 A17）与
`shr1`（共享文件柜，由 `isShareEnabled()` 决定）由 `RelaySettings.caps(remoteView, remoteShare)`
（`composeApp/.../RelaySettings.kt:203-214`）动态追加。`ag1`（身份）只有 agent 节点声明。
`tw1`/`mrec1` 各有一个设置开关，关掉即从 caps 里移除。
**实测**：手机实际广播的 caps 是
`["tw1","mrec1","agn1","tbl1","rv1","rvs1","shr1"]` —— 注意 **`av1` 不在其中**
（自定义头像至今不声明，见下面的不声明列表）。

`tbl1` 的**发送侧**也已支持：`POST /v1/send-file` 接受 `tableText`，并且按协议只向声明了 `tbl1`
的对端附带它（对端没声明回 409 `peer-unsupported`）。

**不声明的**（能力位键名以点对点协议规范为准：`docs/protocol.md` 里的 `grp1` 在实现里叫 `gr1`，
而文档里的 `img1` 在实现里根本没有这一位 —— 见 `README.md` 的「能力声明（caps）」一节）：
`gr1`（群子集已落地，完整 RBAC 含管理密码路径没做，见 §5）、`img1`（实现里不存在的键；图片仍作为
文件收，≤20MB 自动接收、气泡里有内联预览；**群聊图片上限 10MB**，超限退化成手动领取——协议写的是
"拒绝"，本端选择退化成手动领取，因为拒收会让一条本来能收的图彻底收不到）、`av1`、`upd1`、
`dp1`（只实现了应答半，没实现发起半）、
`fd1`（**只读不声明**：读对端的 `fd1` 决定要不要在 `offer` 后补 `file-ctl direct`，
自己不承诺免确认写存储）、`age1`（agent 事件流批量投递/游标续读，PPE-1 P1.5）。

> `shr1` **不在**这份「不声明」名单里：它的声明条件是「共享目录已配置」
> （`RelaySettings.isShareEnabled()`，`RelaySettings.kt:195-200`），配了就会随 profile 广播出去。但**协议侧只有壳**（见 §三）——
> 这是当前「声明了却没有对应实现」的一处已知不对等，列在 §五。

**投屏已从「不声明」移出**：`rv1`/`rvs1` 现在**都广播**（实现已落地并双向验收通过，见 §5 的投屏行与 `ACCEPTANCE.md` 的 A17）。实测手机 caps 为
`["tw1","mrec1","agn1","tbl1","rv1","rvs1","shr1"]`。声明与实现的联动点是
`RelaySettings.caps(remoteView, remoteShare)`（`composeApp/.../RelaySettings.kt:203-214`）：
只有投屏受支持时才追加这两位。

**2026-10-18 新增的五位**（都在 `RelaySettings.caps()` 里显式拼出，`composeApp/.../RelaySettings.kt:300-340`）：

| 键 | 谁声明 | 含义 |
|---|---|---|
| `lnk1` | **只有承载 agent 的主机**（随 `ag1`） | 本机可接受一条长连接。手机**不**声明（拨号侧是手机） |
| `att1` | 手机 | 能把 agent 事件的附件元数据渲染成卡片；发送方据此决定要不要在 `agent-event` 里带 `attachment` |
| `aws1` | 手机 | 工作区身份四键（`agentId`/`wsId`/`wsTitle`/`wsPath`）与 `agent-workspace` 帧 |
| `agr1` | 手机 | 授权回路三帧 + **投递前按策略过滤**（沉默 = 未授权） |
| `agp1` | 手机 | 认识 `agent-session-page`（向宿主要一页历史会话） |

> **守卫**：core 侧有一条台账测试钉住「每个 cap 常数要么被 profile 清单声明、要么在台账里带**理由**豁免（`fd1` 只读 / `ams1` 预埋 / `shr1`·`rv1`·`rvs1`·`av1` 由壳按条件声明）」，另有源码级守卫断言新加的 cap 必须同步进台账：
> `core/src/commonTest/kotlin/app/relay/core/protocol/CapabilityDeclarationTest.kt` 与 `core/src/jvmTest/.../CapabilityDeclarationSourceTest.kt`。

## 五、还没做的（明确列出来，不含糊）

| 项 | 现状 | 说明 |
|---|---|---|
| 内联预览与大图查看 | 图片与语音已有内联预览，**图片点击放大全屏查看已支持**（`ImageViewerDialog` 支持居中缩放、保存/分享导出）；**视频没有**（落回文件卡） | 视频需要解码器与首帧抽取，暂不引入大体积依赖 |
| 共享文件柜的协议侧（`shr1`） | **只有壳**：配置了共享目录就会宣告 `shr1`，但入站 `share` 帧在业务层被 `CHAT_INBOUND_TYPES` 丢弃（只回传输层 ACK）、本端从不发 `share:list`、界面条目是 mock、下载只弹横幅 | 桌面全功能节点支持该能力；本端要补齐浏览/拉取/权限校验才有意义。**当前是「声明了却没有实现」的一处不对等**（由 `e2e_share.py` 的壳契约断言钉住，见 §三） |
| 群文件（群图 / 群文件发送） | **已全面打通**：群文本、群图片与群文件收发均已实现，发送侧支持逐成员扇出广播、首个传输卡片归集至群会话、收端按 `groupId` 归集，UI 层附件入口与撤回完全可用 | 底层 `NodeRuntime.offerFiles` 支持透传 `groupId`/`groupRev`，`RelayControllerTransfers` 负责扇出与状态同步 |
| 群管理的其余部分 | 已做：建群、群列表/成员、群会话、按群归位、发言人署名、`op:info`/`op:need` 同步、角色矩阵（只有群主与管理员能改群，普通成员只能只增不减地邀请） | 未做：管理密码路径（`adminSecretHash`/`adminHint` 只透传）、群头像、群简介/公告编辑、owner/admin 的完整 RBAC 界面、离组重邀的按步重放。手机端没有群管理界面，把 `canApplyRemoteInfo` 那 130 行全搬过来，大半分支永远走不到 |
| 投屏接线（`rv1`/`rvs1`） | **已全面落地并双向验收通过**：`screen` 四个 op 逐字校验（`sessionId` 必须 UUIDv4、`accept` 的 `token` 必须 32 位小写 hex），TCP 四个帧与裸 JPEG 数据面全通；手机既能当 Sharer（MediaProjection 采集）也能当 Viewer（全屏渲染器 + 画质下拉）。实测广播 `rv1` 与 `rvs1` | 验收步骤见 `ACCEPTANCE.md` 的 A17。**已知未做**：桌面端 GUI 没有「发起投屏」入口（只能应答入站邀请），`permission-denied` 这条 reason 至今没有生产代码路径（失败一律发 `capture-failed`） |
| Agent 事件与会话持久化 | **已全面落地**：基于 SQLDelight 创建独立的 `AgentSessionEntity` 与 `AgentEventEntity` 分表，严格不污染聊天消息表（遵循 PPE-1 §9），重启后自动按 cursor 恢复并与内存去重合并 | `SqlDelightAgentStore` 提供跨平台多端持久化支持，`RelayControllerAgentSessionTest` 覆盖跨重启数据恢复验证 |
| `age1` 批量投递/游标续读 | 不支持；`GET /v1/inbox` 只按 `since` | PPE-1 §12 的 P1.5；现在靠 ACK + 去重兜底 |
| **跨网屏幕协助** | **未实现**：投屏/协助仍要求两端在同一局域网 | 跨网只做**文件**传输；屏幕的采集/渲染链路假定低延迟同网，跨网要先解决抖动与授权（见 `docs/cross-network-file-screen-design.md`） |
| **云推送** | **设计已交付、未实现** | `docs/cloud-push-design.md`；当前后台唤醒靠 Android 前台服务（`remoteMessaging`）+ 离线增量游标同步 |
| **附件「点开下载」** | **未实现**：agent 事件附件只带 `attachmentId`，缺 `fileId`/路径 | 卡片能渲染，点开落盘要等协议补 `fileId` |
| **外部来源配对确认** | **已实现**（task-54）：deep link 与 debug 广播注入都只登记，用户在确认框上点「允许」才落盘；拒绝零副作用 | 理由：把外部输入的密钥存成信任根是不可逆的信任建立。测试 `androidApp/src/test/.../ExternalPairingGateTest.kt`、`composeApp/src/jvmTest/.../ExternalPairingPreviewPurityTest.kt` |

---

## 附：验证与核心测试覆盖

```sh
./gradlew :core:jvmTest --tests 'app.relay.core.protocol.MsgTypeDispositionTest' \
  --tests 'app.relay.core.transport.DiscoveryProfileRefreshTest' \
  --tests 'app.relay.core.transport.TransferAutoAcceptTest' \
  --tests 'app.relay.core.transport.TransferOfferGroupRefTest' \
  --tests 'app.relay.core.transport.TransferOfferTableTextTest' \
  --tests 'app.relay.core.transport.TransferOfferFieldsTest'
./gradlew :composeApp:jvmTest --tests 'app.relay.app.NudgeNotificationTest'
```

- `MsgTypeDispositionTest`：处置划分完整、不自相矛盾，且声明的能力都落在"会处理"那一半。
- `DiscoveryProfileRefreshTest`：`profile` 帧采纳更新版本；`presence` 版本失配时单播 `entry` 换回全量资料。
- `TransferAutoAcceptTest`：私聊 20MB / 群聊 10MB 两条自动接收上限。
- `TransferOfferGroupRefTest`：发端把 `groupId`/`groupRev` 写上帧，且不传时这两个键不出现（严格对齐键集合校验规则）。
- `TransferOfferTableTextTest`：发端把 `tableText` 写上帧、超 4096B 时按 UTF-8 边界截断并置 `tableTextTruncated`，未截断时不增加冗余键。
- `TransferOfferFieldsTest`：`groupId`/`groupRev`/`tableText`/`tableTextTruncated` 四个可选字段穿过组装过程不丢。
- `NudgeNotificationTest`：戳一戳帧生成带有发送者上下文的通知（`focusPeerId` = 发件人），点击直接定位至目标会话。
- 媒体撤回（`mrec1`）：手机长按自己发出的图片触发撤回，对端收到 `file-ctl {"op":"cancel"}` 与 `msg {"kind":"recall","targetId":…}`，收发双向均有完整单测与端到端链路覆盖。


---

## 六、身份恢复 L2（2026-10-25 新增）

`recovery-req` / `recovery-data` 已进 `HANDLED`（方案 §6.3）：请求方恢复身份后向持有
双边副本的对端要历史，对端分页回消息实体。投递纪律与安全边界见
`PROTOCOL-EXTENSION.md` §14。老节点把两张帧当未知类型静默丢弃——这是设计（Q5），
不是缺陷；`MsgTypeDispositionTest` 钉住划分。
