// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.text.AttributedString
import javax.swing.SwingUtilities

/**
 * 补上 macOS 下「空格 / 回车」丢掉的那一次 `KEY_TYPED`（真缺陷的绕修）。
 *
 * ## 缺陷在哪一层（**尚未确定**，这里如实记录已排除的）
 *
 * 曾怀疑是 JDK `CPlatformResponder.handleKeyEvent` 里那条「空格/回车结束输入法处理、
 * 由 `handleInputEvent` 补发 KEY_TYPED」的特例。**已证伪**：那条分支以
 * `needsKeyReleased` 为条件，而普通窗口构造响应器时传的是 `false`
 * （`CPlatformWindow` 与 `CViewPlatformEmbeddedFrame` 都是 `new CPlatformResponder(peer, false)`，
 * 只有 `CEmbeddedFrame` 传 true），所以它从不执行。
 *
 * 目前**确凿**的只有这几条（都有日志或对照实验支撑）：
 *   ① 空格事件**是进到 JVM 的**：诊断日志里出现过 `TYPED keyCode=0 char=' '(32)`；
 *   ② 但它是否落进文本框，取决于输入法状态 —— 拼音时有时能落、ABC 时完全落不进去；
 *   ③ 同一台机器上原生应用（浏览器、备忘录）打空格正常；
 *   ④ 纯 Swing 探针（JDK 21 / JDK 25，完全不碰 Compose）同样打不出空格。
 *   ④+③ 说明病根在 **JDK 的 macOS 输入桥**，不在 Tidings、不在 Compose。
 *
 * 所以本模块是**实验性的绕修**，不是已证实的解；它的判定过程会写进追踪日志
 * （`FIX` 行），用来确定"到底哪一步没成立"。真机验证通过之前，不许把它当成修好了。
 *
 * ## 这一步做什么
 *
 * 在应用里**自己把缺的那一次字符补上**：看到空格按下、并且这次按下没有换来任何
 * 空格字符时，向当前焦点组件投一个「输入法提交了一个空格」的事件。
 *
 * ## 为什么是自动纠错的（关键安全设计）
 *
 * **不是"看到空格就补"**，而是"看到空格按下 → 等这一轮事件走完 → 若期间没有任何
 * `KEY_TYPED` 送来空格字符，才补"。于是：
 *   · 拼音（输入法会补）⇒ 守卫判定"已经有人补了"，本模块**什么都不做**，不会出现双空格；
 *   · ABC（没人补）⇒ 守卫判定缺口，补一次。
 *
 * 这条守卫把"我猜错了会怎样"的代价压到零：猜错时它只是不动作，而不是插入多余字符。
 *
 * ## 已知边界（如实写在这里，不假装它是万能修）
 *
 * * 它只补**空格与回车**这两个 JDK 特例里的键；其它键本来就正常。
 * * 它按"焦点组件是 Compose 的内容层"这一条做收窄，避免往非文本区域乱投。
 * * 若将来 JDK 自己修掉了这个特例（`needsKeyReleased` 分支被改写），本模块的守卫会
 *   判定"已经有人补了"，自动退化为空转 —— 不需要再改这里。
 */
internal object DesktopSpaceKeyFix {

    /** 只在 Compose 的内容层上动作：它的类名是生成的 `...hierarchyRoot` 匿名子类。 */
    private const val CONTENT_COMPONENT_HINT = "hierarchyRoot"

    @Volatile
    private var installed = false

    /** 一次待判定的空格：按下时刻没有伴随任何空格字符。 */
    @Volatile
    private var pendingSpace: KeyEvent? = null

