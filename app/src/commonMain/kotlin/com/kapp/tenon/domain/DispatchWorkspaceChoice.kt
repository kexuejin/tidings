package com.kapp.tenon.domain

/**
 * 「这条指令写进哪个仓」——1:1 派单药丸的选中项解析。
 *
 * ## 为什么单独成一个纯函数
 *
 * 同一个问题（当前生效的工作区是哪个）在同一屏里被问了**两次**：
 * 一次给药丸/顶栏画出来，一次在 `submitMessage` 里决定 `wsId`。
 * 从前这两处各写一条回退链（`localActiveWsId ?: 列表第一个`），
 * 于是任何一种口径变化都只能改一处、漏一处 —— 画着 A 仓、派到 B 仓，
 * 而且两个值都"看起来对"。
 *
 * 现在把回退链收成一个函数、一处求值：**界面显示的就是派单用的**。
 *
 * ## 三条判据的顺序
 *
 * 1. [remembered]（用户这次会话里显式点选的，或上次落盘记住的）——
 *    **但必须仍在本机工作区列表里**。解绑、换机、改名之后它就是一个悬空 id，
 *    照它派单等于往一个不存在的仓写字。
 * 2. [available] 的第一个 —— 与"从没选过"同一个结果，保持老行为。
 * 3. 都没有 ⇒ null（没有工作区就没有可派的目标，调用方据此不派）。
 *
 * 这里刻意**不做**"猜一个最像的"：歧义时退到确定的第一个，比猜错仓好收场。
 */
object DispatchWorkspaceChoice {

    /**
     * @param remembered 上次选中/上次落盘的工作区 id（可为空、可为悬空值）
     * @param available  本机在**这个对端**上可见的工作区 id，顺序即默认顺序
     * @return 生效的 wsId；列表为空时 null
     */
    fun resolve(remembered: String?, available: List<String>): String? {
        val hit = remembered?.takeIf { it.isNotBlank() && it in available }
        return hit ?: available.firstOrNull()
    }
}
