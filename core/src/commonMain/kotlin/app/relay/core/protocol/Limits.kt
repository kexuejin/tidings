// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * 协议常量。数值与点对点通讯协议规范逐条对齐，改动必须同步更新
 * `core/src/commonTest/.../GoldenVectorTest.kt` 的 golden vectors。
 */
object Protocol {
    /** 信封版本，`Envelope.v` 必须严格等于它，否则整帧丢弃（reason=version）。 */
    const val VERSION: Int = 1

    const val DEFAULT_UDP_PORT: Int = 17878
    const val DEFAULT_TCP_PORT: Int = 17879

    /** 出站 UDP 单包上限（防 IP 分片，protocol §2）。 */
    const val UDP_MAX_PAYLOAD: Int = 1200

    /** 入站硬上限，超过直接丢弃（reason=oversize）。 */
    const val UDP_MAX_INBOUND: Int = 4096

    /** 文本走 UDP 的上限，超过改走 TCP 控制帧。 */
    const val TEXT_UDP_LIMIT: Int = 800

    /** 文本硬上限，超过不再发送。 */
    const val TEXT_TCP_LIMIT: Int = 4096
}

/** 字段长度上限（UTF-16 码元数），对应 TS 侧 `LIMITS`。 */
object Limits {
    const val NICK = 32
    const val COMPANY = 32
    const val DEPT = 32
    const val TEAM = 32
    const val HOST = 64
    const val VER = 16
    const val CAPS = 16
    const val CAP_ITEM = 16
    const val TYPE = 32
    const val ID = 64
    const val FROM = 64
    const val IP = 45
    const val AVATAR_HASH = 64

    /**
     * 群元数据（协议 §7.4）的字段上限，对应 TS `LIMITS` 的
     * `groupName` / `groupAdminHint` / `groupAdminHash` / `groupDescription` / `groupAnnounce`。
     *
     * `groupName` 在 TS 里是**写死的 32**（不在 `LIMITS` 对象里，直接 `isStr(meta.name, 32)`），
     * 这里提成常量只是为了让 `validateGroup` 读起来不是魔法数字 —— 值必须仍是 32。
     */
    const val GROUP_NAME = 32
    const val GROUP_ADMIN_HINT = 40
    const val GROUP_ADMIN_HASH = 64
    const val GROUP_DESCRIPTION = 200
    const val GROUP_ANNOUNCE = 1024

    /**
     * PPE-1 §6 的 agent-* 字段上限。
     *
     * 这些**不是**上游 `LIMITS` 的镜像（上游不认识 `agent-*`），是本项目扩展规范自己定的界：
     * 取值只保证「标识类字段不会无限长、描述类字段不会把一帧撑爆」。`body` 没有单独上限 ——
     * 它按 PPE-1 §6.2 与 `text` 同规矩受**通道**上限约束（UDP 800 / TCP 4096 字节）。
     */
    const val AGENT_SESSION_ID = 64
    const val AGENT_ALIAS = 64
    const val AGENT_KIND = 32
    const val AGENT_STATE = 16
    const val AGENT_TITLE = 200
    const val AGENT_EVENT_TYPE = 32

    /** 档案里的 TLS pin（`sha256:<64 hex>` = 71 字符，留一倍余量）。 */
    const val TLS_PIN = 128

    // ---- agent-event 的附件元数据（task-45）----
    /** 附件 id（与 DSH 侧 content 块的 attachmentId 同口径）。 */
    const val AGENT_ATTACHMENT_ID = 128
    /** MIME 类型，如 image/png。 */
    const val AGENT_MEDIA_TYPE = 64
    /** 展示用文件名（可空）。 */
    const val AGENT_ATTACHMENT_NAME = 128
    /** 尺寸/大小上限：宽高 ≤ 32768、字节 ≤ 512 MiB —— 超出只能是坏载荷。 */
    const val AGENT_ATTACHMENT_DIMENSION_MAX = 32768
    const val AGENT_ATTACHMENT_BYTES_MAX = 512L * 1024 * 1024
    const val AGENT_SUMMARY = 200
    const val AGENT_REF = 64
    const val AGENT_TASK_ID = 64
    const val AGENT_RUN_ID = 64
    const val AGENT_APPROVAL_ID = 64
    const val AGENT_TOOL = 64
    const val AGENT_NOTE = 200
    const val AGENT_PROMPT = 4096
    const val AGENT_CHOICES = 64
    /** Agent 事件的 body 在 TCP 通道上的上限（64 KB，给代码块、diff、深度思考、任务总结充足预算）。 */
    const val AGENT_BODY_TCP_LIMIT = 65536

