// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 task-3 的 A-3：**「清空聊天记录」必须先确认再删**。
 *
 * 这条曾经是「更危险的动作保护更少」：设置 → 数据与存储里那颗 danger 按钮
 * `onClick = { onClearHistory?.invoke() }` 直接清空**全部会话**的全部本地历史，
 * 零确认、不可恢复；而「清空本会话」反倒有一道 `ClearConversationDialog`。
 *
 * ## 为什么是源码级守卫
 *
 * 破坏性动作的"前置确认"在 Composable 内部（本仓没有 Compose UI 测试基建），
 * 只能在源码层钉住。判据不是"文件里出现过 onClearHistory"（它当然会出现 ——
 * 确认框的确认路径要调它），而是**那颗按钮自己的 onClick 里没有它**。
 *
 * ⚠️ 必须**先剥注释**：`App.kt` 里正当解释这条纪律的注释就写着 `clearAllMessages`，
 * 裸 grep 会被自己的注释绊倒（Lead 点名过这个坑）。
 */
class ClearAllConfirmationGuardTest {

    private val dataSubpage =
        "composeApp/src/commonMain/kotlin/app/relay/feature/settings/SettingsDataSubpage.kt"
    private val app =
        "composeApp/src/commonMain/kotlin/app/relay/App.kt"

    @Test
    fun clearAllHistoryButtonOnlyOpensTheConfirmDialog() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(dataSubpage))

        val destructiveButton = callSpanContaining(text, "OutlinedButton(", "set_clear_history_action")
        // 只看这颗按钮**自己的 onClick 体**：enabled = onClearHistory != null 是正当的
        // （没有回调时保持灰的），判据是"onClick 里有没有直接调它"。
        val onClickBody = GuardSourceScan.bracedBlockAfter(destructiveButton, "onClick =")
        assertFalse(
            onClickBody.contains("onClearHistory"),
            "「清空聊天记录」那颗按钮又在 onClick 里直接调回调了 —— 它是全量、不可恢复的删除，" +
                "必须先弹确认框（ClearAllHistoryConfirmDialog）。",
        )
        assertTrue(
            onClickBody.contains("showClearHistoryConfirm = true"),
            "「清空聊天记录」的 onClick 应当只把确认框叫出来。",
        )
        assertTrue(
            destructiveButton.contains("enabled = onClearHistory != null"),
            "没有回调时这颗按钮应当保持灰的（而不是点了没反应）。",
        )
    }

    @Test
    fun theConfirmDialogIsTheOnlyPlaceThatDeletes() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(dataSubpage))

        // 用 `onConfirm = {` 而不是 `onConfirm`：后者会把底下的**定义**
        // （`onConfirm: () -> Unit`）也当成一处命中，判据就变成"两处里挑一处"。
        val confirmCall = callSpanContaining(text, "ClearAllHistoryConfirmDialog(", "onConfirm = {")
        assertTrue(
            confirmCall.contains("onClearHistory?.invoke()"),
            "确认框的 onConfirm 才是真正执行清空的地方 —— 这条调用链断了，按钮就只是弹个框。",
        )

        val definitionAt = text.indexOf("private fun ClearAllHistoryConfirmDialog(")
        assertTrue(definitionAt >= 0, "找不到 ClearAllHistoryConfirmDialog 的定义。")
        val definition = text.substring(definitionAt)
        assertTrue(
            definition.contains("danger = true"),
            "确认按钮必须是 danger 形态（描边红字）：破坏性动作与「取消」在形状上就要分开。",
        )
        assertTrue(
            definition.contains("common_cancel"),
            "确认框必须给得出「取消」这条路。",
        )
        assertTrue(
            definition.contains("set_clear_history_confirm_body"),
            "确认框正文必须说清三件事（全部会话 / 只删本机、对方不受影响 / 不可恢复）—— " +
                "正文资源 key 是 set_clear_history_confirm_body。",
        )
    }

    @Test
    fun theWiringFromTheCompositionRootStaysIntact() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(app))
        val dataPage = GuardSourceScan.parenBlockAfter(text, "SettingsDataSubpage(")
        assertTrue(
            dataPage.contains("onClearHistory = controller::clearAllMessages"),
            "App.kt 必须继续把清空动作接进数据子页 —— 少了它，那颗按钮会静默变灰，" +
                "用户再也删不掉本地记录。",
        )
    }

    /**
     * 取 `marker(...)` 的实参**加**紧随其后的尾随 lambda。
     *
     * 为什么不能用 [GuardSourceScan.parenBlockAfter]：Compose 的按钮把文案写在尾随
     * lambda 里（`Button(...) { Text(…) }`），只取圆括号内的实参找不到那颗按钮的文案，
     * 也就分不出"清空本会话"和"清空全部"。
     */
    private fun callSpanContaining(text: String, marker: String, needle: String): String {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) throw AssertionError("源码里找不到包含「" + needle + "」的 " + marker)
            val span = callSpan(text, marker, at)
            if (span.contains(needle)) return span
            from = at + marker.length
        }
    }

    private fun callSpan(text: String, marker: String, fromIndex: Int): String {
        val at = text.indexOf(marker, fromIndex)
        if (at < 0) throw AssertionError("源码里找不到调用：" + marker)
        var depth = 0
        var i = at + marker.length - 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        var end = i + 1
        var j = end
        while (j < text.length && text[j].isWhitespace()) j++
        if (j < text.length && text[j] == '{') {
            var braces = 0
            var k = j
            while (k < text.length) {
                when (text[k]) {
                    '{' -> braces++
                    '}' -> {
                        braces--
                        if (braces == 0) break
                    }
                }
                k++
            }
            end = k + 1
        }
        return text.substring(at, end)
    }
}
