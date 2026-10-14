// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * §8 文件收发的协议常量、载荷类型与 TCP 控制帧编解码。
 *
 * 三条规则与 [Codec] 一致，违反任何一条都会让标准对端拒帧或静默丢帧：
 * 1. 出站 `@Serializable` 类的字段声明顺序 = TS 对象字面量的 key 顺序（TS 用 `JSON.stringify`）；
 * 2. 真可选字段给 `= null`，靠 `explicitNulls=false` 整条省略；
 * 3. 必须上线的字段绝不给默认值（`encodeDefaults=false` 会把有默认值的字段整条省略）。
 *
 * 入站方向**不用**这些类：手写从 `JsonObject` 逐字段解，为的是复刻 TS `decodeTcpFrameObject`
 * 的字段检查顺序与拒绝原因字符串。TS 源码在 `src/main/net/frame.ts`。
 */
object FileTransfer {

    /** offer 单包最多几条 file entry。超了就按同一 transferId 拆多包（`protocol.ts:354`）。 */
    const val OFFER_FILES_PER_PACKET: Int = 6

    /** offer 分包重组超时（`protocol.ts:356`）。 */
    const val OFFER_ASSEMBLE_TIMEOUT: Long = 10_000L

    /** 单次传输最多多少文件。内存 DoS 护栏（`protocol.ts:358`）。 */
    const val MAX_FILES_PER_TRANSFER: Int = 2000

    /** 接收方预哈希 / 发送方排队 / 哈希收尾期间的 wait 心跳间隔。 */
    const val PULL_WAIT_HEARTBEAT: Long = 20_000L

    /** 接收方 pull 空闲超时。超时判失败，`.part` 留着可续传重试。 */
    const val PULL_IDLE_TIMEOUT: Long = 60_000L

    /** 普通私聊/群文件自发出起的认领窗口。 */
    const val FILE_OFFER_TTL: Long = 24L * 60 * 60 * 1000

    /** 图片自动接收阈值（上游定的是 20MB）。超限退化为普通文件流程。 */
    const val IMG_AUTO_ACCEPT: Long = 20L * 1024 * 1024

    /** 群聊图片内联上限。超限按普通文件发送，需手动领取。 */
    const val GROUP_IMG_AUTO_ACCEPT: Long = 10L * 1024 * 1024

    /** 表格图片消息的原始 TSV 文本视图上限。 */
    const val TABLE_TEXT_LIMIT_BYTES: Int = Protocol.TEXT_TCP_LIMIT

    /**
     * TCP 控制帧体上限，与 TS `frame.ts` 的 `MAX_FRAME` 逐字一致。
     * 注意这是**帧体**上限：`pull-ok` 之后紧跟的裸流字节不受此限。
     */
    const val MAX_FRAME: Int = 64 * 1024

    /** `err.reason` 上限，与 TS `ERR_REASON_MAX` 一致。 */
    const val ERR_REASON_MAX: Int = 128

    /**
     * JS `Number.MAX_SAFE_INTEGER`。
     *
     * Kotlin 的 `longOrNull` 能一路收到 `Long.MAX_VALUE`，而 TS 的 `Number.isSafeInteger`
     * 在 2^53 以上返回 false。不复刻这条界线，就会收下一个 JS 端根本无法精确表示的 offset。
     */
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
}

/** `file-ctl` 的 op 取值。 */
object FileCtlOps {
    const val OFFER = "offer"
    const val ACCEPT = "accept"
    const val DECLINE = "decline"
    const val CANCEL = "cancel"
    const val DIRECT = "direct"

    /** 不带文件清单的短包（只有 op + transferId）。 */
    val SIMPLE: Set<String> = setOf(ACCEPT, DECLINE, CANCEL, DIRECT)
}

/** `FileCtlOffer.purpose` 取值。 */
object FilePurpose {
    const val IMAGE = "image"
    const val STICKER = "sticker"
    const val UPDATE = "update"
    const val SHARE_GET = "share-get"
    const val SHARE_PUT = "share-put"

    val ALL: Set<String> = setOf(IMAGE, STICKER, UPDATE, SHARE_GET, SHARE_PUT)

