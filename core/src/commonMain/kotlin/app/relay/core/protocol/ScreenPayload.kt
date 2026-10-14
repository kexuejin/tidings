// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.Serializable

/**
 * 屏幕协助会话操作指令（op），对齐屏幕协助协议规范 §8.3。
 */
object ScreenOps {
    const val REQUEST = "request"
    const val ACCEPT = "accept"
    const val REJECT = "reject"
    const val END = "end"
}

/**
 * 屏幕协助拒绝原因码。
 */
object ScreenRejectReasons {
    const val DECLINED = "declined"
    const val BUSY = "busy"
    const val UNSUPPORTED = "unsupported"
    const val PERMISSION_DENIED = "permission-denied"
    const val CAPTURE_FAILED = "capture-failed"
    const val TIMEOUT = "timeout"

    val ALL: Set<String> = setOf(DECLINED, BUSY, UNSUPPORTED, PERMISSION_DENIED, CAPTURE_FAILED, TIMEOUT)
}

/**
 * 屏幕协助结束原因码。
 */
object ScreenEndReasons {
    const val USER = "user"
    const val CANCELED = "canceled"
    const val TIMEOUT = "timeout"
    const val LOCKED = "locked"
    const val SUSPENDED = "suspended"
    const val DISCONNECTED = "disconnected"
    const val CAPTURE_ENDED = "capture-ended"
    const val PROTOCOL_ERROR = "protocol-error"
    const val APP_EXIT = "app-exit"

    val ALL: Set<String> = setOf(USER, CANCELED, TIMEOUT, LOCKED, SUSPENDED, DISCONNECTED, CAPTURE_ENDED, PROTOCOL_ERROR, APP_EXIT)
}

/**
 * screen op: request
 *
 * **`op` 不能有默认值**：`wireJson` 是 `encodeDefaults = false`（Envelope.kt:13 立的规矩），
 * 给了默认值这个键就**整个不上线**；而 `validateScreen` 与屏幕协议规范都要求
 * `op` 必须存在 —— 缺了它，自己发的 screen 帧会死在自己的解码器上，
 * 症状与"对端不投屏"一模一样（静默）。投屏接线时用回环测试才暴露。
 */
@Serializable
data class ScreenRequestPayload(
    val op: String,
    val sessionId: String,
)

/**
 * screen op: accept
 */
@Serializable
data class ScreenAcceptPayload(
    val op: String,
    val sessionId: String,
    val token: String,
)

/**
 * screen op: reject
 */
@Serializable
data class ScreenRejectPayload(
    val op: String,
    val sessionId: String,
    val reason: String,
)

/**
 * screen op: end
 */
@Serializable
data class ScreenEndPayload(
    val op: String,
    val sessionId: String,
    val reason: String,
)

/**
 * 屏幕协助领域分派消息。
 */
sealed interface ScreenMessage {
    val sessionId: String

    data class Request(val payload: ScreenRequestPayload) : ScreenMessage {
        override val sessionId: String get() = payload.sessionId
    }

    data class Accept(val payload: ScreenAcceptPayload) : ScreenMessage {
        override val sessionId: String get() = payload.sessionId
        val token: String get() = payload.token
    }

    data class Reject(val payload: ScreenRejectPayload) : ScreenMessage {
        override val sessionId: String get() = payload.sessionId
        val reason: String get() = payload.reason
    }

    data class End(val payload: ScreenEndPayload) : ScreenMessage {
        override val sessionId: String get() = payload.sessionId
        val reason: String get() = payload.reason
    }
}

/**
 * TCP 屏幕帧定义，对齐屏幕帧协议规范。
 */
sealed interface ScreenTcpFrame {
    val sessionId: String

    data class Open(
        val from: String,
        override val sessionId: String,
        val token: String,
    ) : ScreenTcpFrame

    data class Ready(
        override val sessionId: String,
    ) : ScreenTcpFrame

    data class Next(
        override val sessionId: String,
        val seq: Long,
    ) : ScreenTcpFrame

    data class Frame(
        override val sessionId: String,
        val seq: Long,
        val width: Int,
        val height: Int,
        val len: Int,
    ) : ScreenTcpFrame
}

enum class ScreenRole {
    Viewer,
    Sharer,
}

enum class ScreenPhase {
    Requesting,
    AwaitingConsent,
    Preparing,
    Connecting,
    Active,

    /** 正常收场：用户结束、对方结束、锁屏/休眠、进程退出。 */
    Ended,

    /**
     * 失败收场：被拒绝、占线、对方不支持、抓屏失败、等待超时、断链、协议出错。
     *
     * 之前这个档案**不存在**，`finishScreenSession` 把上面那些失败原因也一律写成 [Ended]，
     * 于是 UI 侧的终态守卫 `if (session.isTerminal && !session.isFailed) return`
     * （`ui/components/RemoteViewSessionDialog.kt`）
     * 直接把弹窗抹掉 —— 用户看不到任何原因，也分不清"对方拒绝了"和"我自己挂了"。
     * 终态判据因此从"只认 Ended"扩成「Ended ∪ Error」，见 [ScreenSessionState.isTerminal]。
     *
     * 这个枚举**不进 wire**：全仓没有任何序列化/编解码引用（只有内存状态机、UI 与截图测试），
     * 加成员不改任何一帧的字节。
     */
    Error,
}