    fun install() {
        if (installed) return
        if (runCatching { java.awt.GraphicsEnvironment.isHeadless() }.getOrDefault(true)) return
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                when (event) {
                    is KeyEvent -> onKey(event)
                    // 输入法自己提交了字符（拼音路径）⇒ 这次按下已经被补上，撤销待判定。
                    is InputMethodEvent -> if (eventTextOf(event).isNotEmpty()) pendingSpace = null
                    else -> Unit
                }
            }, AWTEvent.KEY_EVENT_MASK or AWTEvent.INPUT_METHOD_EVENT_MASK)
            installed = true
        }
    }

    private fun onKey(event: KeyEvent) {
        val isSpaceLike = event.keyCode == KeyEvent.VK_SPACE || event.keyCode == KeyEvent.VK_ENTER
        when (event.id) {
            KeyEvent.KEY_TYPED -> {
                // 任何"打字"事件都说明这一轮有字符落地；空格这一轮不需要我们补了。
                if (event.keyChar == ' ' || event.keyChar == '\n' || event.keyChar == '\r') pendingSpace = null
            }

            KeyEvent.KEY_PRESSED -> {
                if (!isSpaceLike) return
                // 每一步拒绝都要留痕：否则"补丁没生效"与"补丁判定为不需要补"在日志里
                // 长得一模一样，又是之前那种"量具看不见就等于没发生"的坑。
                if (event.isControlDown || event.isMetaDown || event.isAltDown) {
                    DesktopKeyTrace.note("FIX", "skip: modifier")
                    return
                }
                if (!isComposeContent(event.component)) {
                    DesktopKeyTrace.note(
                        "FIX",
                        "skip: no compose content above " +
                            (event.component?.javaClass?.name?.substringAfterLast('.') ?: "null"),
                    )
                    return
                }
                DesktopKeyTrace.note("FIX", "armed for keyCode=" + event.keyCode)
                pendingSpace = event
                // 排到当前事件之后：如果 JDK / 输入法会补 KEY_TYPED，它已经排在队列前面，
                // 会先被处理并把 pendingSpace 清掉；走到这里还被判缺口，才是真的缺。
                SwingUtilities.invokeLater { maybeSynthesize() }
            }
        }
    }

    private fun maybeSynthesize() {
        val press = pendingSpace
        pendingSpace = null
        if (press == null) {
            DesktopKeyTrace.note("FIX", "no-op: a real char already arrived")
            return
        }
        val target = composeContentOf(press.component) ?: run {
            DesktopKeyTrace.note("FIX", "no-op: no compose content above " + (press.component?.javaClass?.name ?: "null"))
            return
        }
        // 与按键语义一致：空格补空格，回车补换行。两者在 JDK 那条特例里是同一类。
        val text = if (press.keyCode == KeyEvent.VK_ENTER) "\n" else " "
        val ok = runCatching {
            target.dispatchEvent(
                InputMethodEvent(
                    target,
                    InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    AttributedString(text).iterator,
                    text.length,
                    null,
                    null,
                ),
            )
        }
        DesktopKeyTrace.note(
            "FIX",
            "synthesized text=" + (if (text == " ") "<space>" else "<lf>") +
                " ok=" + ok.isSuccess +
                " err=" + (ok.exceptionOrNull()?.javaClass?.simpleName ?: "none"),
        )
    }

    /**
     * 焦点组件是不是 Compose 的内容层。
     *
     * 只看类名里的 `hierarchyRoot`：那是 CMP 给内容层生成的匿名子类，稳定且不会
     * 与别的组件撞名。宁可少补，也不要往菜单、滚动条之类的地方投文本。
     */
    private fun isComposeContent(c: Component?): Boolean = composeContentOf(c) != null

    /**
     * 从事件来源往上游找到**真正承载输入法会话**的那个组件。
     *
     * 踩过：按键事件的 `component` 是 SkiaLayer 的内部匿名子类（日志里记作
     * `SkiaLayer$1`），而 CMP 把 `InputMethodListener` 挂在 `hierarchyRoot`
     * （`WindowSkiaLayerComponent$hierarchyRoot$1`）上。只按"来源本身叫不叫
     * hierarchyRoot"判断，会把每一次按键都判成"与文本无关"，补丁静默什么都不做 ——
     * 于是"补丁没生效"和"补丁认为不需要补"在日志里长得一样。
     *
     * 所以两件事一起做：**判断**看整条祖先链，**派发**发往命中的那个祖先。
     * 直接发往来源组件是没用的：监听器不在它身上。
     */
    private fun composeContentOf(c: Component?): Component? {
        var cur = c
        var guard = 0
        while (cur != null && guard < 16) {
            if (cur.javaClass.name.contains(CONTENT_COMPONENT_HINT)) return cur
            cur = cur.parent
            guard++
        }
        return null
    }

    /** 取输入法事件里"已提交"的那一段文本（前 `committedCharacterCount` 个字符）。 */
    private fun eventTextOf(e: InputMethodEvent): String {
        val it = e.text ?: return ""
        val end = e.committedCharacterCount.coerceAtMost(it.endIndex)
        if (end <= 0) return ""
        val sb = StringBuilder(end)
        it.setIndex(it.beginIndex)
        while (it.index < end) {
            sb.append(it.current())
            val ch = it.next()
            if (ch == java.text.AttributedCharacterIterator.DONE) break
        }
        return sb.toString()
    }
}
