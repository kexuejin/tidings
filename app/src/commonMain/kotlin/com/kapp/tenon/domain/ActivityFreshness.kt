// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 「最新活动」这句文案的新鲜度档（不变量 4）。
 *
 * ## 为什么需要它
 *
 * 卡上写「最新活动 · 3 天前」时，前半句在**撒谎**：三天前的活动不是「最新」，它只是
 * 「最后一条」。没有这层判定，用户看到一个陈旧的摘要会以为对方刚刚说过话，据此去
 * 「立即回复」—— 而那条会话早就结束了。
 *
 * ## 为什么不是两个布尔
 *
 * 在线与新鲜是两个独立事实，但它们**共同决定**这句话能不能声称「最新」：
 * `online=true` 且事件陈旧（进程活着，只是没干活）与 `online=false` 且事件刚刚
 * 还热（对端刚断线）都不该说「最新」。做成一个枚举，UI 就只有一个分支要写。
 */
public enum class ActivityFreshness {
    /** 有活动、对端在线、且在窗口内 —— 只有这一档可以说「最新活动」。 */
    FRESH,

    /** 有活动，但对端在线且事件已超出窗口（默认 24h）—— 写「最后活动」不写「最新」。 */
    STALE,

    /** 对端离线：不管事件多新，都不引导「立即回复」（它现在收不到）。 */
    OFFLINE,

    /** 一条活动都没有（`at <= 0`）—— 不说「最新」，也不说「最后」，整句不画。 */
    NONE,
}

/** 「最新」的有效窗口：超过一天就不再叫最新（产品裁决值，不是测量结论）。 */
public const val ACTIVITY_FRESH_WINDOW_MS: Long = 24L * 60 * 60 * 1000

/**
 * 活动新鲜度判定（纯函数，唯一判据）。
 *
 * @param at 最近活动时刻（epoch ms；0 / 负数 = 没有活动）。
 * @param now 本端当前时刻。
 * @param online 对端是否在线（**沉默不算在线**：调用方按 `PeerUi.online` 如实传入）。
 */
public fun agentActivityFreshness(at: Long, now: Long, online: Boolean): ActivityFreshness = when {
    at <= 0L -> ActivityFreshness.NONE
    !online -> ActivityFreshness.OFFLINE
    now - at >= ACTIVITY_FRESH_WINDOW_MS -> ActivityFreshness.STALE
    else -> ActivityFreshness.FRESH
}
