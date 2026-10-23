// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `relay://show-main` 这条"把主窗叫出来"的深链。
 *
 * ## 为什么它值得单独一条测试
 *
 * 它服务的是**单实例**这条路径：第二次点图标 → 第二个进程拿不到锁 → 把意图转交给
 * 已在跑的那个。转交没有说话的地方，用户看到的就是"点了没反应" —— 而这正是本仓
 * 反复修的那类缺陷（能力在、路在、但用户看不到反馈）。
 *
 * ## 三条断言各自挡什么
 *
 * 1. **它必须被认出来**：删掉 `isShowMain` 的分支会让第二次点图标变成静默。
 * 2. **它不能被当成节点地址**：`parse` 要求 `host:port`，这条没有端口；如果哪天有人
 *    把它改成 `relay://show-main:0` 之类，就会往节点列表里塞一个假节点 ——
 *    比什么都不做更糟（本仓的既有判据）。
 * 3. **它不能被当成消息文件**：没有 `.relaymsg` 后缀。
 */
class ShowMainDeepLinkTest {

    @Test
    fun `show-main 被识别为唤回主窗`() {
        assertTrue(DesktopDeepLink.isShowMain("relay://show-main"), "转交通道唯一的载荷必须是它能认出来的")
        assertTrue(DesktopDeepLink.isShowMain("  RELAY://SHOW-MAIN  "), "URL scheme 与大小写都不敏感")
    }

    @Test
    fun `普通深链不会被误判成唤回主窗`() {
        assertFalse(DesktopDeepLink.isShowMain("relay://192.168.1.5:8788"), "带端口的深链是要加节点，不是唤窗")
        assertFalse(DesktopDeepLink.isShowMain("relay://"), "空载荷不是唤窗")
        assertFalse(DesktopDeepLink.isShowMain("/tmp/a.relaymsg"), "文件关联不是唤窗")
    }

    @Test
    fun `show-main 不会被当成节点地址`() {
        assertNull(
            DesktopDeepLink.parse(DesktopDeepLink.SHOW_MAIN_URL),
            "它必须解析不出节点 —— 否则每次点第二次图标都会往节点列表里加一个假节点",
        )
    }

    @Test
    fun `show-main 不会被当成消息文件`() {
        assertFalse(
            DesktopDeepLink.isMessageFile(DesktopDeepLink.SHOW_MAIN_URL),
            "它是 URL 不是文件；两条判断各走各的路",
        )
    }
}
