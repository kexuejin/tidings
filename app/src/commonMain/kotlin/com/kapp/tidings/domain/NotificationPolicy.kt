// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 静默时段的边界（用户当地时间，小时制）：`22:00` 起（含）到 `08:00` 止（不含）。
 *
 * 与设置屏上那条文案「22:00 – 08:00 不响铃」是同一个区间 —— 数字只写在这里一份，
 * 将来要改就改这里，别让文案和判定各说各话。
 */
const val QUIET_HOURS_START = 22

/** 见 [QUIET_HOURS_START]。 */
const val QUIET_HOURS_END = 8

/**
 * 一条刚收到的聊天消息要不要弹通知。
 *
 * 抽成**纯函数**（`localHour` 由调用方传进来）是为了让它可测：三个通知开关里
 * 最麻烦的那个是时段判断，如果它偷偷读系统时钟，测试就只能靠"跑的时候刚好是几点"。
 *
 * 判定顺序与理由：
 * 1. **静默时段优先**：它是用户对"现在别吵我"的直接表达，比"谁发的"更高一层。
 * 2. **Agent 会话单独管**：Agent 节点会自己往外说话（状态、进度），是最吵的一类；
 *    把它单独关掉，不需要连同事一起静音 —— 这也是设置屏第一条开关的承诺
 *    （「Agent 消息通知」），它不是总开关。
 * 3. **仅被 @ 时提醒只对群消息生效**：协议里 `mentions` 只存在于群消息载荷，
 *    一对一根本没有"提到我"这个概念。私聊一律提醒，否则用户会把"对方没回过"
 *    和"我把通知关了"混起来。
 *
 * @param fromAgent 发这条消息的节点是不是 Agent（`Profile.isAgentNode()`）
 * @param isGroup 是不是群消息（载荷里才有 `mentions` 的那种）
 * @param mentioned 群消息里有没有点到本机
 * @param localHour 本机当前时区的小时数（0..23）
 */
internal fun shouldNotify(
    settings: RelaySettings,
    fromAgent: Boolean,
    isGroup: Boolean,
    mentioned: Boolean,
    localHour: Int,
    /**
     * 这条消息所属的**会话键**（私聊 = 对端 nodeId、群 = group:<id>）。
     *
     * 用来查「这个会话被用户静音了吗」。null = 调用方不知道会话键（预览 / 老调用点），
     * 那就当没静音 —— 宁可多提醒一次，也不要因为查不到键就把通知吞掉。
     */
    convId: String? = null,
): Boolean {
    // 会话级静音**优先于一切**：用户明确说过「这个会话别吵我」，
    // 那么不论它是不是 agent、有没有 @ 我、当前是不是静默时段，都不该弹。
    // 与全局开关的语义分开：全局开关是「我什么都不想收」，静音是「这一个别吵」。
    if (convId != null && convId in settings.mutedConvIds) return false
    if (settings.notifyQuietHours && isQuietHour(localHour)) return false
    if (fromAgent) return settings.notifyAgentMessages
    if (isGroup && settings.notifyMentionOnly && !mentioned) return false
    return true
}

/** 当前小时是否落在静默时段里。区间跨零点，所以不能用 `in a..b`。 */
internal fun isQuietHour(localHour: Int): Boolean =
    localHour >= QUIET_HOURS_START || localHour < QUIET_HOURS_END

/**
 * 戳一碰要不要发出系统通知（协议 §8）。
 *
 * 与 [shouldNotify] 的差别只有一条：**不看「Agent 消息通知」开关**。戳一碰不是 Agent
 * 在往外说话，而是对面的人明确按了一下"看我一眼"；把它交给一个管 Agent 流量的开关，
 * 会出现"用户关了 Agent 通知，于是人被戳了也毫无反应"。
 *
 * 免打扰必须遵守：协议原文是「若该单聊免打扰，收端不得唤起、置前或震动主窗」。
 * 本端只有全局静默时段这一种免打扰（设置里没有单聊级免打扰），所以能兑现的只有它。
 */
internal fun shouldNotifyNudge(settings: RelaySettings, localHour: Int): Boolean =
    !(settings.notifyQuietHours && isQuietHour(localHour))
