// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.Serializable

/**
 * 共享文件柜目录条目（协议 §8.2）。
 */
@Serializable
data class ShareEntry(
    val name: String,
    val size: Long,
    val isDir: Boolean,
    /** 修改时间，Unix 毫秒。 */
    val mtime: Long,
)

/**
 * 共享文件柜操作指令（op）。
 */
object ShareOps {
    const val LIST = "list"
    const val LIST_OK = "list-ok"
    const val DENY = "deny"
    const val GET = "get"
    const val PUT = "put"
}

/**
 * 共享文件柜拒绝原因码。
 */
object ShareDenyReasons {
    const val OFF = "off"
    const val NO_PERM = "no-perm"
    const val NOT_FOUND = "not-found"
    const val TOO_DEEP = "too-deep"
    const val BUSY = "busy"
    const val GONE = "gone"
}

/**
 * share op: list
 */
@Serializable
data class ShareListPayload(
    val op: String,
    val reqId: String,
    val path: String = "",
    val offset: Int = 0,
    val snapshotId: String? = null,
)

/**
 * share op: list-ok
 */
@Serializable
data class ShareListOkPayload(
    val op: String,
    val reqId: String,
    val path: String,
    val perm: String,
    val snapshotId: String,
    val offset: Int,
    val total: Int,
    val truncated: Boolean = false,
    val entries: List<ShareEntry> = emptyList(),
)

/**
 * share op: deny
 */
@Serializable
data class ShareDenyPayload(
    val op: String,
    val reqId: String,
    val reason: String,
)

/**
 * share op: get
 */
@Serializable
data class ShareGetPayload(
    val op: String,
    val reqId: String,
    val paths: List<String>,
)

/**
 * 领域分派模型。
 */
sealed interface ShareMessage {
    val reqId: String

    data class ListReq(val req: ShareListPayload) : ShareMessage {
        override val reqId: String get() = req.reqId
    }

    data class ListOk(val res: ShareListOkPayload) : ShareMessage {
        override val reqId: String get() = res.reqId
    }

    data class Deny(val deny: ShareDenyPayload) : ShareMessage {
        override val reqId: String get() = deny.reqId
    }

    data class GetReq(val get: ShareGetPayload) : ShareMessage {
        override val reqId: String get() = get.reqId
    }
}
