// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 智能体页空态的四档（方案 §3.1「空态四档」）。
 *
 * ## 为什么要有这一档
 *
 * 之前整页只有一句话（「这台电脑和已连上的设备上都还没有智能体在运行」），于是**三种完全不同的
 * 处境**被说成同一件事：一台设备都没连、连了但谁都没建人、本机没人而别的设备在跑。前两种是
 * 「去配对」与「去建人」，出路完全不同；第三种更要命 —— 它把「别人的机器在跑」说成「什么都没有」，
 * 用户会以为自己配的东西丢了。
 *
 * ## 判据顺序就是语义（首个命中即用）
 *
 * [NO_DEVICE] 必须最先：一台设备都没连的时候，「本机有没有行」这句话本身不成立。
 * 剩下的三档按「本机有没有行」二分 —— 有行（含离线行）就不是空态（返回 null）；
 * 无行时再看**本机档案**是否为空：非空 ⇒ 人已经建过、只是这会儿都没在跑（[LOCAL_IDLE]，
 * 主行动是建团队）；为空才轮到「这台电脑还没人」——再按远端有没有行分成
 * [NO_EMPLOYEE]（哪儿都没有）与 [REMOTE_ONLY]（只有别的设备在跑）。
 *
 * **档案必须排在「远端有没有行」之前**：反过来的话，一个建过两个人、只是都没在跑的机器
 * （无远端行）会落进 [NO_EMPLOYEE]「还没有员工」，界面劝他再建一个人 —— 而 B 档永远
 * 触发不到（它在任何输入下都会被 A 吃掉）。这条链上「先问本机有没有人」比「先问远端有没有行」
 * 更接近用户的问题。
 *
 * 这几条判据全部是**读得到的事实**（已连节点数、本机行数、远端行数、本机档案数），
 * 不需要新协议字段，也不依赖 UI 状态。
 */
public enum class HubEmptyTier { NO_DEVICE, NO_EMPLOYEE, LOCAL_IDLE, REMOTE_ONLY }

/**
 * 求值：[HubEmptyTier] 的四档 + `null`（不是空态）。
 *
 * @param connectedNodes 已连节点数（含本机）。`0` ⇒ 一台设备都没连。
 * @param localRows 本机行数（**含离线行** —— 离线的人是你的员工，不是不存在的行）。
 * @param remoteRows 别的设备的行数。
 * @param hasLocalProfiles 本机员工档案是否非空（MINT / REGISTER 建过的人）。
 */
public fun hubEmptyTier(
    connectedNodes: Int,
    localRows: Int,
    remoteRows: Int,
    hasLocalProfiles: Boolean,
): HubEmptyTier? = when {
    connectedNodes <= 0 -> HubEmptyTier.NO_DEVICE
    localRows > 0 -> null
    hasLocalProfiles -> HubEmptyTier.LOCAL_IDLE
    remoteRows == 0 -> HubEmptyTier.NO_EMPLOYEE
    else -> HubEmptyTier.REMOTE_ONLY
}
