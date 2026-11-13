// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

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

    /**
     * 本端增补：设备向宿主**读或撤免批规则**（cap `atr1`），设备 → 宿主。
     *
     * 免批表（宿主侧 `AgentToolApprovalStore`）此前只有写入口：设备在审批行上按
     * 「始终允许」，宿主落规则 —— 而设备的界面上既看不见也收不回。一份自己
     * 看不见、收不回的授权，用户没法对自己的机器负责；手机端尤其如此。
     *
     * 一帧两用（键区分，不新增第二张帧）：`tool` 缺席 = 读（宿主回当前快照）；
     * `tool` 在场 = 撤这一条（与 `nodeId + agentKey + tool + wsId` 逐字匹配）。
     * **响应不新造帧**：宿主收到后用它已有的兄弟帧 `agent-tool-rules-list`
     * 就地回全量快照（免批表是节点本地事实， grants 家族的「就地应答」先例，
     * 不需要 session-page 那套队列/宿主回填）。
     *
     * 撤销是**收紧方向**（让宿主重新开始问）：正在等批准的那一条不受影响，
     * 已经执行完的不会回滚 —— 回执说的是「下一次同工具请求会重新询问」。
     */
    const val AGENT_TOOL_RULES_REQUEST = "agent-tool-rules-request"

    /**
     * 本端增补：宿主把当前免批表**全量快照**回给设备（cap `atr1`），宿主 → 设备。
     *
     * 全量不是增量：免批规则按 tool 名记、行数少，总量预算远够；增量对账需要的
     * 键（rev / 序号）比这帧族本身还重。顺序 = 宿主表的最新优先。撤销后宿主也
     * 用它回更新后的快照 —— 设备侧只认这一种对账来源，不本地猜测。
     */
    const val AGENT_TOOL_RULES_LIST = "agent-tool-rules-list"

    /**
     * M4 跨端派单网道（规格 §6.2「地下铁」）：宿主 A 的 Leader 把子任务派到宿主 B 的
     * 插件（cap `agi1`）。请求-响应式 RPC 走 P2P SecureStream 点对点传输，
     * **不经群聊** —— 主群里一条跨设备交接文本都不产生，全群只看一张汇总看板卡。
     *
     * 与 `/v1/agent/run`（PPE-1 §6.4）的区别：run 是**单向执行状态宣告帧**，
     * invoke 是**带租约（deadlineMs）的任务派发请求**，目标节点必须经
     * `AgentExecAuthorizer` 授权后应答 `agent-invoke-result`。两种职责不共用一帧。
     *
     * `op` 两态：`task`（派单执行）与 `action`（卡片动作转发 —— 手机点了宿主插件
     * 卡片上的按钮，控制意图走网道直达宿主，绝不拼成群文本）。
     */
    const val AGENT_INVOKE = "agent-invoke"

    /** `agent-invoke` 的应答帧（cap `agi1`）：结果状态 + 可选的执行产出卡片。 */
    const val AGENT_INVOKE_RESULT = "agent-invoke-result"

    /**
     * `agent-channel`（cap `agc1`，M7-T2）：跨宿主任务委派——`assign`（所有权保留、
     * 调用方等结果）与 `transfer`（所有权转移、调用方退出）。先例即 [AGENT_INVOKE]：
     * 两种职责不共用一帧。op 两态互斥的判法在 `CodecChannel.validateChannel`。
     */
    const val AGENT_CHANNEL = "agent-channel"

    /**
     * `agent-channel` 的应答帧：`status` **复用** `CodecInvoke.InvokeResultStates` 词汇
     * （不另立第二份结果状态机）；产物只回执引用（`artifactIds`），与 T3 同一条
     * 「元数据 + 按需拉取」纪律。
     */
    const val AGENT_CHANNEL_RESULT = "agent-channel-result"

    /**
     * `agc1` = 跨宿主 Channel 委派（M7-T2）。声明它 = 认识 `agent-channel` /
     * `agent-channel-result` 两帧。宿主声明 = 编排器承接（互斥台账 + 插件执行）；
     * 手机也声明（D-017）：声明的语义是「解得动这族帧」，不是「我执行」——手机被
     * 误指名时回一帧诚实的 `no-executor`（决策包 §1.6 的设计意图），而不是沉默到
     * 对端租约超时。与 `ag1`「我是 agent 节点」不同层的判据。
     */
    const val CAP_AGENT_CHANNEL = "agc1"

    /**
     * 身份恢复 L2：恢复后的设备向**持有双边副本的对端**要历史（方案 §6.3）。
     *
     * 载荷为空对象 `{}`（严格白名单，多一个键整帧拒收）—— 要什么是隐含的：
     * 「你我这段会话的全部」。这个帧对**旧端是未知类型**（解码通过、known=false、
     * 静默忽略），所以恢复请求发进旧版本对端等于发进黑洞 —— 发送方按 Q5 的
     * 5 秒窗口放弃，不进离线队列。
     */
    const val RECOVERY_REQ = "recovery-req"

    /**
     * 身份恢复 L2：对端把双边副本里的历史**分页**回给恢复方（设备 → 恢复方）。
     *
     * fire-and-forget（Q5）：**严禁进可靠离线队列** —— 恢复方版本落后时静默阻塞
     * 的队列重试会把请求变成第 7 种「没收到」。分页在 64 KiB 整帧硬上限内，
     * 发送侧按字节预算切批（`CodecRecovery.RECOVERY_DATA_BATCH_BYTES`）。
     * 重复帧由接收侧去重表挡住；合并按消息 id 幂等。
     */
    const val RECOVERY_DATA = "recovery-data"

    /** 本实现认识的类型；未知类型解码通过但 known=false。 */
    val KNOWN: Set<String> = MessageSpecs.all.filter { it.known }.map { it.type }.toSet()

    /**
     * 可靠帧：必须回 ACK。
     *
     * 四张新帧（D3 的 workspace + D7 的三张 grant）全在：cap 门控保证它们只发给
     * 声明了对应能力的本实现对端，不存在"老端不回 ACK → 假故障"；反过来，
     * 不在这里的帧到不了 `inbound`（见上），状态广播也必须被收到。
     */
    // 请求帧同样必须在 RELIABLE 里：不在的话它到不了对端 inbound（见上文那条），
    // 表现是「手机点了加载更多，宿主根本没收到」。
    // 派单请求与结果都必须可靠：请求丢了靠重传兜底；结果丢了，发起方会把
    // 「已经完成」误判成「超时中断」—— 比丢请求更糟。
    // Channel 委派与结果同理（M7-T2）：assign 丢了 = 调用方干等；结果丢了 =
    // 所有权交接的状态机断在半空。
    val RELIABLE: Set<String> = MessageSpecs.all.filter { it.reliable }.map { it.type }.toSet()

    /**
     * 手机端 caps —— 尤其**不能**声明 `upd1`，否则桌面端会推自更新包。
     */
    val MOBILE_CAPS: List<String> =
        listOf(
            CAP_MOBILE_TEXT, CAP_MOBILE_RECORD, CAP_AGENT_SESSION, CAP_AGENT_QUEUE, CAP_AGENT_WORKSPACE,
            CAP_AGENT_GRANT, CAP_AGENT_SESSION_PAGE, CAP_TABLE_TEXT,
            // atr1（免批规则读撤）：手机发读/撤请求、收快照帧 —— 声明与处理同一批改动。
            CAP_AGENT_TOOL_RULES,
            // task-45：手机能把 agent 事件的附件元数据渲染成卡片 ⇒ 如实声明。
            // 发送方（桥/宿主）据此决定 agent-event 里带不带 attachment；不声明就只发文本占位。
            CAP_ATTACHMENT,
            // 手机端收发的群文本都可能带扩展键（senderId 与 agent 意图/状态/卡片）⇒ 如实声明。
            // 不声明的话「对端按能力门控」会连**我们自己的宿主**一起退化成不带归属的那一份。
            CAP_GROUP_TEXT_ATTRIBUTED,
            // 跨端派单网道（M4）：手机要能点宿主插件卡片上的按钮（action 请求只发
            // 给声明 agi1 的对端）；被误指名派单时也应答一帧诚实的 no-executor 拒绝。
            CAP_AGENT_INVOKE,
            // M7-T3（agf1）：手机收产物卡片时能显示端到端校验摘要 —— 如实声明（收方语义）。
            CAP_AGENT_ARTIFACT_VERIFIED,
            // M7-T2（agc1，D-017）：手机解得动 Channel 委派族帧。声明的语义是「这族帧
            // 发给我不会石沉大海」——被误指名时回一帧诚实的 no-executor（手机永不执行
            // 任务，执行只发生在宿主编排器），而不是沉默到对端租约超时。
            CAP_AGENT_CHANNEL,
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

    /**
     * `agf1` = `agent-artifact` 的**校验增量**（M7-T3）：声明它 = 认识 payload 里的
     * `sha256` / `summary` 两键。为什么单开而不是塞进 `agn1`：只有 `agn1` 的老端，
     * 其 `hasOnlyKeys` 会把带新键的帧**整帧拒收**——发送侧按对端 caps 决定带不带
     * （先例 `att1`/attachment），「两端必须同版本」的组合因此收窄为「声明 `agf1` 的对端」。
     */
    const val CAP_AGENT_ARTIFACT_VERIFIED = "agf1"

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
     * `fch1` = **文件字节走加密信道**（`FileChunkFrame`/`FileChunkAck` 帧族）。
     *
     * 与 `fd1` 是两件事，容易混：
     *  - `fd1`（历史能力）说的是"可以请求我免确认收下文件"，属于**要不要弹确认框**；
     *  - `fch1`（本能力）说的是"文件**字节**能从加密信道推给我"，属于**数据走哪条路**。
     *
     * 只有双方都声明 `fch1` 才走新数据面；不声明 = 老端 ⇒ 继续用 17879/17881 拨号拉取。
     * 这条不对称能力位就是灰度开关：一端升级、另一端没升级时不会出现"发送端推、收端等"。
     */
    const val CAP_FILE_CHANNEL = "fch1"

    /**
     * `scr1` = **投屏画面走独立加密会话**（与 `fch1` 同一族的第二块数据面）。
     *
     * 说的是「画面字节能不能经一条**专门**的加密会话推给我」，不是「能不能投屏」
     * （那是 `rv1`/`rvs1`）。两者必须分开：`rv1` 描述会话能不能建立，`scr1` 描述
     * 画面走哪条路。只有双方都声明 `scr1` 且对端档案里有 screenPort 才走新数据面，
     * 否则继续用 17879 明文 / 17882 跨网 TLS —— 这同样是不对称能力位作灰度开关。
     */
    const val CAP_SCREEN_CHANNEL = "scr1"

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
     * `ams1` = **消息级 agent 会话关联能力**（PPE-1 §6 扩展）：声明本能力表示节点支持在
     * `msg` 文本/富文本帧中携带可选 `agentSessionId`，把 IM 文本流关联到具体 Agent 会话线索。
     *
     * ## 本仓状态：**保留给茶话间互通；既不声明也不消费（0/0）**
     *
     * 全仓核对（task-51）：这个常量在本仓**没有任何**声明点（不在
     * [MOBILE_CAPS] / [AGENT_CAPS] / `RelaySettings.caps()` 里）也没有任何消费点
     * （没有一处校验/读取它）—— 是**唯一**一个 0/0 的 `CAP_*`。
     *
     * **刻意保留、不删**：它对应茶话间（对端实现）的互通约定，删掉这个常量就等于
     * 从我们这边抹掉那条约定的名字，将来对接时要重新发明一次。留着的代价只是一个常量。
     * 需要它的时候（真要发 `agentSessionId`）再按"声明 + 消费"两处一起补 —— 只补一处
     * 就是今天栽过两次的那种静默（`lnk1` / `att1` 都曾只加进清单、没进 `caps()`）。
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
     * `atr1` = **免批规则读撤能力**（本端增补）。
     *
     * 语义：声明方认识两张 tool-rules 帧（`agent-tool-rules-request` /
     * `agent-tool-rules-list`）。宿主声明它 = 会就地把快照回给设备（免批表就在
     * 节点里，与 grant 家族同一先例）；设备声明它 = 发得出读/撤请求、解得动
     * 快照帧 ——「声明 = 不会石沉大海」的同一条纪律（agn1）。
     *
     * 为什么单开而不是塞进 `agr1`：`agr1` 说的是「agent 帧按策略过滤」的授权
     * 回路，免批规则是它的**下游事实**（审批「始终允许」的产物）。老宿主认识
     * `agr1` 却不认识这两张新帧 —— 塞进旧 cap 会让新设备向老宿主发请求然后
     * 白等，正好违背声明语义。
     */
    const val CAP_AGENT_TOOL_RULES = "atr1"

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
     * 能力声明 `lnk1`：本机**能接受一条长连接**（K1 的手机→主机下行通道）。
     *
     * 由**主机**声明（桌面/agentNode），手机据此决定"这台机器可以建长连接"。不声明 = 按老端
     * 对待（手机不会去连）。与 `ag1` 同层：它声明的是"我这边有一个可接受连接的 TCP 监听"，
     * 不是用户偏好 —— 所以没有开关（开关在**发起侧**的手机上）。
     */
    const val CAP_LINK = "lnk1"

    /**
     * 能力声明 `att1`：本机**能把 agent 事件的附件元数据渲染成附件卡片**（task-45）。
     *
     * 由**接收方**（手机）声明。发送方（桥/宿主）据此决定要不要在 `agent-event` 里带
     * `attachment`：对端不声明就只发文本占位 —— 这样"旧端忽略未知字段"这条兼容性
     * 就有了**主动**保障（而不是指望旧端恰好宽容）。
     */
    const val CAP_ATTACHMENT = "att1"

    /**
     * `gta1` = **带发送者归属的群文本**（group-text attributed）。
     *
     * 声明它的意思是「我看得懂 `group-text` 载荷里本仓的扩展键」（`senderId` 与
     * agent 意图/状态/卡片族；`reasoning` 已退场，CoT 不进群）。
     * 发送侧**只给声明了它的成员**附带扩展键；没声明的成员收到的是**同一封信封 id**、
     * 但只含上游白名单内字段的另一份载荷。
     *
     * ## 为什么必须是独立能力位，而不是"复用 agn1 / 猜版本号"
     *
     * 上游茶话间（0.61.2）对 `group-text` 做的是**严格字段白名单**
     * （`kind/text/groupId/groupRev/mentions/resend/replyTo`）：多一个键 ⇒ `validatePayload`
     * 返回 false ⇒ **整帧丢弃**。而本仓的扩展键恰好在那份白名单之外，
     * 所以「agent 转发进群的消息」在茶话间侧是**静默消失**的：发送侧 SENT、本地落库成功、
     * 对端什么都没有。
     *
     * 用版本号判也不行：`ver` 是自由字符串（`relay-0.1.0` / `0.33.2` 都在线上出现过），
     * 拿它做能力判定等于让每个发送方各写一遍版本表。而 caps 是**由对端自己声明**的：
     * 不声明 = 不认识 = 不许发新键，这是唯一能把「谁认识什么」说清楚的机制。
     *
     * **老版节点即使有 `agn1` 也不能当作支持**：`agn1` 说的是 agent 会话帧，
     * 与群文本的字段白名单无关；把它当代理标志会让老构建继续整帧拒收。
     */
    const val CAP_GROUP_TEXT_ATTRIBUTED = "gta1"

    /**
     * `agi1` = **跨端派单网道**（规格 §6.2 / §6.3，M4）。
     *
     * 语义：声明方认识 `agent-invoke` / `agent-invoke-result` 两帧 ——
     * 能**收** invoke 并按 `AgentExecAuthorizer` 授权应答（哪怕应答一个诚实的拒绝：
     * 没有执行器、未授权、目标插件不存在，都是一帧如实的结果而不是沉默），
     * 能**发** action 请求（手机点击宿主插件卡片按钮的那条控制通路）。
     *
     * 两端（MOBILE_CAPS 与 AGENT_CAPS）都声明：手机永不承载任务执行，但手机会点卡片
     * 按钮 —— action 请求只发给声明了 `agi1` 的宿主；反过来手机也可能被恶意指名
     * 派单，如实声明后它会应答一帧 `no-executor` 拒绝，而不是让发起方白等租约超时。
     */
    const val CAP_AGENT_INVOKE = "agi1"

    /**
     * agent 节点的 caps：`ag1`（身份）+ 一个人类节点也会有的文本能力 + `agn1`（会话帧）
     * + `aws1`（工作区键与宣告帧，D1/D2/D3 已实现）+ `agr1`（授权回路，D7 已实现）
     * + `lnk1`（长连接下行，K1 已实现）+ `gta1`（带发送者归属的群文本）。
     *
     * `gta1` 两边都声明（这里与 [MOBILE_CAPS]）：宿主侧的转发路径同样会往群里写
     * `senderId = agent:<kind>`，不声明的话手机对**自家宿主**也会退化成不带归属的那一份。
     */
    val AGENT_CAPS: List<String> =
        listOf(
            CAP_AGENT, CAP_MOBILE_TEXT, CAP_AGENT_SESSION, CAP_AGENT_QUEUE,
            CAP_AGENT_WORKSPACE, CAP_AGENT_GRANT, CAP_AGENT_SESSION_PAGE, CAP_LINK,
            CAP_GROUP_TEXT_ATTRIBUTED, CAP_AGENT_INVOKE, CAP_AGENT_ARTIFACT_VERIFIED,
            // atr1（免批规则读撤）：宿主就地应答读/撤请求 —— 免批表在节点里
            // （ControlPlane.toolApprovals），与 grant 家族同一先例。
            CAP_AGENT_TOOL_RULES,
            // agc1（M7-T2，D-017）：宿主是 Channel 委派的承接方（编排器 + 插件执行器
            // 都在本进程）——「宣告与记账成对」的记账在这一刻补齐（AgentSessionRegistry
            // KDoc 纪律：声明 agc1 的同一批改动里必须有入站处理与诚实拒绝路径）。
            CAP_AGENT_CHANNEL,
        )

    /**
     * 本端**真的会处理**的已知类型，与 [UNHANDLED] 一起构成对 [KNOWN] 的一个划分。
     *
     * `SHARE` 在这里的依据只是**传输层**：它属于 [RELIABLE_TYPES]，入站帧会被 ACK；业务层
     * （`RelayController.CHAT_INBOUND_TYPES`，消费点在 `RelayControllerMessagesComponent`）不含它，帧会被丢掉，共享文件柜
     * 至今只有界面壳。这一处「声明了却没有实现」已如实写进 `PROTOCOL-COVERAGE.md` 的 §三/§五，
     * 由 `scripts/e2e/e2e_share.py` 的壳契约断言钉住。
     *
     * Channel 两帧（M7-T2，D-016 后半步）：接收宿主侧的编排器（agentNode
     * `ChannelOrchestrator`：互斥台账 / 租约 / 结果分派）与手机侧的诚实拒绝
     * （no-executor）已落地 ⇒ 进 HANDLED 并声明 [CAP_AGENT_CHANNEL]。
     */
    val HANDLED: Set<String> = MessageSpecs.all.filter { it.handled }.map { it.type }.toSet()

    /**
     * 本端**认得、但有意不处理**的已知类型。
     */
    val UNHANDLED: Set<String> = MessageSpecs.all.filter { it.known && !it.handled }.map { it.type }.toSet()
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
/**
 * 事件类型的**瞬态集合**（T-G3，不变量 3「实时 stdout 走抽屉管道不存库」）：
 * 这两类是流式增量 —— 落 SQLite 只会堆出「只写不读」的表（真机 57,644 行 / 5.2MB
 * 的现场），它们的可见性由实时管道（桌面 Inspector / 内存态）承担，重启即逝
 * 是**设计语义**：终局事实走 result 卡与台账，增量不留底。
 */
object AgentEventKinds {
    val EPHEMERAL = setOf("token.delta", "thought.delta")

    /** 唯一判定入口：这条事件类型要不要落持久层（调用方 = 域层两处 + UI 过滤）。 */
    fun shouldPersist(eventType: String): Boolean = eventType !in EPHEMERAL
}

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
    /** 单个工作区（`nodeId` + `agentKey` + `wsId`）。 */
    const val WORKSPACE = "workspace"
    val VALUES: Set<String> = setOf(ALL, AGENT, WORKSPACE)
}

