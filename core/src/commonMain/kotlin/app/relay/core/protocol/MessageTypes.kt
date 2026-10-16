// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * 已知消息类型。与 TS 侧 `MSG_TYPES` 完全一致。
 *
 * **扩展规则（vertical extension）**：新增 `agent-*` 类型必须放进这里才会被本端处理；
 * 对端若是老节点，其 `KNOWN_TYPES` 里没有该值，会解码通过但 `known=false` → 静默忽略，
 * 不崩溃、不回错。这正是我们扩展协议的方式。
 */
object MsgTypes {
    const val ENTRY = "entry"
    const val ALIVE = "alive"
    const val EXIT = "exit"
    const val PRESENCE = "presence"
    const val PROFILE = "profile"
    const val PEERS = "peers"
    const val SCAN_RANGES = "scan-ranges"
    const val MSG = "msg"
    const val ACK = "ack"
    const val FILE_CTL = "file-ctl"
    const val GROUP = "group"
    const val AVATAR = "avatar"
    const val UPDATE = "update"
    const val SHARE = "share"
    const val SCREEN = "screen"

    /**
     * PPE-1 §6.1：agent 会话宣告（cap `agn1`）。
     *
     * 已知键：`sessionId` / `alias` / `agentKind` / `state` / `title` / `startedAt`，
     * 加上增补 D1/D6 的四个可选键 `agentId` / `wsId` / `wsTitle` / `wsPath`（cap `aws1`）
     * —— 见 `Codec.AGENT_SESSION_EXPECTED_KEYS`。
     *
     * 校验语义自 D2 起是「**必需子集 + 忽略未知键**」（父稿 §2.3）：未知键在预算内
     * （≤8 个、UTF-8 ≤1024B）被忽略而不是整帧丢弃；`agent-event` 等其余 `agent-*`
     * 仍是严格白名单。
     */
    const val AGENT_SESSION = "agent-session"

    /** PPE-1 §6.2：agent 会话事件（cap `agn1`；批量与游标续读另需 `age1`）。 */
    const val AGENT_EVENT = "agent-event"

    /** PPE-1 §6.3 ~ §6.8：agent 队列、执行、审批与控制（cap `agq1`）。 */
    const val AGENT_QUEUE = "agent-queue"
    const val AGENT_RUN = "agent-run"
    const val AGENT_APPROVAL = "agent-approval"
    const val AGENT_CANCEL = "agent-cancel"
    const val AGENT_ACK = "agent-ack"

    /**
     * PPE-1 §6.7：产物元数据（cap `agn1`，**不是** `agq1`）。
     *
     * 只描述产物、不传二进制；二进制走 `file-msg`，这里只带一个 `fileId` 引用。
     * 与 §6.1/§6.2 同属会话基线能力，所以归 `agn1`。
     */
    const val AGENT_ARTIFACT = "agent-artifact"

    /**
     * PPE-1 增补 D3：宿主**主动宣告一个工作区**（cap `aws1`），一帧一个。
     *
     * 覆盖「工作区存在但还没有任何会话」这一档 —— `agent-session` 的 ws 键只到
     * "会话带着工作区身份来"，空工作区只能靠这帧显式宣告（父稿 §2.2）。
     *
     * **在 RELIABLE 里**（与父稿"新类型对旧端 known=false 静默忽略"并不冲突）：
     * 本端只在对方声明 `aws1` 时才发这帧（cap 门控），而声明 `aws1` 的必然是
     * `Messenger` 只把 RELIABLE 类型投进 incoming（`presence` 能到 inbound 是因为
     * 它走 Discovery 的 gossip 通道，agent 帧没有那条路），因此采用可靠投递。
     *
     * 父稿 §2.2 的键表里有 `source?`，但 §0「不做」清单裁决**不加**（它没有独立
     * 数据源，派生自 `agentKind`）。本实现照「不做」走：帧里**没有** source 键。
     */
    const val AGENT_WORKSPACE = "agent-workspace"

    /** PPE-1 增补 D7：设备向宿主**申请**看它的 agent（cap `agr1`），设备 → 宿主。 */
    const val AGENT_GRANT_REQUEST = "agent-grant-request"