    /** 媒体类用途（进聊天记录，可自动接收）。 */
    val MEDIA: Set<String> = setOf(IMAGE, STICKER)
}

/** TCP 帧的 `type` 取值，与 TS `MSG_TYPES` 之外的帧族一一对应。 */
object TcpFrameTypes {
    const val PULL = "pull"
    const val PULL_OK = "pull-ok"
    const val DONE = "done"
    const val FINISH = "finish"
    const val ERR = "err"
    const val WAIT = "wait"
    const val MSG = "msg"
    const val MSG_ACK = "msg-ack"
    const val SCREEN_OPEN = "screen-open"
    const val SCREEN_READY = "screen-ready"
    const val SCREEN_NEXT = "screen-next"
    const val SCREEN_FRAME = "screen-frame"

    /** 文件传输族：只有这些帧参与 `TransferServer` 的 pull 状态机。 */
    val CONTROL: Set<String> = setOf(PULL, PULL_OK, DONE, FINISH, ERR, WAIT)

    /** 投屏族。手机没有投屏处理器，收到即断链——与 TS「无 handler 就 destroy」等价。 */
    val SCREEN: Set<String> =
        setOf(SCREEN_OPEN, SCREEN_READY, SCREEN_NEXT, SCREEN_FRAME)
}

/** TCP 帧的拒绝原因，与 TS `frame.ts` 逐字一致（对外语义，不能改写）。 */
object TcpRejectReason {
    const val BAD_RAW_LENGTH = "bad-raw-length"
    const val BAD_FRAME_LENGTH = "bad-frame-length"
    const val BAD_FRAME_JSON = "bad-frame-json"
    const val BAD_FRAME_SHAPE = "bad-frame-shape"
}

// ---------------------------------------------------------------------------
// 出站载荷：声明顺序即线上 key 顺序，勿动
// ---------------------------------------------------------------------------

/**
 * 文件清单里的一条。
 *
 * [path] 是**相对路径**（保留目录结构）。接收侧落盘前必须先过 [app.relay.core.fs.sanitizeRelativePath]——
 * 这是对端可控的字符串，直接拼路径就是路径穿越。
 */
@Serializable
data class FileMeta(
    val fileId: String,
    val path: String,
    val size: Long,
    val isDir: Boolean? = null,
)

/**
 * `op:'offer'` 的完整文件清单包。
 *
 * key 顺序照抄 TS `FileCtlOffer`（`protocol.ts:466`）：op, transferId, msgId, seq, total,
 * files, totalSize, fileCount, rootName, purpose, expiresAt, tableText, tableTextTruncated,
 * groupId, groupRev。老端按 `hasOnlyKeys` 严格比对 key 集合，多一个键整帧丢弃。
 */
@Serializable
data class FileCtlOffer(
    val op: String,
    val transferId: String,
    val msgId: String? = null,
    val seq: Int,
    val total: Int,
    val files: List<FileMeta>,
    val totalSize: Long,
    val fileCount: Int,
    val rootName: String,
    val purpose: String? = null,
    val expiresAt: Long? = null,
    val tableText: String? = null,
    val tableTextTruncated: Boolean? = null,
    val groupId: String? = null,
    val groupRev: Int? = null,
    val isFolder: Boolean? = null,
)

/** `accept` / `decline` / `cancel` / `direct`：只有 op 与 transferId。 */
@Serializable
data class FileCtlSimple(
    val op: String,
    val transferId: String,
)

// ---------------------------------------------------------------------------
// 出站 TCP 控制帧：同样按 TS 对象字面量的 key 顺序声明
// ---------------------------------------------------------------------------

/** 接收方发起拉取。[offset] > 0 表示续传。 */
@Serializable
data class PullFrame(
    val type: String,
    val from: String,
    val transferId: String,
    val fileId: String,
    val offset: Long,
)

/** 发送方答应拉取：紧跟 [len] 字节裸流。 */
@Serializable
data class PullOkFrame(
    val type: String,
    val fileId: String,
    val len: Long,
)

/** 发送方给出的整文件摘要，接收方据此校验 `.part` 是否可转正。 */
@Serializable
data class DoneFrame(
    val type: String,
    val fileId: String,
    val sha256: String,
)