object AgentGrantAccess {
    /** 只读：仅可浏览工作区、跟踪产物和会话流，不可下发任务或审批。 */
    const val RO = "ro"
    /** 读写：具备完整交互能力。 */
    const val RW = "rw"
    /** 协同管理员：具备治理与任务调度权限。 */
    const val ADMIN = "admin"
    /** 群主/宿主：物理工作区拥有者。 */
    const val OWNER = "owner"
    val VALUES: Set<String> = setOf(RO, RW, ADMIN, OWNER)
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

/**
 * `agent-ack.kind` 的取值域（§6.8）—— **全仓唯一一份**。
 *
 * ## 为什么必须只有一份
 *
 * 这个值同时出现在三个地方：编解码的校验（收得下吗）、控制面的入口白名单（认得吗）、
 * 台账的处置分派（怎么应用）。三处各写一份的下场已经发生过一次：
 * 客户端早就在发 `allow_session` / `revoke_session`，而校验白名单里只有正文那五个值 ——
 * 帧在**接收侧解码时整帧退回**，于是「本次免审」点了、界面显示已生效、宿主一直停在
 * `pending`。发送侧不校验（它只拼帧），所以这条错误在两端都不会报，只能靠人发现。
 *
 * ## 前五个是 §6.8 正文，后两个是本端增补
 *
 * | kind | 语义 | 出处 |
 * |---|---|---|
 * | `queued` | 收到排队请求 | §6.8 |
 * | `approved` / `denied` | 单条批准 / 拒绝 | §6.8 |
 * | `cancelled` | 收到取消 | §6.8 |
 * | `received` | 仅确认收到 | §6.8 |
 * | `allow_session` | 本会话免审（此后同类请求不再询问） | 本端增补 |
 * | `revoke_session` | 撤销本会话免审 | 本端增补 |
 * | `always` | 这个 **tool** 以后免审（宿主落授权表） | 本端增补 |
 *
 * 增补值复用 `kind` 这一个键（**不新增帧、不新增键**）：旧宿主不认识某个值时，
 * 只会停在原来的处置状态 —— 与它今天的行为一致，不会更糟。
 */
object AgentAckKinds {
    const val QUEUED = "queued"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val CANCELLED = "cancelled"
    const val RECEIVED = "received"

