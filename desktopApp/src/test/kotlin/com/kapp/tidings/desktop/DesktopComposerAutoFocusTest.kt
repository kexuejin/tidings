// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.kapp.tidings.ui.desktop.DesktopComposer
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 桌面输入框**不用先点**就能打字 —— 用真组件在场景里跑，不靠源码字符串。
 *
 * ## 为什么这条必须是真的跑一遍
 *
 * 这个缺陷在真机上被报了三次（「桌面端打不进空格」），每一轮的结论都停在
 * 「读代码看不出」：旧守卫只断言源码里出现 `requestFocus()`，而那句话存在、
 * 测试常绿、缺陷也在。真正的失败是**返回值**：`FocusRequester.requestFocus()`
 * 在输入框还没进焦点树的头两帧里返回 false 且不抛异常，旧代码
 * `runCatching { requestFocus() }` 把它扔了，于是自动聚焦每一次都落空。
 *
 * 症状之所以像"空格的问题"：点一下输入框之后一切正常（空格、字母都进得去），
 * 而不点的时候**所有**按键都进不去 —— 用户最先注意到的是空格，因为写句子离不开它。
 *
 * 这条测试把"不点击、直接打字"这个动作固化下来：它一旦变红，就是又回到了
 * "必须先点一下"的状态。
 */
class DesktopComposerAutoFocusTest {

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
    fun typingWithoutClickingReachesTheComposer() {
        var draft = ""
        val scene = ImageComposeScene(width = 900, height = 600, density = Density(1f)) {
            AppTheme(darkTheme = false) {
                var d by remember { mutableStateOf("") }
                Box(Modifier.fillMaxSize()) {
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
            // 帧预算要够：聚焦是"首帧 false、下一帧再试"，所以必须让场景真的前进几帧。
            repeat(8) { scene.render() }
            val letter = type(scene, 'a')
            repeat(2) { scene.render() }
            assertEquals(true, letter, "不点击输入框时字母进不去 —— 自动聚焦没成功")
            assertEquals("a", draft, "字母被吞了：草稿里应当已经有 'a'")

            val space = type(scene, ' ')
            repeat(2) { scene.render() }
            assertEquals(true, space, "空格被吞了 —— 这正是用户反复报的那个症状")
            assertEquals("a ", draft, "空格没落进草稿")
        } finally {
            scene.close()
        }
    }
}