    /** PPE-1 增补 D7：宿主的授权决定（approved / denied / revoked），宿主 → 设备。 */
    const val AGENT_GRANT = "agent-grant"

    /**
     * PPE-1 增补 D7：宿主把"你被授权看哪些"逐条回给设备（一帧一条，可选）。
     * 触发点：收到 `agent-grant-request` 时先回当前生效清单，让手机端四档状态里
     * 「已授权」与「未申请」可区分（父稿 §3.3：沉默 ≠ 拒绝）。
     */
    const val AGENT_GRANT_LIST = "agent-grant-list"

    /**
     * 设备向宿主**要一页会话清单**（cap `agp1`），设备 → 宿主。
     *
     * 为什么需要它：`agent-session` 只在会话**正在活动**时由宿主推送，所以 DSH 重启后
     * 已有的历史会话一条都到不了手机 —— 用户看到的是「某工作区写着 6 个会话，点开却
     * 一条都没有」。历史会话一直在宿主侧，缺的只是让手机能开口要的链路。
     *
     * **响应不新造帧**：宿主收到后直接用它已有的 `agent-session` 逐条回填，所以这里
     * 只新增请求、不新增响应。
     *
     * 分页而不是一次全给：TCP 载荷上限 4096 字节，一个工作区上百条会话装不进一帧；
     * 且用户的原话是「也没必要一次性就加载全部会话，可以通过更多的方式加载」。
     */
    const val AGENT_SESSION_PAGE = "agent-session-page"

    /** 本实现认识的类型；未知类型解码通过但 known=false。 */
    val KNOWN: Set<String> = setOf(
        ENTRY, ALIVE, EXIT, PRESENCE, PROFILE, PEERS, SCAN_RANGES, MSG, ACK,
        FILE_CTL, GROUP, AVATAR, UPDATE, SHARE, SCREEN,
        AGENT_SESSION, AGENT_EVENT,
        AGENT_QUEUE, AGENT_RUN, AGENT_APPROVAL, AGENT_CANCEL, AGENT_ACK,
        AGENT_ARTIFACT,
        AGENT_WORKSPACE, AGENT_GRANT_REQUEST, AGENT_GRANT, AGENT_GRANT_LIST, AGENT_SESSION_PAGE,
    )

    /**
     * 可靠帧：必须回 ACK。
     *
     * 四张新帧（D3 的 workspace + D7 的三张 grant）全在：cap 门控保证它们只发给
     * 声明了对应能力的本实现对端，不存在"老端不回 ACK → 假故障"；反过来，
     * 不在这里的帧到不了 `inbound`（见上），状态广播也必须被收到。
     */
    val RELIABLE: Set<String> = setOf(
        MSG, FILE_CTL, GROUP, AVATAR, UPDATE, SHARE, SCREEN,
        AGENT_SESSION, AGENT_EVENT,
        AGENT_QUEUE, AGENT_RUN, AGENT_APPROVAL, AGENT_CANCEL, AGENT_ACK,
        AGENT_ARTIFACT,
        AGENT_WORKSPACE,
        AGENT_GRANT_REQUEST, AGENT_GRANT, AGENT_GRANT_LIST,
        // 请求帧同样必须在 RELIABLE 里：不在的话它到不了对端 inbound（见上文那条），
        // 表现是「手机点了加载更多，宿主根本没收到」。
        AGENT_SESSION_PAGE,
    )

    /**
     * 手机端 caps —— 尤其**不能**声明 `upd1`，否则桌面端会推自更新包。
     */
    val MOBILE_CAPS: List<String> =
        listOf(
            CAP_MOBILE_TEXT, CAP_MOBILE_RECORD, CAP_AGENT_SESSION, CAP_AGENT_QUEUE, CAP_AGENT_WORKSPACE,
            CAP_AGENT_GRANT, CAP_AGENT_SESSION_PAGE, CAP_TABLE_TEXT,
        )