/** 一次 transfer 的全部文件发完。 */
@Serializable
data class FinishFrame(
    val type: String,
    val transferId: String,
)

/** 发送方拒绝或中断，[reason] 是短机器串。 */
@Serializable
data class ErrFrame(
    val type: String,
    val reason: String,
)

/**
 * 排队/预哈希/哈希收尾的保活心跳。
 *
 * **只能发给声明了 `tw1` 的对端**：老端遇到不认识的帧类型会直接断链。
 */
@Serializable
data class WaitFrame(
    val type: String,
)

// ---------------------------------------------------------------------------
// 入站解码结果
// ---------------------------------------------------------------------------

/** 已经解出来的文件传输控制帧。 */
sealed interface TcpControlFrame {
    data class Pull(val from: String, val transferId: String, val fileId: String, val offset: Long) :
        TcpControlFrame

    data class PullOk(val fileId: String, val len: Long) : TcpControlFrame

    data class Done(val fileId: String, val sha256: String) : TcpControlFrame

    data class Finish(val transferId: String) : TcpControlFrame

    data class Err(val reason: String) : TcpControlFrame

    data object Wait : TcpControlFrame
}

/**
 * 一个 TCP 帧的解码结果。
 *
 * [Screen] 带解出来的 [ScreenTcpFrame]（首帧 `screen-open` 要拿 from/sessionId/token 去核对
 * 会话）；非首帧或核对不上时调用方断链 —— 与 TS `openScreen` 返回 null 时 destroy 等价。
 */
sealed interface TcpFrameDecode {
    data class Control(val frame: TcpControlFrame) : TcpFrameDecode
    data class Msg(val envelope: Envelope) : TcpFrameDecode
    data class MsgAck(val ackFor: String) : TcpFrameDecode
    data class Screen(val type: String, val screenFrame: ScreenTcpFrame? = null) : TcpFrameDecode
    data class Rejected(val reason: String) : TcpFrameDecode
}

// ---------------------------------------------------------------------------
// 编解码
// ---------------------------------------------------------------------------

/**
 * TCP 帧编解码器，逐条对齐 TS `src/main/net/frame.ts`。
 *
 * 出站：JSON 体 → [TcpFrame.encode] 加 4 字节大端长度前缀。
 * 入站：先 [TcpFrame.decodeLength] 取长度（0 或 > [FileTransfer.MAX_FRAME] 直接拒），
 * 再 [decodeBody] 解 JSON 与形状。
 */
object TcpFrames {

    /** 把帧对象序列化成 JSON 体（不含长度前缀）。 */
    fun encodeBody(frame: Any): String = when (frame) {
        is PullFrame -> Codec.wireJson.encodeToString(PullFrame.serializer(), frame)
        is PullOkFrame -> Codec.wireJson.encodeToString(PullOkFrame.serializer(), frame)
        is DoneFrame -> Codec.wireJson.encodeToString(DoneFrame.serializer(), frame)
        is FinishFrame -> Codec.wireJson.encodeToString(FinishFrame.serializer(), frame)
        is ErrFrame -> Codec.wireJson.encodeToString(ErrFrame.serializer(), frame)
        is WaitFrame -> Codec.wireJson.encodeToString(WaitFrame.serializer(), frame)
        is FileCtlOffer -> Codec.wireJson.encodeToString(FileCtlOffer.serializer(), frame)
        is FileCtlSimple -> Codec.wireJson.encodeToString(FileCtlSimple.serializer(), frame)
        is ScreenTcpFrame.Open -> """{"type":"screen-open","from":"${frame.from}","sessionId":"${frame.sessionId}","token":"${frame.token}"}"""
        is ScreenTcpFrame.Ready -> """{"type":"screen-ready","sessionId":"${frame.sessionId}"}"""
        is ScreenTcpFrame.Next -> """{"type":"screen-next","sessionId":"${frame.sessionId}","seq":${frame.seq}}"""
        is ScreenTcpFrame.Frame -> """{"type":"screen-frame","sessionId":"${frame.sessionId}","seq":${frame.seq},"width":${frame.width},"height":${frame.height},"len":${frame.len}}"""
        else -> error("不认识的帧类型：${frame::class.simpleName}")
    }

