// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desktop_tray_headline_grants
import com.kapp.tidings.generated.resources.desktop_tray_headline_unread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **托盘必须能说出「有人等着你批准」。**
 *
 * ## 真机那条死路的最后一环
 *
 * 申请发得出来、台账记得下、面板也批得了 —— 但面板在「设置 → 授信设备」深处，
 * 用户没有理由主动去翻。没有托盘这一句，结局就是「没人看得见有个东西在等」。
 *
 * ## 两条纪律（都在本文件原有的 KDoc 里）
 *
 * 1. **频繁变化的数不进口条目表**：一旦进去，AWT 那侧就要整份重建原生菜单，
 *    而重建会重排菜单栏几何（由 WindowServer 主线程付代价）。所以待批数只进
 *    头条（tooltip）与图标角标，和未读数同一档。
 * 2. **直达条目只在真的有东西待批时出现**：常驻一条会让人以为「这里总有东西」，
 *    与本仓「能力不存在就不给一个按下去没反应的入口」同一条纪律。
 */
class TrayPendingGrantsTest {

    private fun actions(spec: TrayMenuSpec) = spec.items.mapNotNull { it.action }.toSet()

    /** 有待批 ⇒ 头条说件事，并且出现直达条目。 */
    @Test
    fun pendingGrantsGetAHeadlineAndADirectEntry() {
        val spec = trayMenuSpec(unreadTotal = 0, receivedFiles = 0, pendingGrants = 2)

        assertTrue(
            actions(spec).contains(TrayAction.OpenAgentGrants),
            "有待批时必须给一条直达 —— 否则用户还得自己在八行左栏里找面板",
        )
    }

    /** 没有待批 ⇒ **不画**那条直达（不制造一个永远点不出东西的入口）。 */
    @Test
    fun noPendingGrantsMeansNoDirectEntry() {
        val spec = trayMenuSpec(unreadTotal = 0, receivedFiles = 0, pendingGrants = 0)
        assertTrue(
            !actions(spec).contains(TrayAction.OpenAgentGrants),
            "没有待批时不该出现直达条目",
        )
    }

    /**
     * **未读消息优先于待批**：消息是有人在对你说活，比「有人等你按一个按钮」更急。
     *
     * 头条只能显示一条，所以这条优先级必须钉住。
     */
    @Test
    fun unreadMessagesOutrankPendingGrantsInTheHeadline() {
        val spec = trayMenuSpec(unreadTotal = 3, receivedFiles = 0, pendingGrants = 2)
        assertEquals(
            Res.string.desktop_tray_headline_unread,
            spec.title.resource,
            "未读消息应当占住头条（待批退居其次）",
        )
    }

    /** 待批优先于「收到文件」：前者有人在等决定，后者只是东西到了。 */
    @Test
    fun pendingGrantsOutrankReceivedFilesInTheHeadline() {
        val spec = trayMenuSpec(unreadTotal = 0, receivedFiles = 5, pendingGrants = 1)
        assertEquals(
            Res.string.desktop_tray_headline_grants,
            spec.title.resource,
            "待批应当压过「收到文件」——前者有人在等决定",
        )
    }
}