    /**
     * **本项目的协议扩展点**：用 caps 里的一个自定义键来标注"这是一个 agent 节点"。
     *
     * 为什么用 caps 而不是新增 message type：caps 是**字符串数组**，茶话间对它的校验只有
     * 「数组 ≤16 项、每项 ≤16 字符」（`codec.ts` 的 caps 分支），未知键既不报错也不影响
     * 解码，桌面端只是把它当"看不懂的能力"照原样显示。所以加一个 `ag1` 是**零改动接入**，
     * 而新增一个 message type 需要在两边都改 `KNOWN_TYPES` 才有意义。
     *
     * 语义：声明 `ag1` 的节点代表"一个可以接活的 agent"，UI 据此把它和人类同事分开显示
     * （见 `design-spec.json` 的 `PeerRow.kind_badge[human,agent]`）。
     */
    const val CAP_AGENT = "ag1"

    /**
     * `agn1` = **agent 会话语义基线**（PPE-1 §4），是任何 `agent-*` 帧的必选前置。
     *
     * 声明它的意思是"我认识 `agent-session` / `agent-event`，发给我不会石沉大海"。
     * 手机端从收到这两个帧、到控制器分派、到 Agent 控制台展示，都已经落地，所以这里可以声明；
     * 在此之前声明它就是**骗对端**——对方发来、我们静默丢弃，而对方还在按 `RELIABLE` 重传。
     *
     * 与 [CAP_AGENT] 的区别（容易混）：`ag1` 只说明"我是 agent 节点"，不含任何帧语义；
     * `agn1` 才说明"我看得懂 agent 帧"。一个节点可以只有其中一个。
     *
     * `age1`（可恢复事件流 / 批量投递 / 游标续读）**仍然不声明**：本端按 cursor 排序去重、
     * 但不支持批量帧与 `GET /v1/inbox?cursor=` 续读，那是 PPE-1 §12 的 P1.5。
     */
    const val CAP_AGENT_SESSION = "agn1"

    const val CAP_MOBILE_TEXT = "tw1"
    const val CAP_MOBILE_RECORD = "mrec1"

    /**
     * `tbl1` = **表格图片文字视图**（协议 §3）。
     *
     * 声明它的意思是"认识 `file-ctl offer.tableText/tableTextTruncated`"。发送端只向声明者
     * 附带那段原始 TSV（≤4096B），收端把它存进图片消息并在气泡里给"图片/文字"切换。
     * 本端做的是收侧：字段进门、进卡片、能读能复制；发侧没有表格粘贴入口，所以从不产生它。
     */
    const val CAP_TABLE_TEXT = "tbl1"

    /**
     * `fd1` = 私聊文件支持**发送方请求免确认接收**（收端仍受自己的本地开关控制）。
     *
     * **只读，不声明**：本客户端**不往自己的 caps 里放这个键** —— 那等于对外承诺"可以
     * 免确认把文件写进我的存储"，而手机上这是一个应当由用户逐次点头的决定，我们也没有
     * 对应的设置项。所以别人发文件给手机时一律弹「接收 / 拒绝」。
     *
     * 但它必须被**读**：桌面端在自己 `allowDirectFileSend` 打开时会声明 `fd1`，
     * 我们据此在 `offer` 之后补一条 `file-ctl direct`，桌面端就会免确认收下
     * （`files.ts` 的 `onDirectRequest`）。桌面端设关闭时它自己不声明 `fd1`，
     * 我们一条都不发 —— 决定权始终在收端，这正是协议的设计。
     */
    const val CAP_FILE_DIRECT = "fd1"

    /**
     * `shr1` = **共享文件柜能力**（协议 §8.2）。
     */
    const val CAP_SHARE = "shr1"

    /**
     * `rv1` = 屏幕查看（对齐 protocol §8.3 / CAPS.remoteView）。
     * `rvs1` = 屏幕共享（对齐 protocol §8.3 / CAPS.remoteShare）。
     */
    const val CAP_REMOTE_VIEW = "rv1"
    const val CAP_REMOTE_SHARE = "rvs1"

    /**
     * `av1` = **自定义头像能力**（PPE-1 §15 / protocol §3）。
     * 声明本能力表示节点支持通过 `avatar{op:"get"}` 请求并提供自定义头像数据。
     */
    const val CAP_AVATAR = "av1"