enum class ScreenMode {
    Auto,
    Economy,
    Standard,
    Smooth,
}

/**
 * 屏幕协助核心会话模型，对齐屏幕协助状态模型 ScreenState。
 */
data class ScreenSessionState(
    val revision: Long = 0,
    val sessionId: String,
    val peerId: String,
    val peerName: String,
    val peerIp: String,
    val role: ScreenRole,
    val phase: ScreenPhase,
    val mode: ScreenMode = ScreenMode.Auto,
    val targetFps: Int = 10,
    val reason: String? = null,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
) {
    val isActive: Boolean get() = phase == ScreenPhase.Active

    /**
     * 终态：正常结束或失败结束，会话都不再占着接收器/发送器/采集器。
     *
     * **语义扩张**：这个判据以前叫 `isEnded`、等于 `phase == Ended`，加了
     * [ScreenPhase.Error] 之后必须同时覆盖失败终态。
     *
     * 理由**不是**"否则守卫会把失败态误判成空闲"（初稿这么写过，verifier 实测是写反的：
     * 不扩宽时 `!isEnded` 对 `Error` 恰好为 `true`，守卫只会更保守地**挡住**新请求；而且
     * `finishScreenSession` 的清理（停接收器/发送器/采集器、置死帧源）发生在写相位**之前**，
     * 从来不会留下遗留资源）。真实后果在反方向：`Error` 不算终态，这个会话就永远算不上
     * "已经收场"，发起前那道 `if (current != null && !current.isTerminal)` 会一直说"还占着"
     * ⇒ **单侧永久锁死**：既发不出新会话，也没有任何路径把它清成空闲。
     *
     * 这次**顺手把旧名删掉、只留 `isTerminal`**：一个名字一个含义。保留一个"字面写着
     * 已结束、实际含失败"的 `isEnded` 别名，只会让下一个读代码的人随手挑一个 —— 这正是
     * "名字看着对、语义在别处"的病根形状。
     */
    val isTerminal: Boolean get() = phase == ScreenPhase.Ended || phase == ScreenPhase.Error

    /** 失败终态。UI 必须把 [reason] 翻成用户看得懂的原因，并留一个关掉的出口。 */
    val isFailed: Boolean get() = phase == ScreenPhase.Error
}

/**
 * 失败原因码集合（拒绝原因 + 结束原因里属于"没成功"的那几个）。
 *
 * [ScreenRejectReasons.TIMEOUT] 与 [ScreenEndReasons.TIMEOUT] 同值（都是 `"timeout"`），
 * 集合会自动去重，所以只列一次就同时覆盖"没人应答"与"中途超时"两种情况。
 */
private val SCREEN_FAILURE_REASONS: Set<String> = setOf(
    ScreenRejectReasons.DECLINED,
    ScreenRejectReasons.BUSY,
    ScreenRejectReasons.UNSUPPORTED,
    ScreenRejectReasons.PERMISSION_DENIED,
    ScreenRejectReasons.CAPTURE_FAILED,
    ScreenRejectReasons.TIMEOUT,
    ScreenEndReasons.DISCONNECTED,
    ScreenEndReasons.CAPTURE_ENDED,
    ScreenEndReasons.PROTOCOL_ERROR,
)

/**
 * 结束原因码 → 终态相位。
 *
 * 这是 `ScreenPhase.Error` 的**唯一生产者口径**：会话收尾只有这一个入口
 * （`RelayControllerScreen.finishScreenSession`），它以前不看原因一律写 [ScreenPhase.Ended]，
 * 现在按这张表分流。`user`/`canceled`/`locked`/`suspended`/`app-exit` 是正常收场，
 * 其余是失败收场。
 *
 * **它只看原因码、不看是谁发起的** —— 这是刻意的：`declined`/`unsupported`/`timeout` 这几个
 * 码两侧语义相反（对端发来 = 对端拒绝/不支持/没人应答 = 失败；本侧发出 = 我拒绝/我这台机器
 * 不支持/我这边没人应答 = 正常收场），同一个字符串推不出同一个相位。所以发起方由调用点
 * 显式声明（`finishScreenSession(..., localDecision = true)` 直接落 [ScreenPhase.Ended]），
 * 不在这里按码打特例 —— 特例会把"对方真的拒绝了我"也一起洗成正常收场。
 */
fun screenTerminalPhaseFor(reason: String?): ScreenPhase =
    if (reason != null && reason in SCREEN_FAILURE_REASONS) ScreenPhase.Error else ScreenPhase.Ended


