// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ShareEntry
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.transport.SendOutcome

/**
 * [NodeRuntime] 的共享文件柜（Cabinet / Share）网络信令扩展（协议 §8.2）。
 */

suspend fun NodeRuntime.sendShareList(
    peerId: String,
    path: String = "",
    offset: Int = 0,
    snapshotId: String? = null,
): String {
    val reqId = newId()
    val envelope = makeEnvelope(
        MsgTypes.SHARE,
        profile.nodeId,
        Codec.shareListPayload(reqId, path, offset, snapshotId),
    )
    messenger.sendUserMessage(peerId, envelope)
    return reqId
}

suspend fun NodeRuntime.sendShareListOk(
    peerId: String,
    reqId: String,
    path: String,
    perm: String,
    snapshotId: String,
    offset: Int,
    total: Int,
    truncated: Boolean,
    entries: List<ShareEntry>,
): SendOutcome {
    val envelope = makeEnvelope(
        MsgTypes.SHARE,
        profile.nodeId,
        Codec.shareListOkPayload(reqId, path, perm, snapshotId, offset, total, truncated, entries),
    )
    return messenger.sendUserMessage(peerId, envelope)
}

suspend fun NodeRuntime.sendShareDeny(
    peerId: String,
    reqId: String,
    reason: String,
): SendOutcome {
    val envelope = makeEnvelope(
        MsgTypes.SHARE,
        profile.nodeId,
        Codec.shareDenyPayload(reqId, reason),
    )
    return messenger.sendUserMessage(peerId, envelope)
}

suspend fun NodeRuntime.sendShareGet(
    peerId: String,
    paths: List<String>,
): String {
    val reqId = newId()
    val envelope = makeEnvelope(
        MsgTypes.SHARE,
        profile.nodeId,
        Codec.shareGetPayload(reqId, paths),
    )
    messenger.sendUserMessage(peerId, envelope)
    return reqId
}