    /** 本会话免审：此后该会话的同类请求不再询问。 */
    const val ALLOW_SESSION = "allow_session"

    /** 撤销本会话免审。 */
    const val REVOKE_SESSION = "revoke_session"

    /**
     * **这个 tool 以后免审**（按 tool 名粒度，不是整 agent）。
     *
     * 与 [ALLOW_SESSION] 的区别是范围：那条只管一个会话，这条跨会话但只管一个工具名。
     * 宿主侧把它落进授权表（`AgentToolApprovalStore`），命中时不再打扰对端。
     */
    const val ALWAYS = "always"

    val VALUES: Set<String> = setOf(
        QUEUED, APPROVED, DENIED, CANCELLED, RECEIVED,
        ALLOW_SESSION, REVOKE_SESSION, ALWAYS,
    )

    fun isKnown(kind: String): Boolean = kind in VALUES
}

/**
 * `agent-approval.riskLevel` 的**取值域**（PPE-1 §14.3 三级风险，本端增补）。
 *
 * 单源纪律（AGENTS §3-#7）：键 + 取值域的字段只能有一份定义，且被编解码校验与
 * 所有消费方共同引用 —— 驱动默认值、UI 的确认判定都从这里取，不许各写一份字面量
 * （仓外脚本与第三方插件按 `PROTOCOL-EXTENSION.md` §14.3 对齐，键序同上：不认识
 * 某个值时旧端停在自己今天的行为上，不会更糟）。
 */
object AgentRiskLevels {
    /** L1 只读（查状态、读文件）。 */
    const val READONLY = "L1"

