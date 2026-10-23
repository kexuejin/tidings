// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面壳的分档与高度预算。
 *
 * 这些函数全是纯函数，但此前**一条测试都没有** —— 设计对账里那句「wide 档 1440×900 减去
 * chrome 与状态栏后，聊天区高度是否仍满足 `MIN_CHAT_HEIGHT_PX`」因此一直没有答案。
 * 这里把它变成一个可以跑的数字，而不是继续留在计划文档里。
 */
class DesktopLayoutTest {

    // ── 分档 ────────────────────────────────────────────────

    @Test
    fun theThreeAcceptanceSizesLandInTheThreeTiers() {
        assertEquals(
            DesktopWindowSizeClass.Expanded,
            desktopWindowSizeClass(1440, 900),
            "1440×900（原型 wide 档）该是双栏",
        )
        assertEquals(
            DesktopWindowSizeClass.Expanded,
            desktopWindowSizeClass(1100, 720),
            "1100×720 是首次启动默认大小，也该是双栏",
        )
        assertEquals(
            DesktopWindowSizeClass.Medium,
            desktopWindowSizeClass(760, 720),
            "760×720 装不下侧栏 300 + 聊天栏 300 + 竖导 64，退竖导",
        )
        assertEquals(
            DesktopWindowSizeClass.Compact,
            desktopWindowSizeClass(560, 620),
            "560×620 是窗口最小尺寸，退底导单栏",
        )
    }

    @Test
    fun theWideTierStillHasRoomForTheChatPane() {
        // 这条就是计划文档里悬着的那个问题：1440×900 扣掉 chrome 之后还剩多少。
        val usable = 900 - DesktopMetrics.CHROME_PX
        assertEquals(788, usable, "1440×900 减 chrome 112")
        assertTrue(
            usable >= DesktopMetrics.MIN_CHAT_HEIGHT_PX,
            "聊天区只剩 $usable px，低于输入区浮起阈值 ${DesktopMetrics.MIN_CHAT_HEIGHT_PX}",
        )
        // 顺带把 1280×800 也钉住：注释里那句「还剩约 660px」得是真的。
        assertEquals(800 - DesktopMetrics.CHROME_PX, 688)
        assertTrue(800 - DesktopMetrics.CHROME_PX >= DesktopMetrics.MIN_CHAT_HEIGHT_PX)
    }

    @Test
    fun theChromeBudgetIsTheSumOfItsThreeStrips() {
        // 2026-10-18：菜单栏那一段（26px）整条删掉，改由系统菜单栏承载 —— 它在窗口
        // 客户区之外，不再从这个预算里扣。于是四段变三段、138 变 112。
        assertEquals(
            DesktopMetrics.TITLEBAR_PX + DesktopMetrics.TOOLBAR_PX + DesktopMetrics.STATUSBAR_PX,
            DesktopMetrics.CHROME_PX,
        )
        assertEquals(112, DesktopMetrics.CHROME_PX, "设计稿写死的数，改动要连着设计稿一起改")
    }

    @Test
    fun widthIsNotEnoughOnItsOwn() {
        // 很宽的窗口但很矮：列表和详情各分一半，谁都放不下，双栏没有意义。
        assertEquals(
            DesktopWindowSizeClass.Medium,
            desktopWindowSizeClass(1440, 500),
            "1440 宽但可用高度只有 ${500 - DesktopMetrics.CHROME_PX}px，不该给双栏",
        )
        // 刚好卡在阈值上下。
        assertEquals(
            DesktopWindowSizeClass.Expanded,
            desktopWindowSizeClass(1000, DesktopMetrics.CHROME_PX + DesktopMetrics.MIN_CHAT_HEIGHT_PX),
            "宽度与可用高度都刚好达标",
        )
        assertEquals(
            DesktopWindowSizeClass.Medium,
            desktopWindowSizeClass(1000, DesktopMetrics.CHROME_PX + DesktopMetrics.MIN_CHAT_HEIGHT_PX - 1),
            "可用高度差 1px 就不给双栏",
        )
    }