    /**
     * `agq1` = **agent 任务队列与审批语义**（PPE-1 §6.3 ~ §6.8）。
     */
    const val CAP_AGENT_QUEUE = "agq1"

    /**
     * `ams1` = **消息级 agent 会话关联能力**（PPE-1 §6 扩展）。
     * 声明本能力表示节点支持在 `msg` 文本/富文本帧中携带可选 `agentSessionId`，
     * 用于将 IM 文本流关联到具体的 Agent 会话线索（Thread）中。
     */
    const val CAP_MSG_AGENT_SESSION = "ams1"

    /**
     * `aws1` = **工作区身份携带能力**（PPE-1 增补 D1/D2/D3，`docs/PROTOCOL-AGENT-WORKSPACE.md` §2.4）。
     *
     * 语义（父稿把两件事绑在一个 cap 里，减少一个协商位）：声明方**既能收
     * `agent-session` 的四个新可选键**（`agentId` / `wsId` / `wsTitle` / `wsPath`），
     * 支持四个可选键及 agent-workspace 帧的完整解析与投递。
     */
    const val CAP_AGENT_WORKSPACE = "aws1"

    /**
     * `agr1` = **agent 授权回路能力**（PPE-1 增补 D7，父稿 §3.3）。
     *
     * 语义：声明方认识三张 grant 帧（`agent-grant-request` / `agent-grant` /
     * `agent-grant-list`），并且**在投递 agent 帧前按策略过滤**（执行点在宿主进程，
     * 「没授权」才等于「没数据」）。
     *
     * 旧端（不声明 `agr1`）在 `byDevice` / `request` 档下收不到 agent 数据 ——
     * 沉默 = 未授权，不走申请流程、不猜、不默认放行（父稿 §3.3）。
     */
    const val CAP_AGENT_GRANT = "agr1"

    /**
     * `agp1` = **agent 会话分页能力**（PPE-1 增补 D8）。
     *
     * 语义：声明方认识 `agent-session-page`（设备向宿主要一页会话清单）。
     *
     * 为什么单开一个 cap 而不是塞进 `agn1`：这两个能力是**两个方向**的。`agn1` 说的是
     * 「我看得懂你推来的会话帧」（收的能力），`agp1` 说的是「我能向你**要**历史会话」
     * （发的能力）。旧宿主不声明 `agp1` 时手机不该白等一个永远没人答的请求 ——
     * 界面上「加载更多」那一档要据此直接隐藏或明说不可用（父稿 §3.3 的「沉默 ≠ 拒绝」
     * 同类纪律：不猜、不装作发过）。
     */
    const val CAP_AGENT_SESSION_PAGE = "agp1"

    /**
     * agent 节点的 caps：`ag1`（身份）+ 一个人类节点也会有的文本能力 + `agn1`（会话帧）
     * + `aws1`（工作区键与宣告帧，D1/D2/D3 已实现）+ `agr1`（授权回路，D7 已实现）。
     */
    val AGENT_CAPS: List<String> =
        listOf(
            CAP_AGENT, CAP_MOBILE_TEXT, CAP_AGENT_SESSION, CAP_AGENT_QUEUE,
            CAP_AGENT_WORKSPACE, CAP_AGENT_GRANT, CAP_AGENT_SESSION_PAGE,
        )

