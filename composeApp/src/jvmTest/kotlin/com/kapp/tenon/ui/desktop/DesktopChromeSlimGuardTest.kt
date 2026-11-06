// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面 chrome 精简（整改方案 V2）：
 *
 * 1. 标题栏不许把节点身份片段印进人眼语境（B13 同族泄漏）——`selfNodeId.take(8)`
 *    的完整 id 在设置/节点详情里都点得到（那里是"身份"的合法语境），标题栏是
 *    「这一页是什么」的语境，一串 8 位十六进制在那里没有信息量、只有噪音。
 * 2. 状态栏只留两个**真相**：链路状态与未读（待发）。端口/控制面是与诊断包重复的
 *    观测项 —— 诊断包导出里它们是结构化字段；印在状态栏上每个窗口都占 26px，
 *    换来的只有「数字在动」。动作（配对链接/诊断包）按裁决收敛：诊断包与系统菜单
 *    重复（菜单同一条、⌘ 可达）从状态栏摘除；配对链接无第二入口且是分享动作，
 *    照原型 .r 保留。
 *
 * 为什么只能源码守卫：这些是「印不印」的组合分支，渲染证据（桌面帧）只有常量
 * 夹具一份，区分不出「从未印过」与「这一帧没印」。
 */
class DesktopChromeSlimGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 取 [marker] 起、到下一个顶层 fun 声明为止的函数体切片。
     *
     * 不用 [GuardSourceScan.bracedBlockAfter]：它的括号配平不识别 `参数默认值 = {}`
     * 里的空花括号（参数区里第一个 `{` 就把块"闭合"了），对带函数类型默认值的
     * Composable 恒返回空串 —— 本仓第一次踩到这个形状是 DesktopStatusBar 的
     * `onCopied: () -> Unit = {}`。
     */
    private fun functionBody(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) return ""
        val rest = text.slice(at + 1 until text.length)
        val next = Regex("\\n    (?:public|internal|private) fun ").find(rest)
        return if (next == null) rest else rest.slice(0 until next.range.first)
    }

    private val chromePath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopChrome.kt"
    private val statusBarPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopStatusBar.kt"

    /** 回退方式：把 \`selfNodeId.take(8)\` 放回标题栏副标题 ⇒ 本条红。 */
    @Test
    fun titleBarDoesNotPrintNodeIdFragment() {
        val chrome = live(chromePath)
        val titleBar = functionBody(chrome, "fun DesktopTitleBar(")
        assertFalse(
            titleBar.contains("take(8)"),
            "标题栏副标题把 selfNodeId 片段印进人眼语境（B13 同族）—— 完整 id 的合法语境在节点详情与设置",
        )
    }

    /**
     * 会话视图没有选中时副标题不占位（原型 tsub 只在 connected 态有内容；
     * 「局域网直连的消息与文件传输」这句 tagline 在窗口打开的每一秒都成立，
     * 不需要每秒重读一遍）。
     *
     * 回退方式：把 tagline 恢复成无选中时的兜底 ⇒ 本条红。
     */
    @Test
    fun conversationsSubtitleAppearsOnlyWithSelection() {
        val chrome = live(chromePath)
        val titleBar = GuardSourceScan.bracedBlockAfter(chrome, "fun DesktopTitleBar(")
        assertFalse(
            titleBar.contains("desktop_title_tagline"),
            "无选中会话时标题栏回退到 tagline —— 副标题按裁决只在有内容可说（选中会话）时出现",
        )
    }

    /**
     * 状态栏不再印端口与控制面（诊断包已含结构化字段；这是观测项的重复）。
     *
     * 回退方式：把 "UDP" / "controlPlaneLabel" 恢复进状态栏 Row ⇒ 本条红。
     */
    @Test
    fun statusBarDropsPortsAndControlPlane() {
        val bar = live(statusBarPath)
        val row = functionBody(bar, "fun DesktopStatusBar(")
        assertFalse(
            row.contains("UDP \$selfPort"),
            "状态栏还印 UDP/TCP 端口 —— 观测项与诊断包重复（V2 精简裁决）",
        )
        assertFalse(
            row.contains("controlPlaneLabel"),
            "状态栏还印控制面端口 —— 同上",
        )
    }

    /**
     * 诊断包动作从状态栏摘除（系统菜单同一条 + ⌘ 快捷键可达，双入口重复）；
     * 配对链接保留（无第二入口、原型 .r 里有它）。
     *
     * 回退方式：把 onExportDiagnostics 分支恢复进状态栏 ⇒ 本条红。
     */
    @Test
    fun statusBarKeepsPairingLinkDropsDiagnostics() {
        val bar = live(statusBarPath)
        val row = functionBody(bar, "fun DesktopStatusBar(")
        assertTrue(
            row.contains("onCopyPairingLink") || row.contains("actions.onCopyPairingLink"),
            "配对链接从状态栏消失 —— 它没有第二入口（菜单/工具栏都没有），不能跟着诊断包一起摘",
        )
        assertFalse(
            row.contains("onExportDiagnostics"),
            "诊断包仍在状态栏 —— 与系统菜单同一条动作重复（V2 归并裁决）",
        )
    }
}
