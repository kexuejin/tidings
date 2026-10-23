// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.ScreenRecordUi
import com.kapp.tidings.core.protocol.ScreenEndReasons
import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRejectReasons
import com.kapp.tidings.core.protocol.ScreenRole

/**
 * 一条投屏记录落在哪一档文案上。
 *
 * ## 为什么它在领域侧而不是 UI 侧
 *
 * 这个判据回答的是「这次投屏**发生了什么**」—— 相位、原因码、本侧是 sharer 还是
 * viewer。三件都是协议事实，而不是像素。`declined` 在本侧是 sharer 时意思是
 * 「我拒绝了」，在 viewer 时是「对方拒绝了」：**同一个字符串推不出同一句话**，
 * 所以必须在这里按 role 分叉。
 *
 * UI 侧只负责「这一档写成哪句中文」：短状态词与完整句子各一张资源表
 * （`screen_card_state_*` / `screen_card_text_*`），两张表都以本枚举为键。
 * 分表的理由是状态词与完整句子必须**永远同步** —— 同一个原因下短词写「已超时」、
 * 长句写「对方拒绝」就是自相矛盾，两处各写一遍 `when` 迟早会漂。
 *
 * ## 状态转换规则
 *
 * **先看相位、再看原因**。`declined` / `busy` 按 role 分叉，其余码两侧同义
 * （状态机规则一致）。
 */
fun screenCardCase(record: ScreenRecordUi): ScreenCardCase = when (record.phase) {
    ScreenPhase.Requesting -> ScreenCardCase.Requesting
    ScreenPhase.AwaitingConsent -> ScreenCardCase.AwaitingConsent
    ScreenPhase.Preparing, ScreenPhase.Connecting -> ScreenCardCase.Connecting
    ScreenPhase.Active -> ScreenCardCase.Active
    // Ended / Error：相位已经说不出为什么，只能看原因码。
    ScreenPhase.Ended, ScreenPhase.Error -> when (record.reason) {
        ScreenRejectReasons.DECLINED ->
            if (record.role == ScreenRole.Sharer) ScreenCardCase.DeclinedSelf else ScreenCardCase.DeclinedPeer
        ScreenRejectReasons.BUSY ->
            if (record.role == ScreenRole.Sharer) ScreenCardCase.BusySelf else ScreenCardCase.BusyPeer
        ScreenRejectReasons.UNSUPPORTED -> ScreenCardCase.Unsupported
        ScreenRejectReasons.PERMISSION_DENIED -> ScreenCardCase.PermissionDenied
        // `capture-failed`（我这边采集起不来）与 `capture-ended`（采集被系统收了）
        // 在此处共用同一种文案。
        ScreenRejectReasons.CAPTURE_FAILED, ScreenEndReasons.CAPTURE_ENDED -> ScreenCardCase.CaptureStopped
        // 只列一次：`ScreenRejectReasons.TIMEOUT` 与 `ScreenEndReasons.TIMEOUT` 同值
        // （都是 "timeout"），列两次是同一个分支写两遍。
        ScreenRejectReasons.TIMEOUT -> ScreenCardCase.Timeout
        ScreenEndReasons.DISCONNECTED -> ScreenCardCase.Disconnected
        ScreenEndReasons.LOCKED -> ScreenCardCase.Locked
        ScreenEndReasons.SUSPENDED -> ScreenCardCase.Suspended
        ScreenEndReasons.PROTOCOL_ERROR -> ScreenCardCase.ProtocolError
        // 本地卫生逻辑写的码，不在协议取值域里（见 SCREEN_INTERRUPTED_REASON）。
        SCREEN_INTERRUPTED_REASON -> ScreenCardCase.Interrupted
        // 兜底按「有没有真的开始过」分：没开始 = 请求被取消，开始过 = 结束了
        // （默认：startedAt 未设定时为已取消，否则为已结束）。
        else -> if (record.startedAt == null) ScreenCardCase.Canceled else ScreenCardCase.Ended
    }
}

/** [screenCardCase] 的取值。每一档对应一组资源键（短词 / 完整句子）。 */
enum class ScreenCardCase {
    Requesting,
    AwaitingConsent,
    Connecting,
    Active,
    DeclinedSelf,
    DeclinedPeer,
    BusySelf,
    BusyPeer,
    Unsupported,
    PermissionDenied,
    CaptureStopped,
    Timeout,
    Disconnected,
    Locked,
    Suspended,
    ProtocolError,
    Interrupted,
    Canceled,
    Ended,
}
