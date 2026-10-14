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
     * 默认 `false` 保证老用户升级后行为不变。
     */
    val persistMessages: Boolean = false,
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
        if (waitFrameCapability) add(MsgTypes.CAP_MOBILE_TEXT)
        if (recordMessageCapability) add(MsgTypes.CAP_MOBILE_RECORD)
        add(MsgTypes.CAP_AGENT_SESSION)
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