    /** 完整帧字节：4 字节大端长度前缀 + JSON 体。 */
    fun encode(frame: Any): ByteArray = TcpFrame.encode(encodeBody(frame).encodeToByteArray())

    /**
     * `{"type":"msg","envelope":…}`。
     *
     * 裸信封会被对端 `decodeTcpFrameObject` 的白名单拒掉，必须包这一层。
     * 与 [app.relay.core.transport.TcpFramedChannel] 里那份私有的实现同形——那份只管 P1 出站，
     * 这份是 TCP 监听器共用的家。
     */
    fun encodeMsgFrame(envelope: Envelope): String =
        """{"type":"${TcpFrameTypes.MSG}","envelope":${Codec.encode(envelope)}}"""

    /**
     * 解一个已经取好长度前缀的帧体。
     *
     * @param text 帧体的 UTF-8 文本。
     */
    fun decodeBody(text: String): TcpFrameDecode {
        val raw = try {
            Codec.wireJson.parseToJsonElement(text)
        } catch (_: Exception) {
            return TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_JSON)
        }
        return decodeObject(raw)
    }

    /**
     * 校验并解一个帧对象，对齐 TS `decodeTcpFrameObject`。
     *
     * 每种帧都用 `hasOnlyKeys` 严格比对 key 集合：多一个键就整帧拒。
     */
    fun decodeObject(raw: JsonElement?): TcpFrameDecode {
        val o = raw as? JsonObject
            ?: return TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE)
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE)

        return when (type) {
            TcpFrameTypes.PULL -> {
                if (!hasOnlyKeys(o, setOf("type", "from", "transferId", "fileId", "offset"))) {
                    return TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE)
                }
                val from = boundedString(o["from"], Limits.FROM) ?: return badShape()
                val transferId = boundedString(o["transferId"], Limits.ID) ?: return badShape()
                val fileId = boundedString(o["fileId"], Limits.ID) ?: return badShape()
                val offset = nonNegativeSafeInteger(o["offset"]) ?: return badShape()
                TcpFrameDecode.Control(TcpControlFrame.Pull(from, transferId, fileId, offset))
            }

            TcpFrameTypes.PULL_OK -> {
                if (!hasOnlyKeys(o, setOf("type", "fileId", "len"))) return badShape()
                val fileId = boundedString(o["fileId"], Limits.ID) ?: return badShape()
                val len = nonNegativeSafeInteger(o["len"]) ?: return badShape()
                TcpFrameDecode.Control(TcpControlFrame.PullOk(fileId, len))
            }

            TcpFrameTypes.DONE -> {
                if (!hasOnlyKeys(o, setOf("type", "fileId", "sha256"))) return badShape()
                val fileId = boundedString(o["fileId"], Limits.ID) ?: return badShape()
                val sha = boundedString(o["sha256"], Limits.AVATAR_HASH) ?: return badShape()
                if (!isHex64(sha)) return badShape()
                TcpFrameDecode.Control(TcpControlFrame.Done(fileId, sha))
            }

            TcpFrameTypes.FINISH -> {
                if (!hasOnlyKeys(o, setOf("type", "transferId"))) return badShape()
                val transferId = boundedString(o["transferId"], Limits.ID) ?: return badShape()
                TcpFrameDecode.Control(TcpControlFrame.Finish(transferId))
            }

            TcpFrameTypes.ERR -> {
                if (!hasOnlyKeys(o, setOf("type", "reason"))) return badShape()
                val reason = boundedString(o["reason"], FileTransfer.ERR_REASON_MAX)
                    ?: return badShape()
                TcpFrameDecode.Control(TcpControlFrame.Err(reason))
            }

            TcpFrameTypes.WAIT -> {
                if (!hasOnlyKeys(o, setOf("type"))) return badShape()
                TcpFrameDecode.Control(TcpControlFrame.Wait)
            }

            TcpFrameTypes.MSG -> {
                if (!hasOnlyKeys(o, setOf("type", "envelope"))) return badShape()
                // 这里必须用 TCP 的文本上限：TS decodeTcpFrameObject 走的是 TCP 路径。
                val decoded = Codec.decodeEnvelopeObject(o["envelope"], Protocol.TEXT_TCP_LIMIT)
                when (decoded) {
                    is DecodeResult.Ok -> if (decoded.known) {
                        TcpFrameDecode.Msg(decoded.env)
                    } else {
                        badShape()
                    }

                    is DecodeResult.Rejected -> badShape()
                }
            }

            TcpFrameTypes.MSG_ACK -> {
                if (!hasOnlyKeys(o, setOf("type", "ackFor"))) return badShape()
                val ackFor = boundedString(o["ackFor"], Limits.ID) ?: return badShape()
                TcpFrameDecode.MsgAck(ackFor)
            }

            TcpFrameTypes.SCREEN_OPEN -> {
                if (!hasOnlyKeys(o, setOf("type", "from", "sessionId", "token"))) return badShape()
                val from = boundedString(o["from"], Limits.FROM) ?: return badShape()
                val sessionId = boundedString(o["sessionId"], Limits.ID) ?: return badShape()
                val token = boundedString(o["token"], Limits.ID) ?: return badShape()
                TcpFrameDecode.Screen(type, ScreenTcpFrame.Open(from, sessionId, token))
            }

            TcpFrameTypes.SCREEN_READY -> {
                if (!hasOnlyKeys(o, setOf("type", "sessionId"))) return badShape()
                val sessionId = boundedString(o["sessionId"], Limits.ID) ?: return badShape()
                TcpFrameDecode.Screen(type, ScreenTcpFrame.Ready(sessionId))
            }

            TcpFrameTypes.SCREEN_NEXT -> {
                if (!hasOnlyKeys(o, setOf("type", "sessionId", "seq"))) return badShape()
                val sessionId = boundedString(o["sessionId"], Limits.ID) ?: return badShape()
                val seq = nonNegativeSafeInteger(o["seq"])?.takeIf { it > 0 } ?: return badShape()
                TcpFrameDecode.Screen(type, ScreenTcpFrame.Next(sessionId, seq))
            }

            TcpFrameTypes.SCREEN_FRAME -> {
                if (!hasOnlyKeys(o, setOf("type", "sessionId", "seq", "width", "height", "len"))) return badShape()
                val sessionId = boundedString(o["sessionId"], Limits.ID) ?: return badShape()
                val seq = nonNegativeSafeInteger(o["seq"])?.takeIf { it > 0 } ?: return badShape()
                val width = nonNegativeSafeInteger(o["width"])?.toInt()?.takeIf { it > 0 } ?: return badShape()
                val height = nonNegativeSafeInteger(o["height"])?.toInt()?.takeIf { it > 0 } ?: return badShape()
                val len = nonNegativeSafeInteger(o["len"])?.toInt()?.takeIf { it in 1..Limits.SCREEN_MAX_FRAME_BYTES } ?: return badShape()
                if (!ScreenImageMetadata.isScreenSize(width, height)) return badShape()
                TcpFrameDecode.Screen(type, ScreenTcpFrame.Frame(sessionId, seq, width, height, len))
            }

            else -> TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE)
        }
    }

    private fun badShape(): TcpFrameDecode =
        TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE)

    /** TS `hasOnlyKeys`：键数相等且每个键都在白名单里。 */
    fun hasOnlyKeys(o: JsonObject, keys: Set<String>): Boolean =
        o.size == keys.size && o.keys.all { it in keys }

    /** TS `isBoundedString`：非空字符串且长度（UTF-16 码元，等价 JS `.length`）不超上限。 */
    fun boundedString(x: JsonElement?, max: Int): String? {
        val p = x as? JsonPrimitive ?: return null
        if (!p.isString) return null
        val s = p.content
        if (s.isEmpty() || s.length > max) return null
        return s
    }

    /**
     * TS `isNonNegativeSafeInteger`。
     *
     * Kotlin 侧必须自己卡 2^53：`longOrNull` 会一路收到 `Long.MAX_VALUE`。
     */
    fun nonNegativeSafeInteger(x: JsonElement?): Long? {
        // 复用 Codec 的 intOf：字符串与 JsonNull 都不算整数，1.5 也会被它挡掉。
        val v = Codec.intOf(x) ?: return null
        if (v < 0 || v > FileTransfer.MAX_SAFE_INTEGER) return null
        return v
    }
}
