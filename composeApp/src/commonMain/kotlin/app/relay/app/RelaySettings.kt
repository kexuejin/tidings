// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.newId
import app.relay.platform.AppPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个手动添加的节点。手机端主路径就是"知道电脑 IP 就能建立双向在线"。
 */
@Serializable
data class ManualPeerEntry(
    val host: String,
    val port: Int = Protocol.DEFAULT_UDP_PORT,
    /**
     * 本机给这条地址起的名字，**只用于本机显示**（原型 `m08-nodes` 里那台
     * `ci-runner` / `老周的旧笔记本` 就是这种名字）。
     *
     * 为什么不进 wire：名字是"我给这条地址贴的便条"，不是对端声明的身份。
     * 对端一旦握手成功，节点表里就有它自己的昵称，那时候显示的是它的昵称；
     * 这个名字只在**还没握上手**的时候顶替 `host:port` 出现在列表里。
     * 所以它既不参与握手，也不该出现在 `entry` 报文里。
     *
     * `null` = 没起过名字，回落成 `host:port`（与加这个字段之前完全一致）。
     * 有默认值 ⇒ 旧存档反序列化后是 `null`，读得出来。
     */
    val name: String? = null,
)

/**
 * 公网中继配置（Cloudflare Tunnel / agentNode 控制面）。
 */
@Serializable
data class RemoteRelayConfig(
    val url: String,
    val token: String = "",
    val nodeId: String? = null,
    val nick: String? = null,
    val enabled: Boolean = true,
)

/**
 * 共享文件柜针对联系人的单独权限配置。
 */
@Serializable
data class ShareGrantEntry(
    val nodeId: String,
    val mode: String, // "off" | "read" | "write"
)

/**
 * 共享文件柜最近有人放进来的动态记录。
 */
@Serializable
data class CabinetRecentUpload(
    val transferId: String,
    val uploaderNodeId: String,
    val uploaderNick: String,
    val fileCount: Int,
    val totalSize: Long,
    val ts: Long,
)

/**
 * 网段扫描与自动同步配置项。
 */
@Serializable
data class ScanRangeEntry(
    val cidr: String,
    val addedAt: Long,
    val source: String = "self", // "self" | "peer"
    val sourceNodeId: String? = null,
    val sourceNick: String? = null,
)

/**
 * 本节点的持久设置。
 *
 * [nodeId] 与 [profileRev] 是**协议状态**，不是用户偏好：
 * - `nodeId` 必须跨重启稳定，否则每次启动在桌面茶话间里都是一个新联系人；
 * - `profileRev` 每次改资料都要递增，对端据此判断"资料变了，该刷新头像/昵称"。
 */