    @Test
    fun theMediumLowerBoundIsWhereTheComposerWouldBreak() {
        // 原型断点写 600，这里取 664 —— 600 宽时聊天栏只剩 236px，输入栏是坏的。
        assertEquals(
            DesktopMetrics.MEDIUM_RAIL_PX + DesktopMetrics.SIDEBAR_PX + DesktopMetrics.MIN_CHAT_PANE_PX,
            DesktopMetrics.MEDIUM_MIN_PX,
        )
        assertEquals(664, DesktopMetrics.MEDIUM_MIN_PX)
        assertEquals(
            DesktopWindowSizeClass.Compact,
            desktopWindowSizeClass(663, 720),
            "差 1px 就退到单栏，而不是硬塞一个坏掉的输入栏",
        )
        assertEquals(DesktopWindowSizeClass.Medium, desktopWindowSizeClass(664, 720))
    }

    @Test
    fun theTabletDeductsItsOwnChromeNotTheDesktops() {
        // 平板只留工具栏：按 112 扣会让 800×1280 凭空少 66dp，竖向判定跟着漂。
        assertEquals(DesktopMetrics.TOOLBAR_PX, DesktopMetrics.TABLET_CHROME_PX)
        assertEquals(
            DesktopWindowSizeClass.Expanded,
            desktopWindowSizeClass(1280, 800, DesktopChromeMode.Tablet),
            "1280×800 平板横放是双栏",
        )
        assertEquals(
            DesktopWindowSizeClass.Medium,
            desktopWindowSizeClass(800, 1280, DesktopChromeMode.Tablet),
            "800×1280 平板竖放宽度不够，走竖导",
        )
        // 同一个尺寸、两种外壳，档位必须不同 —— 否则就是扣错了 chrome。
        // 500 高：桌面外壳可用 362px 不够双栏，平板外壳可用 454px 够。
        assertEquals(DesktopWindowSizeClass.Expanded, desktopWindowSizeClass(1000, 500, DesktopChromeMode.Tablet))
        assertEquals(DesktopWindowSizeClass.Medium, desktopWindowSizeClass(1000, 500, DesktopChromeMode.Desktop))
    }

    @Test
    fun theTierFlagsAgreeWithTheTierItself() {
        assertTrue(DesktopWindowSizeClass.Expanded.twoPane)
        assertFalse(DesktopWindowSizeClass.Medium.twoPane)
        assertFalse(DesktopWindowSizeClass.Compact.twoPane)
        assertTrue(DesktopWindowSizeClass.Medium.usesRail)
        assertFalse(DesktopWindowSizeClass.Expanded.usesRail)
        assertFalse(DesktopWindowSizeClass.Compact.usesRail)
        assertTrue(DesktopChromeMode.Desktop.showsDesktopChrome)
        assertFalse(DesktopChromeMode.Tablet.showsDesktopChrome)
        assertEquals(DesktopChromeMode.Desktop, desktopChromeModeFor(isDesktop = true))
        assertEquals(DesktopChromeMode.Tablet, desktopChromeModeFor(isDesktop = false))
    }

    @Test
    fun theWindowNeverGetsSmallerThanTheTiersCanDescribe() {
        // ComposeWindow 的 minSize 就是这两个数；测试里写出来，免得它们被单独改掉。
        assertEquals(560, DesktopMetrics.MIN_WINDOW_WIDTH_PX)
        assertEquals(620, DesktopMetrics.MIN_WINDOW_HEIGHT_PX)
        assertEquals(
            DesktopWindowSizeClass.Compact,
            desktopWindowSizeClass(
                DesktopMetrics.MIN_WINDOW_WIDTH_PX,
                DesktopMetrics.MIN_WINDOW_HEIGHT_PX,
            ),
            "最小窗口落在 Compact",
        )
    }

    @Test
    fun aWindowShorterThanItsOwnChromeIsStillJustCompact() {
        // 高度比 chrome 还小时可用高度夹到 0，不能让减法算出负数再拿去比大小。
        assertEquals(
            DesktopWindowSizeClass.Compact,
            desktopWindowSizeClass(320, 100),
            "100px 装不下 138px 的 chrome，但函数不能抛也不能判成 Expanded",
        )
    }

    // ── 草稿字节数 ──────────────────────────────────────────

