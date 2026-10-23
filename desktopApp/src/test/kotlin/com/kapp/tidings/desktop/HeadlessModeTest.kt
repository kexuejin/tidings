// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `--headless` 的开关语义与**它不该越界的地方**。
 *
 * ## 为什么这两条要一起钉
 *
 * `--headless` 服务的是"量守护进程内存"与"判 UI 是否只是观察者"这两个问题。
 * 如果它被写宽了 —— 比如把 `--background` 也当成无界面 —— 后果是**登录自启
 * 之后桌面上什么都没有**：进程在跑、消息在收、托盘却没有，用户只能以为应用坏了。
 *
 * 反过来，写窄了（只认某个环境变量）则那个读数不可复现：命令行参数是可复制、
 * 可写进证据的东西。
 */
class HeadlessModeTest {

    @Test
    fun `认 --headless`() {
        assertTrue(HeadlessMode.requested(listOf("--headless")))
        assertTrue(
            HeadlessMode.requested(listOf("--headed", "--headless", "--background")),
            "与其它开关混用时也要认出来",
        )
    }

    @Test
    fun `--background 不是 headless`() {
        assertFalse(
            HeadlessMode.requested(listOf(DesktopLoginItem.BACKGROUND_FLAG)),
            "--background 是「托盘驻留、不弹主窗」，产品行为；把它当成无界面会让登录自启后什么都没有",
        )
        assertFalse(HeadlessMode.requested(emptyList()))
        assertFalse(HeadlessMode.requested(listOf("/tmp/a.relaymsg")))
    }

    @Test
    fun `前缀相似不算`() {
        assertFalse(HeadlessMode.requested(listOf("--headless=false")), "开关只认完整字面量，不做参数解析")
        assertFalse(HeadlessMode.requested(listOf("headless")), "少两个连字符不是它")
    }
}