@Serializable
data class RelaySettings(
    val nodeId: String,
    /**
     * 本机在对端列表里的显示名。**空串 = 还没定过**，由各宿主在首次运行时填自己的默认
     * （手机填「我的手机」，桌面端填系统给的机器名 —— 见 [firstRun] 的说明）。
     * 默认值由平台提供（手机与桌面端采用不同的默认设备名），不硬编在共享模型中。
     */
    val nick: String = "",
    val company: String = "",
    val dept: String = "",
    val team: String = "",
    val avatar: Int = -1,
    val udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    val tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
    val manualPeers: List<ManualPeerEntry> = emptyList(),
    val profileRev: Int = 0,
    /**
     * 聊天记录是否落盘（设置屏 2「数据与存储」里的那个开关）。
     *
     * 它必须住在这里而不是只活在内存里：一个**用户偏好**如果重启就自己弹回 OFF，
     * 那界面上那个开关就是在骗人 —— 用户以为历史留住了，实际重启即丢。
     *
     * 默认 `true`（真机反馈 #2：离线打开 App 时会话列表先空屏再加载，像完全依赖设备在线）。
     * 根因就是默认不落盘 ⇒ 冷启动时 [RelayUiState.messages] 为空，只剩要等局域网发现的
     * [RelayUiState.peers]；把默认改成"存"之后，冷启动先读盘、再等网络，离线也能看到历史会话。
     */
    val persistMessages: Boolean = true,
    /**
     * 跨网通道开关（K1 长连接的文本下行 + F1 的跨网文件传输**共用这一个**）。**默认关**。
     *
     * 关着 = 今天的行为（UDP + TCP 回退 + 离线队列 + 文件只走局域网），这条开关就是
     * K1/F1 的回滚开关 —— 关掉即可回到今天的行为。
     * 开着之后：**局域网**对端仍走明文（与今天的 LAN 路径风险相同，不新增暴露面）；
     * **公网**对端一律走 K2 的 TLS + 双向 pin（fail-closed：没有 pin / 平台没有 TLS 实现
     * 就不拨，绝不降级明文）。F1 的跨网文件另起一条独立 TCP（裸流数据阶段），
     * 所以大文件不会拖累这条通道上的文本延迟。
     *
     * 为什么放在设置里而不是内存里：它与"聊天记录落盘"同类，是**用户偏好**，重启必须还在，
     * 否则开关会在用户没动过的情况下自己弹回去。
     */
    /**
     * **已废弃：长连接不再是一个用户设置。**
     *
     * 曾经它是设置页里的「跨网通道」开关（默认关）。那个设计有两个致命前提：
     *
     *  1. **关掉 ≠ 回滚到可用状态**。关掉后 [app.relay.core.transport.LinkClient] **不拨号**，
     *     只剩"等对端主动连入"——而移动网 / NAT 下对端拨不进来，用户看到的就是
     *     "怎么都连不上"。一个默认关闭的开关，等于每个新用户装完就是坏的。
     *  2. **它守护的风险已经不存在**。当初默认关是为了避免"公网明文出网"，而那条早已由
     *     K2 的 TLS + 双向 pin 解决（公网没有 pin 就不拨，fail-closed，见
     *     `LinkClient.linkTargets`）。开关保护的是一个不存在的问题。
     *
     * 用户的心智是「**在移动网 / WiFi / 内网都能无感连上**」，不是"我要不要开一条长连接"。
     * 所以这个开关已从设置页移除，长连接恒开。
     *
     * **字段保留**只为读旧文件：老库里可能存在 `"linkDownlink": true`，留着字段能让
     * `ignoreUnknownKeys` 之外的行为保持稳定，也方便将来真要做诊断开关时复用。
     * **不要再在任何地方读它来做门控。**
     */
    @Deprecated("Link is always enabled; this toggle is not user-facing")
    val linkDownlink: Boolean = true,
    /**
     * 用户是否就 [persistMessages] 明确做过选择；[migrateRelaySettings] 替老库补默认时也写它。
     *
     * 为什么需要单独一格而不是只看 [persistMessages] 的值：
     * `SettingsStore.json` 的 `encodeDefaults = false`（kotlinx.serialization 的默认）意味着
     * **等于默认值的字段不写进文件**。老版本的默认值是 `false` ⇒ 旧 settings.json 里
     * 「从没设过」与「显式关掉」是**同一串字节**（实况见
     * `design/runtime-evidence/persist-04-android-on-disk.txt:33`：只有为 true 时才写了这个键）。
     * 光看字段缺失无法区分这两档，所以把"做过决定"独立成一位：迁移只认它，
     * 认过一次就不再重来 —— 以后即使再改默认值，也不会把用户关掉的开关重新打开。
     *
     * 代价（写在这里免得下一个人以为漏了）：**旧文件里显式关掉的用户无法与"从没设过"区分**，
     * 迁移会把他们也补成开启。这是旧序列化格式丢掉的信息，可靠恢复不了；新版本写出的
     * `"persistMessages": false`（false ≠ 新默认值，会落盘）从此是可判别、且被尊重的选择。
     */
    val persistMessagesDecided: Boolean = false,
    /**
     * 外观档位的 wire 形态（`system` / `light` / `dark`，认不出的值一律当跟随系统）。
     *
     * 与 [persistMessages] 同一条理由：一个**用户偏好**如果重启就自己弹回「跟随系统」，
     * 那个三选一的选择器就是在骗人 —— 用户以为选定了，实际下次启动又跟着系统跑。
     *
     * 默认 `""`（取不到 = 跟随系统）：这里刻意**不**引用 `feature.settings` 里那三个
     * `THEME_MODE_*` 常量，因为那是 UI 层的东西，`app` 层反向依赖它会绕成一个圈。
     * 值最终由 `ThemeMode.fromWire` 兜底解释，任何认不出的串都安全地落回跟随系统。
     */
    val themeMode: String = "",
    /**
     * 能力声明 `tw1`：本机**能收 wait 帧**（不是"能收发文本"，那是这张卡以前的假文案）。
     *
     * 声明它 = 对端在自己忙（给文件做预哈希）时**可以**给我们发保活帧，排队中的传输不会
     * 撞上对端的空闲超时；不声明 = 对端按"老端"对待我们（协议规定 wait 帧只能发给声明了
     * `tw1` 的对端，发给老端会直接断链）。
     *
     * 默认 `true`：这是 [MsgTypes.MOBILE_CAPS] 的基线，也是手机端能力声明的基线要求。
     */
    val waitFrameCapability: Boolean = true,
    /**
     * 能力声明 `mrec1`：本机**认媒体撤回**（对端撤回自己发的图片/文件消息时，本端隐藏消息、
     * 取消进行中的拉取并清理 `.part`）。上游依据＝协议 §3 —— 这个键**不是**录音。
     *
     * 与 [waitFrameCapability] 同理，它声明的是"对端可以给我发什么"，不是"我这边有什么功能"。
     */
    val recordMessageCapability: Boolean = true,
    /**
     * 通知策略：**Agent 会话**的新消息要不要提醒。
     *
     * Agent 节点会自己往外说话（状态、进度），是最吵的一类；它和人类会话分开管，
     * 这样"把机器人静音"不需要把同事也一起静音。
     */
    val notifyAgentMessages: Boolean = true,
    /**
     * 通知策略：只对**被 @ 到**的消息响铃，其余静默进列表。
     *
     * 只对群消息有判定依据：协议里 `mentions` 只存在于群消息载荷（`GroupTextMsgPayload`），
     * 一对一的消息没有"提到"这个概念 —— 私聊本来就是对你说的话，不能因为这条策略静音，
     * 否则用户会以为对方没回过。默认 `true`（与设置屏上的默认档一致）。
     */
    val notifyMentionOnly: Boolean = true,
    /**
     * 通知策略：22:00–08:00（**用户当地时间**）不响铃。
     *
     * 默认 `false`：这个偏好一旦默认为真，用户会在自己没设过的地方错过消息。
     */
    val notifyQuietHours: Boolean = false,
    /**
     * 「后台保持连接」：退到后台时用一条常驻通知的前台服务把进程留在前台状态，
     * 这样节点仍能被局域网里的设备发现、仍能收发消息（Android 专属能力）。
     *
     * ## 为什么默认 true
     *
     * 这是"能收到消息"的前提，不是可选优化。默认关等于默认收不到：Android 会把退到后台的
     * 进程打成 cached 并收紧其网络，真机实测约 90 秒后对端就把它判成
     * 离线、消息全部积压。信使应用默认收不到消息，是产品性的错，不是省电。
     *
     * ## 代价是**可见**的，不是隐藏行为
     *
     * 打开它 = 通知栏里常驻一条「正在保持连接」，并增加耗电。所以设置页必须把这个开关
     * 连同代价一起写清楚，关掉时必须**真的停服务**（[app.relay.platform.BackgroundConnectionService.setEnabled]）。
     *
     * ## 别的平台
     *
     * 桌面端与 iOS 的进程模型不需要这条能力（平台侧的 backgroundConnection 为 null，
     * 设置页据此不画这个开关）。这个字段在那里只是被序列化的一个值，不产生任何行为。
     */
    val keepConnectionAlive: Boolean = true,
    /**
     * 本机认识的群（成员表 + 版本）。
     *
     * 为什么住在这里而不是单开一个 `groups.json`：群列表的读取时机与设置完全一样（启动时
     * 读一次、变更时整份写回），而每多一个平台文件就多一套 expect/actual、多一处"iOS 写哪儿"
     * 的分支。`SettingsStore.json` 的 `ignoreUnknownKeys = true` 保证旧版本读到带 `groups`
     * 的文件不会炸，新版本读到不带它的文件也能拿到空列表 —— 两个方向都安全。
     *
     * **它不是用户偏好，而是协议状态**（与 [nodeId]、[profileRev] 同类）：丢了就等于"我退出了
     * 所有群"，而对端仍然认为我们在群里，会继续把群消息发过来，我们却只能把它们归到一个
     * 不存在的会话里。所以它必须落盘，而且默认值只能是空列表（老用户升级后行为不变）。
     */
    val groups: List<GroupMeta> = emptyList(),
    val remoteRelay: RemoteRelayConfig? = null,
    /**
     * 共享文件柜：本机发布的本地文件夹绝对路径。null 或空串表示未开启。
     */
    val shareRoot: String? = null,
    /**
     * 共享文件柜：默认权限模式。
     * "off" (不共享) / "read" (只读) / "write" (可读可传)。默认 "read"。
     */
    val shareMode: String = "read",
    /**
     * 共享文件柜：按联系人单独定制的权限例外列表。
     */
    val shareGrants: List<ShareGrantEntry> = emptyList(),
    /**
     * 共享文件柜：最近被其他人放进来的文件汇总记录。
     */
    val cabinetRecentUploads: List<CabinetRecentUpload> = emptyList(),
    /**
     * 已配置与网络学习到的 CIDR 网段。
     */
    val scanRanges: List<ScanRangeEntry> = emptyList(),
    /**
     * **本机**给各个节点起的备注名，`nodeId` → 备注（茶话间 F-DISC-9）。
     *
     * ## 为什么是 map 而不是加在 [ManualPeerEntry] 上
     *
     * 备注要能挂在**任何**节点上，不只是手动添加的那些：局域网扫到的、Agent、
     * 甚至中继来的远端节点都得能起名。挂进 `ManualPeerEntry` 就只有手填的地址能备注，
     * 而"两台都叫 mac mini 分不清"这件事恰恰最常发生在自动发现的节点上。
     *
     * ## 为什么落盘在设置里而不是新开一个文件
     *
     * 与 [groups] 同一条理由：读取时机完全一样（启动读一次、变更整份写回），
     * 而每多一个平台文件就多一套 expect/actual、多一处"iOS 写哪儿"的分支。
     * `SettingsStore.json` 的 `ignoreUnknownKeys = true` 保证两个方向都安全。
     *
     * ## 它不是协议状态，是纯本机偏好
     *
     * 丢了不会让任何对端进入不一致状态（对端根本不知道我们叫他什么），
     * 所以默认值只能是空 map，老用户升级后行为不变。
     */
    val peerRemarks: Map<String, String> = emptyMap(),
) {
    /**
     * 按当前能力开关生成的 `caps` 列表。
     *
     * 两个都关掉是**协议上合法**的（caps 允许空数组，对端按老端处理），
     * 只是传输会退化成"没有排队保活、取消后不能断点重拉"。这里不替用户兜底，
     * 因为那两个开关存在的唯一理由就是让他能这么试。
     *
     * `agn1` **不受开关影响，恒在**：它不是用户偏好，而是"这个壳实现了 agent 会话帧"的事实
     * 声明 —— 与"手机一定监听 TCP"同一类。接到开关上就会出现"关掉之后别人发的 agent 帧被
     * 静默丢弃、对方一直重传"的状态，而那正是 caps 机制要避免的事。
     *
     * `tbl1` 同理恒在（也没有开关）：它是"这个壳认识 `offer.tableText`"的事实声明，
     * 而发送端只向声明者附带那段文字。顺序与 [MsgTypes.MOBILE_CAPS] 一致。
     */
    /** 本机文件柜是否对任何人开放（对齐茶话间 ShareService.isEnabled） */
    fun isShareEnabled(): Boolean {
        val root = shareRoot?.trim().orEmpty()
        if (root.isEmpty()) return false
        if (shareMode != "off") return true
        return shareGrants.any { it.mode != "off" }
    }

    /**
     * @param advertiseAgent 桌面端承载 agent 节点时为 true —— 声明 `ag1` 与 agent 帧能力。
     *   手机端恒 false（见 [toProfile] 的 KDoc）。
     */
    fun caps(
        remoteView: Boolean = false,
        remoteShare: Boolean = false,
        customAvatar: Boolean = false,
        advertiseAgent: Boolean = false,
    ): List<String> = buildList {
        // `ag1` 在最前：它是「这个节点能承载 agent 会话」的身份声明，与后面的帧能力不同层。
        // 桌面端（托盘常驻）与 agentNode 都声明它；手机端不声明。
        if (advertiseAgent) add(MsgTypes.CAP_AGENT)
        // lnk1 = "我能接受一条长连接（K1/K2 的下行通道）"，必须**如实声明**，否则手机的
        // LinkClient 会把我们当成老端、一个拨号都不发（task-31 真机：linkConnected 恒 false、
        // links.count=0、17880 一条 ESTABLISHED 都没有 —— 而消息仍然走 UDP/TCP，看起来"一切正常"）。
        //
        // 只随 ag1 一起声明（宿主侧）：K1/K2 的设计是**手机拨主机**，主机侧才有下行通道的
        // 意义。手机不声明 ⇒ 桌面不会反过来去拨手机，连接数不会凭空翻倍。
        //
        // **这个耦合是刻意的，不是漏配**（workspace-ia 审查点出：没有这句话，
        // 读者看到 `lnk1` 挂在 `advertiseAgent` 下会以为是漏了一处清单 —— 今天已经因为
        // "只加进一份清单、没进生效清单" 栽过两次（`lnk1` 与 `att1`），凡是**看着像漏配**
        // 的地方都值得一句话讲清）：
        //   · 长连接存在的唯一理由是**服务 agent 下行**（会话事件、附件、审批）；
        //   · 用户关掉「宣告 agent」⇒ 本机不是 agent 宿主 ⇒ 那条下行通道**没有受众**，
        //     声明它只会让手机白拨一条没人用的连接（还多一份握手与心跳开销）。
        // 所以两个能力同开同关是设计意图；将来若真要让"非宿主也能收下行"，那要另立一个
        // 独立的开关与用途，而不是把这一行挪出来。
        if (advertiseAgent) add(MsgTypes.CAP_LINK)
        if (waitFrameCapability) add(MsgTypes.CAP_MOBILE_TEXT)
        if (recordMessageCapability) add(MsgTypes.CAP_MOBILE_RECORD)
        add(MsgTypes.CAP_AGENT_SESSION)
        // 支持任务排队与审批交互（agent-approval、agent-ack、agent-queue、agent-run、agent-cancel）
        add(MsgTypes.CAP_AGENT_QUEUE)
        // D1/D2/D3 已在 Codec 落地 ⇒ 如实声明 `aws1`（能收 agent-session 的四个新可选键
        // 与 `agent-workspace` 帧）。
        add(MsgTypes.CAP_AGENT_WORKSPACE)
        // D7 已落地（收 grant 三帧 + 发 grant-request）⇒ 如实声明 `agr1`。
        // 不声明的后果不是「更安全」而是「宿主的 byDevice/request 档直接不发数据给我们」：
        // 沉默 = 未授权（父稿 §3.3），手机端会永远停在「未申请」，连申请都发不出。
        add(MsgTypes.CAP_AGENT_GRANT)
        // D8 已落地（点开工作区时向宿主要一页历史会话）⇒ 如实声明 `agp1`。
        // 不声明时宿主不会把「加载更多」那一路当回事（它按 cap 决定要不要应答），
        // 而手机端会永远只看得到活动过的那几条会话。
        add(MsgTypes.CAP_AGENT_SESSION_PAGE)
        add(MsgTypes.CAP_TABLE_TEXT)
        // task-45：手机能把 agent 事件的附件元数据渲染成卡片 ⇒ 如实声明（顺序跟 MOBILE_CAPS 一致）。
        // 漏了这一行就是 task-31 那类静默：桥永远只发文本占位，而代码里"明明支持附件"。
        add(MsgTypes.CAP_ATTACHMENT)
        if (remoteView) add(MsgTypes.CAP_REMOTE_VIEW)
        if (remoteShare) add(MsgTypes.CAP_REMOTE_SHARE)
        if (isShareEnabled()) add(MsgTypes.CAP_SHARE)
        if (customAvatar) add(MsgTypes.CAP_AVATAR)
    }

    /**
     * 按当前设置生成协议档案。
     *
     * ## advertiseAgent
     *
     * 桌面端原生承载 agent 节点（托盘常驻、固定回环 8788），需要广播 `ag1`。
     *
     * 而**手机端绝不能**声明 `ag1`：一个手机不承载 AI 会话，声明了就是骗对端
     * （对方会把会话帧推过来，我们只回 ACK、业务层丢弃）。所以这是一个**显式开关**，
     * 而不是照着平台自动判断 —— 两个宿主的区别是**角色**，不是平台。
     *
     * 默认 false 让所有既有调用点（手机、测试、预览）行为不变。
     */
    fun toProfile(
        host: String,
        ver: String,
        remoteView: Boolean = false,
        remoteShare: Boolean = false,
        customAvatar: Boolean = false,
        advertiseAgent: Boolean = false,
    ): Profile = Profile.mobile(
        nodeId = nodeId,
        nick = nick.take(LIMIT_NICK),
        host = host.take(LIMIT_HOST),
        tcpPort = tcpPort,
        // K2：TLS 监听端口在 TCP 端口之上一个（独立端口，不复用 17879 做嗅探 —— task-13 裁决 ③）。
        // 平台不支持 TLS 时（iOS 尚未落地）这个键照样广播，但本机不会绑它，对端连不上会退回原路径。
        tlsPort = tcpPort.takeIf { it in 1..65534 }?.plus(1),
        ver = ver.take(LIMIT_VER),
        profileRev = profileRev,
        avatar = avatar,
        company = company.take(LIMIT_COMPANY),
        dept = dept.take(LIMIT_DEPT),
        team = team.take(LIMIT_TEAM),
        caps = caps(
            remoteView = remoteView,
            remoteShare = remoteShare,
            customAvatar = customAvatar,
            advertiseAgent = advertiseAgent,
        ),
    )

    companion object {
        const val LIMIT_NICK = 32
        const val LIMIT_COMPANY = 32
        const val LIMIT_DEPT = 32
        const val LIMIT_TEAM = 32
        /**
         * 本机备注的长度上限。与茶话间 `ProfileCard.vue:141` 的 `maxlength="32"` 对齐，
         * 也与 [LIMIT_NICK] 同量级 —— 备注是拿来在列表里认人的，不是第二份简介。
         */
        const val LIMIT_REMARK = 32
        const val LIMIT_HOST = 64
        const val LIMIT_VER = 16

        /**
         * 首次运行的默认设置。
         *
         * `defaultNick` 由**宿主**传入而不是写在这里：手机与桌面端是同一份模型的两个宿主，
         * 各宿主提供自己的默认设备名，避免产生命名冲突。
         *
         * @param defaultNick 该宿主的默认显示名；空串则由调用方之后再定。
         */
        fun firstRun(defaultNick: String = ""): RelaySettings =
            RelaySettings(nodeId = newId(), nick = defaultNick)
    }
}