    /**
     * 群文本**正文**在 TCP 通道上的上限（UTF-8 字节）。
     *
     * ## 为什么不能继续用 4096
     *
     * `Protocol.TEXT_TCP_LIMIT = 4096` 是 UDP 时代的产物（UDP 800 防 IP 分片，TCP 翻 5 倍
     * 而已）。但 agent 的长回复动辄 4000+ 字节：真机实测**140 条群消息**超过 4096
     * （最大 9902 字节），全部被接收侧 `utf8ByteLength(text) > textLimit` 判成 bad-payload
     * **整帧丢弃** —— 手机上一条都看不到，桌面端却好好地留着，用户描述成「没收到推送」。
     * 用户明确问的那条「全部完成。」(2530 字 / **4239 字节**) 就是超了 143 字节。
     *
     * 反证很干净：手机侧**没有任何一条**超过 4096 字节，最大一条恰好 3970 字节 ——
     * 不是「偶尔丢」，是「到 4096 就整齐地断掉」。
     *
     * ## 为什么是 32 KiB 而不是 65536
     *
     * 与 `agent-event` 的 body 同一档思路（那边早就放宽到 `AGENT_BODY_TCP_LIMIT`），
     * 但一帧的硬上限是 64 KiB（`Link.MAX_FRAME_BYTES`），而群文本还可以同时带
     * [GROUP_REASONING_BYTES]（24 KiB）的思考过程 —— 正文必须先给它们留出余量，
     * 否则「正文 + 思考」合起来把整帧顶爆，等于把刚修好的路径又堵回去。
     */
    const val GROUP_TEXT_TCP_LIMIT = 32 * 1024

    /**
     * 群消息可选字段 `reasoning`（思考过程）的 UTF-8 字节上限。
     *
     * 取 24 KiB 而不是跟 `text` 共用通道上限：一帧的硬上限是 64 KiB（`Link.MAX_FRAME_BYTES`），
     * 正文本身还可以占到 4 KiB，留足余量才能保证「正文 + 思考过程」不会把整帧顶爆。
     * 超长由发送侧截断（见 `NodeOutbound.sendGroupText`），接收侧按同一数值校验。
     */
    const val GROUP_REASONING_BYTES = 24 * 1024

    /**
     * PPE-1 增补 D1/D6（`docs/PROTOCOL-AGENT-WORKSPACE.md`）：
     * `agent-session` 的四个**可选**键。全部可选 = 旧接收端仍收得下；
     * `wsPath` 只进详情不进卡片主位（隐私纪律，父稿 §0）。
     */
    const val AGENT_ID = 64
    const val AGENT_WS_ID = 64
    const val AGENT_WS_TITLE = 200
    const val AGENT_WS_PATH = 512

    /**
     * PPE-1 增补 D2：接收侧「忽略未知键」的放大预算 —— 未知键个数与未知键
     * UTF-8 总长（键名 + 该键序列化值）。超限仍丢弃整帧（防放大）。
     */
    const val UNKNOWN_KEYS_MAX = 8
    const val UNKNOWN_KEY_BYTES_MAX = 1024

    /**
     * PPE-1 增补 D7：`agentKey` = `agentId ?? agentKind`（Agent 身份键），与 `agentId`
     * 同界；`reason` 是人话（申请理由），与 note 同界。
     */
    const val AGENT_GRANT_AGENT_KEY = 64
    const val AGENT_GRANT_REASON = 200

