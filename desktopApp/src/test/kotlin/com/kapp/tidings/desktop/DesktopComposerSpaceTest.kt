// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.kapp.tidings.ui.desktop.DesktopComposer
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 桌面输入框：**一个空格就是一个空格**。
 *
 * ## 这条测试守的是什么
 *
 * 桌面端有一层 `DesktopSpaceKeyFix`：它补的是 macOS + JDK 让空格丢掉的那一次
 * `KEY_TYPED`（病根在 `CPlatformResponder` 把空格/回车当"结束输入法处理"的特例）。
 *
 * 这类"自己补字符"的绕修，最贵的失败形态不是"没补上"，而是**补多了一个**：
 * 输入法已经补过、我们再加一个，用户打一个空格得到两个。那比缺陷本身更难查，
 * 因为它只在特定输入法状态下出现。
 *
 * 所以这里钉的是**最终文本**，不是"补丁有没有动作"：
 *   · 打进一个空格 ⇒ 草稿里**恰好**一个空格；
 *   · 再打进字母 ⇒ 字母紧跟在空格后面，没有被挤掉、也没有多出空白。
 *
 * ## 为什么用场景而不是真实窗口
 *
 * 本仓没有真实窗口的按键驱动（系统焦点拿不到，前面几轮已实测），而这条测试要验证的
 * 是"文本最终长什么样" —— 场景级完全够用，且能进 CI。真实窗口下的空格是否恢复，
 * 由发布前的真机验证负责（见报告）。
 */
class DesktopComposerSpaceTest {

    private fun awtTyped(ch: Char) = java.awt.event.KeyEvent(
        java.awt.Canvas(),
        java.awt.event.KeyEvent.KEY_TYPED,
        0L,
        0,
        java.awt.event.KeyEvent.VK_UNDEFINED,
        ch,
    )

    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, ch: Char): Boolean =
        scene.sendKeyEvent(
            KeyEvent(
                key = Key.Unknown,
                type = KeyEventType.Unknown,
                codePoint = ch.code,
                nativeEvent = awtTyped(ch),
            ),
        )

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun oneSpaceIsExactlyOneSpace() {
        var draft = ""
        val scene = ImageComposeScene(width = 900, height = 600, density = Density(1f)) {
            AppTheme(darkTheme = false) {
                var d by remember { mutableStateOf("") }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    DesktopComposer(
                        peer = zhouPeer(),
                        draft = d,
                        onDraftChange = {
                            d = it
                            draft = it
                        },
                        controller = DesktopPreviewActions,
                    )
                }
            }
        }
        try {
            // 先点进输入框再打字：这条测试只关心字符计数，不关心自动聚焦
            //（后者由 DesktopComposerAutoFocusTest 单独钉）。
            repeat(4) { scene.render() }
            val p = Offset(150f, 110f)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = PointerButton.Primary)
            repeat(2) { scene.render() }

            type(scene, 'a')
            repeat(2) { scene.render() }
            type(scene, ' ')
            repeat(2) { scene.render() }
            type(scene, 'b')
            repeat(2) { scene.render() }

            assertEquals("a b", draft, "空格既不能被吞、也不能被补成两个")
        } finally {
            scene.close()
        }
    }
}