    @Test
    fun theDraftCountIsUtf8BytesNotCharacters() {
        // 中文一行 3 字节：字符数看着没到上限的草稿，经常已经超了 UDP 限制。
        assertEquals(3, draftByteCount("茶"), "一个汉字三字节")
        assertEquals(1, "茶".length, "字符数只有 1 —— 用 length 判断就会漏")
        assertEquals(800, Protocol.TEXT_UDP_LIMIT, "上限是字节数，不是字符数")
        assertEquals(Protocol.TEXT_UDP_LIMIT, draftByteCount("a".repeat(Protocol.TEXT_UDP_LIMIT)))
        assertEquals(
            ComposeByteMode.Tcp,
            composeByteMode(draftByteCount("茶".repeat(267))),
            "267 个汉字只有 267 个字符，但已经 801 字节，该走 TCP",
        )
    }

    @Test
    fun theComposerSwitchesToTcpExactlyAtTheLimit() {
        assertEquals(ComposeByteMode.Udp, composeByteMode(Protocol.TEXT_UDP_LIMIT))
        assertEquals(ComposeByteMode.Tcp, composeByteMode(Protocol.TEXT_UDP_LIMIT + 1))
        assertFalse(ComposeByteMode.Udp.overLimit)
        assertFalse(ComposeByteMode.Tcp.overLimit, "换条路不等于发不出去 —— 这正是两态时混掉的那件事")
        assertTrue(ComposeByteMode.Over.overLimit)
    }

    @Test
    fun theComposerOnlyGivesUpAtTheHardLimit() {
        // 4096 字节含等号仍然发得出去（走 TCP 分包）；4097 才是真的发不出去。
        assertEquals(ComposeByteMode.Tcp, composeByteMode(Protocol.TEXT_TCP_LIMIT))
        assertEquals(ComposeByteMode.Over, composeByteMode(Protocol.TEXT_TCP_LIMIT + 1))
        assertEquals(4096, Protocol.TEXT_TCP_LIMIT, "硬上限是 4096 字节")
    }

    // ── 输入区比例 ──────────────────────────────────────────

    @Test
    fun theSplitRatioCannotLeaveItsRange() {
        assertFailsWith<IllegalArgumentException> { ComposerSplit(0.1f) }
        assertFailsWith<IllegalArgumentException> { ComposerSplit(0.9f) }
        // 端点本身是合法的：夹取用的是闭区间，构造时也得收。
        assertEquals(ComposerSplit.MIN_RATIO, ComposerSplit(ComposerSplit.MIN_RATIO).ratio)
        assertEquals(ComposerSplit.MAX_RATIO, ComposerSplit(ComposerSplit.MAX_RATIO).ratio)
        assertEquals(ComposerSplit.MIN_RATIO, ComposerSplit.clamped(0.0f))
        assertEquals(ComposerSplit.MAX_RATIO, ComposerSplit.clamped(1.0f))
        assertEquals(0.5f, ComposerSplit.clamped(0.5f))
        assertEquals(ComposerSplit(0.5f).ratio, ComposerSplit(0.5f).fraction, "fraction 只是 ratio 的可读别名")
    }

    @Test
    fun theComposerHeightNeverGoesNegativeOrBelowItsFloor() {
        assertEquals(0, ComposerSplit(0.5f).heightFor(0), "没有可用高度就是 0，不能算出负数")
        assertEquals(0, ComposerSplit(0.5f).heightFor(-100))
        // 可用高度本来就小于下限时，不能抛 IllegalArgumentException（拖拽中会打断布局）。
        assertEquals(50, ComposerSplit(0.5f).heightFor(50))
        assertEquals(10, ComposerSplit(0.72f).heightFor(10), "极窄时也不超过可用高度")
        assertEquals(
            ComposerSplit.MIN_HEIGHT_PX,
            ComposerSplit(ComposerSplit.MIN_RATIO).heightFor(300),
            "300px 按 28% 只有 84px，被抬到下限 120px",
        )
        assertEquals(420, ComposerSplit(ComposerSplit.DEFAULT_RATIO).heightFor(1_000), "1000 × 42%")
    }
}