    /** §6.7 `agent-artifact`：产物标识、展示名与 MIME。 */
    const val AGENT_ARTIFACT_ID = 64
    const val AGENT_ARTIFACT_NAME = 200
    const val AGENT_ARTIFACT_MIME = 128

    const val PK_GAME = 16
    const val PK_VALUE = 16

    /** 屏幕协助（protocol §8.3）。 */
    const val SCREEN_SESSION_MAX = 1
    const val SCREEN_REQUEST_TIMEOUT_MS = 60_000L
    const val SCREEN_CONNECT_TIMEOUT_MS = 15_000L
    const val SCREEN_FRAME_TIMEOUT_MS = 5_000L
    const val SCREEN_IDLE_TIMEOUT_MS = 10_000L
    const val SCREEN_MAX_FRAME_BYTES = 512 * 1024
    const val SCREEN_MAX_EDGE = 1920
    const val SCREEN_MAX_PIXELS = 2_073_600
    const val SCREEN_MIN_FRAME_INTERVAL_MS = 100L
    const val SCREEN_MAX_BYTES_PER_SECOND = 5L * 1024 * 1024
    const val SCREEN_REQUEST_INTERVAL_MS = 20_000L
    const val SCREEN_TERMINAL_TTL_MS = 120_000L
    const val SCREEN_TERMINAL_MAX = 64
}

/**
 * 群成员上限，对应 TS 侧 `GROUP_MAX_MEMBERS`。
 *
 * 协议规范是 `= 200`（需求 F-MSG-4：
 * 由 50 调至 200）。这里曾错写 256 —— 256 是「能力数组上限 16」那一类猜出来的数字，
 * 不是从源码读出来的。凡与本文件冲突处，以 TS 源码为准。
 */
const val GROUP_MAX_MEMBERS: Int = 200

/** scan-ranges 报文单包条目上限（CIDR + 元数据，保证 ≤ UDP_MAX_PAYLOAD）。对齐 TS protocol.ts */
const val SCAN_RANGES_PER_PACKET: Int = 10

/**
 * 头像预设值范围，对应 TS `isAvatarPresetValue`：整数且落在
 * [`AVATAR_LEGACY_INITIAL_VALUE`, `AVATAR_INITIAL_COLOR_MAX`] 内。
 * -1 是 legacy「初始头像」，0..[AVATAR_ANIMAL_MAX_VALUE] 是「底色 × 动物」，
 * [AVATAR_INITIAL_COLOR_BASE]..[AVATAR_INITIAL_COLOR_MAX] 是初始底色。
 */
const val AVATAR_LEGACY_INITIAL_VALUE: Int = -1
const val AVATAR_ANIMAL_MAX_VALUE: Int = 199
const val AVATAR_INITIAL_COLOR_BASE: Int = 200
const val AVATAR_INITIAL_COLOR_MAX: Int = 209

/** 时间参数（protocol §9）。测试里可注入更短的值。 */
data class Timings(
    val presenceInterval: Long = 30_000,
    val offlineAfter: Long = 90_000,
    val sweepInterval: Long = 10_000,
    val entryReplyJitterBase: Long = 2_000,
    val entryReplyJitterMax: Long = 8_000,
    val aliveDedupWindow: Long = 10_000,
    val probeTimeout: Long = 2_000,
    val legacyProbeTimeout: Long = 20_000,
    val profileProbeInterval: Long = 10_000,
    val directedReplyInterval: Long = 1_000,
    /** ACK 重试退避；耗尽后回退 TCP 一次。 */
    val ackRetrySchedule: List<Long> = listOf(1_000, 2_000, 4_000),
    /** TCP 回退等待 ACK 的超时（精确值，来自 messenger.ts:225-264）。 */
    val tcpAckTimeout: Long = 7_000,
    /** 离线补发队列 TTL。 */
    val queueTtl: Long = 7L * 24 * 3_600_000,
    val queueMaxPerPeer: Int = 200,
    /** 去重窗口。 */
    val dedupTtl: Long = 24L * 3_600_000,
    /** 撤回窗口。 */
    val recallWindow: Long = 5 * 60_000,
)

val DEFAULT_TIMINGS: Timings = Timings()
