// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 长连接诊断报告（**只读、纯数据、不含任何凭据**，task-34）。
 *
 * 为什么要有它：拨号发生在**手机**侧，而 `/v1/peers` 是**宿主**侧的控制面 —— 于是
 * "手机为什么没拨 / 拨了为什么连不上"在宿主那边**永远看不到**（宿主只会显示
 * skipReason=peer-has-no-lnk1，因为按设计只有宿主声明 lnk1）。这份报告把手机侧的
 * 同一批字段落成一个本机文件，供 `run-as cat` 读取（不进协议、不开端口）。
 */
@Serializable
internal data class LinkDiagnosticsReport(
    val updatedAt: Long,
    val self: String,
    val linkEnabled: Boolean,
    val linkTotal: Int,
    val peers: List<LinkPeerDiagnostics>,
)

/** 单个对端的诊断（字段口径与 `/v1/peers` 的 link* 字段一致）。 */
@Serializable
internal data class LinkPeerDiagnostics(
    val nodeId: String,
    val online: Boolean,
    val address: String,
    val linkConnected: Boolean,
    val linkCarrier: String?,
    val dialAttempts: Int,
    val dialFailures: Int,
    val dialLastTarget: String?,
    val lastError: String?,
    val skipReason: String?,
    /** 最近一次连接结束的原因（task-40 ①）：dead-timeout / peer-closed / replaced / recheck / stopped。 */
    val closeReason: String?,
    /** 本端主动关掉连接的次数。 */
    val closeCount: Int,
    /** 主动关闭时的判据两元组（isPlaintext / ip / local，task-40）。 */
    val closeDetail: String?,
    /** 对端 pin 的来源（pairing / tofu / unknown，task-38）。 */
    val pinSource: String?,
)

/**
 * 组装报告（纯函数，便于单测）。
 *
 * [peers] 取 registry 快照：诊断要能回答"这条对端在不在、地址是什么"，
 * 否则看到 `skipReason=no-pin` 也不知道说的是哪台机器。
 */
internal fun buildLinkDiagnostics(
    selfNodeId: String,
    linkEnabled: Boolean,
    snapshot: LinkSnapshot,
    peers: List<PeerRecord>,
    now: Long,
): LinkDiagnosticsReport = LinkDiagnosticsReport(
    updatedAt = now,
    self = selfNodeId,
    linkEnabled = linkEnabled,
    linkTotal = snapshot.total,
    peers = peers.map { record ->
        val nodeId = record.profile.nodeId
        val carrier = snapshot.carrierOf(nodeId)
        val dial = snapshot.dialOf(nodeId)
        LinkPeerDiagnostics(
            nodeId = nodeId,
            online = record.online,
            address = record.ip,
            linkConnected = carrier != null,
            linkCarrier = carrier,
            dialAttempts = dial?.attempts ?: 0,
            dialFailures = dial?.failures ?: 0,
            dialLastTarget = dial?.lastTarget,
            lastError = dial?.lastError,
            skipReason = dial?.skipReason,
            closeReason = snapshot.closeOf(nodeId),
            closeCount = snapshot.closeCountOf(nodeId),
            closeDetail = snapshot.closeDetailOf(nodeId),
            pinSource = snapshot.pinSourceOf(nodeId),
        )
    },
)

/**
 * 人类可读的 JSON（`run-as cat` 直接看）。**不写任何凭据**（没有 pin、没有密钥）。
 *
 * 公开（不是 internal）：调用点在 composeApp（另一个 Gradle 模块），internal 它看不见。
 */
fun linkDiagnosticsJson(
    selfNodeId: String,
    linkEnabled: Boolean,
    snapshot: LinkSnapshot,
    peers: List<PeerRecord>,
    now: Long,
): String = diagnosticsJson.encodeToString(
    LinkDiagnosticsReport.serializer(),
    buildLinkDiagnostics(selfNodeId, linkEnabled, snapshot, peers, now),
)

private val diagnosticsJson = Json {
    prettyPrint = true
    // 显式写出 null / 0：诊断文件里"没这一项"和"值是 0"是两回事，读的人不该靠猜。
    encodeDefaults = true
}