    /** L2 修改（写文件、改配置）。 */
    const val MODIFYING = "L2"

    /** L3 高危（执行命令、删除、外发）。 */
    const val HIGH = "L3"

    val VALUES: Set<String> = setOf(READONLY, MODIFYING, HIGH)

    fun isKnown(level: String): Boolean = level in VALUES
}

/**
 * `choices` 的**生态词汇**（§6.5 文档值域）：插件、`scripts/run-live-dsh-flow.py`、
 * 各测试夹具的历史写法。
 *
 * ## 为什么宿主兜底说这个词，而不是 [AgentAckKinds] 的 `approved` / `denied`（S4-2f 决议）
 *
 * `choices` 读它的是**第三方**：照文档写 `allow/deny` 的插件，遇上宿主兜底的
 * `approved/denied` 会把一次**明确声明**读成**沉默**。宿主改说生态词，两边一个词汇表；
 * `always` 两边同名照旧，`allow_session` 是本端增补的回执取值，照旧附带。
 * 词汇分叉只收在 `choices` 这一个字段上 —— 回执（`agent-ack.kind`）仍是 [AgentAckKinds]，
 * 取值域不动。
 */
object ApprovalChoiceWords {
    const val ALLOW = "allow"
    const val DENY = "deny"
}

/**
 * 宿主兜底的 `choices` CSV（§6.5「省略时宿主填什么」）：生态词两档 + 本端增补的两档回执。
 *
 * 这是**出站帧上远端能看到的取值**，全仓唯一来源（S4-2f 收口：此前拼在 agentNode 的
 * `ControlPlane` 里，用的是 `approved,denied`）。插件**显式声明**时以插件为准 ——
 * 兜底不许盖掉它的决定权。
 */
fun defaultApprovalChoicesCsv(): String = listOf(
    ApprovalChoiceWords.ALLOW,
    ApprovalChoiceWords.DENY,
    AgentAckKinds.ALLOW_SESSION,
    AgentAckKinds.ALWAYS,
).joinToString(",")