    /**
     * 本端**真的会处理**的已知类型，与 [UNHANDLED] 一起构成对 [KNOWN] 的一个划分。
     *
     * `SHARE` 在这里的依据只是**传输层**：它属于 [RELIABLE_TYPES]，入站帧会被 ACK；业务层
     * （`RelayController.CHAT_INBOUND_TYPES`，消费点在 `RelayControllerMessagesComponent`）不含它，帧会被丢掉，共享文件柜
     * 至今只有界面壳。这一处「声明了却没有实现」已如实写进 `PROTOCOL-COVERAGE.md` 的 §三/§五，
     * 由 `scripts/e2e/e2e_share.py` 的壳契约断言钉住。
     */
    val HANDLED: Set<String> = setOf(
        ENTRY, ALIVE, EXIT, PRESENCE, PROFILE,
        MSG, ACK, FILE_CTL,
        AGENT_SESSION, AGENT_EVENT,
        AGENT_QUEUE, AGENT_RUN, AGENT_APPROVAL, AGENT_CANCEL, AGENT_ACK,
        AGENT_ARTIFACT,
        AGENT_WORKSPACE, AGENT_GRANT_REQUEST, AGENT_GRANT, AGENT_GRANT_LIST, AGENT_SESSION_PAGE,
        GROUP, SHARE, SCAN_RANGES, SCREEN, AVATAR,
    )

    /**
     * 本端**认得、但有意不处理**的已知类型。
     */
    val UNHANDLED: Set<String> = setOf(UPDATE, PEERS)
}

/** 这个节点是否声明了自己是 agent 节点（见 [MsgTypes.CAP_AGENT]）。 */
fun Profile.isAgentNode(): Boolean = MsgTypes.CAP_AGENT in caps

/** `msg` 载荷的 kind 取值。 */
object MsgKinds {
    const val TEXT = "text"
    const val GROUP_TEXT = "group-text"
    const val RECALL = "recall"
    const val NUDGE = "nudge"
    const val PK = "pk"

    val ALL: Set<String> = setOf(TEXT, GROUP_TEXT, RECALL, NUDGE, PK)
}

/** PK 小游戏类型 */
object PkGames {
    const val RPS = "rps"
    const val DICE = "dice"
    val ALL: Set<String> = setOf(RPS, DICE)
}

/** 猜拳取值 */
object RpsValues {
    const val ROCK = "rock"
    const val PAPER = "paper"
    const val SCISSORS = "scissors"
    val ALL: Set<String> = setOf(ROCK, PAPER, SCISSORS)
}

/** `platform` 取值：协议里没有 mobile，手机端伪装成 linux。 */
object Platforms {
    const val WIN = "win"
    const val MAC = "mac"
    const val LINUX = "linux"
    val ALL: Set<String> = setOf(WIN, MAC, LINUX)
}

/**
 * Agent 会话状态枚举与分类（PPE-1 §6.1 / 方案 D-2 会话生命周期）。
 * 协议层取值开放，本集合用于 UI 翻译与客户端软淘汰分类。
 */
object AgentSessionStates {
    const val IDLE = "idle"
    const val BUSY = "busy"
    const val DONE = "done"
    const val ERROR = "error"
    const val ARCHIVED = "archived"
    const val CLOSED = "closed"

    val ALL: Set<String> = setOf(IDLE, BUSY, DONE, ERROR, ARCHIVED, CLOSED)
    val ENDED: Set<String> = setOf(DONE, ERROR, ARCHIVED, CLOSED)
}

/**
 * PPE-1 增补 D7（父稿 §3.3）：授权帧的三个取值域。
 *
 * 与 `agent-session.state` 不同，这三组是**封闭取值**：它们是"有人按了按钮"的结论，
 * 拼错一个等于语义不存在，所以 codec 直接拒收而不是原样放行。
 */
object AgentGrantStates {
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val REVOKED = "revoked"
    val ALL: Set<String> = setOf(APPROVED, DENIED, REVOKED)
}

object AgentGrantScopes {
    /** 整台设备（`nodeId`）。 */
    const val ALL = "all"
    /** 单个 agent（`nodeId` + `agentKey`）。 */
    const val AGENT = "agent"
    val VALUES: Set<String> = setOf(ALL, AGENT)
}

object AgentGrantPolicies {
    /** 默认档：任何**已添加**的节点可见全部 agent（= 今天的零门控行为，零迁移）。 */
    const val AUTO = "auto"
    /** per-nodeId 白名单，缺省拒绝。 */
    const val BY_DEVICE = "byDevice"
    /** 未授权设备可申请 → 桌面端批准/拒绝。 */
    const val REQUEST = "request"
    val VALUES: Set<String> = setOf(AUTO, BY_DEVICE, REQUEST)
}