/**
 * settings.json 的**一次性迁移**（真机反馈 #2）。
 *
 * 只有一件事：把「消息落盘」的默认值从关改成开。`SettingsStore.json` 不写等于默认值的字段
 * （`encodeDefaults = false`），所以老库升级后这里能观察到的只有两种输入：
 *
 * - 旧文件里**没有** `persistMessages` 键：可能是"从没设过"，也可能是"显式关掉" —— 两者
 *   在旧文件里是同一串字节，**无法区分**（见 [RelaySettings.persistMessagesDecided] 的 KDoc）。
 *   按"从没设过"迁移到开启：这是产品决定（用户反馈要的是离线可见），代价写在那段 KDoc 里。
 * - 旧文件里写着 `persistMessages: true`：用户本来就开着，迁移只是把结果写成恒等。
 *
 * 新版本写出的 `persistMessages: false` 一定同时带着 `persistMessagesDecided = true`
 * （见 `RelayControllerSettingsComponent.handleUpdatePersistMessages`），所以**用户显式关掉的
 * 选择不会被这条迁移覆盖** —— 这是 [RelaySettings.persistMessagesDecided] 存在的全部理由。
 *
 * @return 迁移后的设置；没有可迁移的东西时返回**同一个实例**，调用方据此决定要不要写盘。
 *   只判 `persistMessagesDecided`，不判值的字面量：否则以后每次改默认值都会把用户关掉的
 *   开关重新打开一遍。
 */
internal fun migrateRelaySettings(loaded: RelaySettings): RelaySettings =
    if (loaded.persistMessagesDecided) {
        loaded
    } else {
        loaded.copy(persistMessages = true, persistMessagesDecided = true)
    }

/**
 * 设置的读写。
 *
 * 这是"凭据/配置必须有录入界面"那条硬约束的落点：UI 改的是这个对象，
 * 平台实现负责把它落到应用私有目录 —— **不需要用户去手写任何配置文件**。
 */
interface SettingsStore {
    fun load(): RelaySettings?

    fun save(settings: RelaySettings)

    companion object {
        internal val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

/** 平台壳提供落盘实现；测试与预览用内存实现。 */
expect fun createSettingsStore(platform: AppPlatform): SettingsStore

/** 内存实现：Compose 预览与单元测试用，绝不碰真实文件系统。 */
class InMemorySettingsStore(private var current: RelaySettings? = null) : SettingsStore {
    var saveCount: Int = 0
        private set

    override fun load(): RelaySettings? = current

    override fun save(settings: RelaySettings) {
        current = settings
        saveCount++
    }
}
