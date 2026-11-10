// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面平台必须**真的接线** luciola 宿主。
 *
 * 真机现场（验证轮抓到）：`DesktopLuciolaHost` 连同 seed/配对/三个工厂全都在，
 * 单测也全绿 —— 但 `DesktopAppPlatform` 没有覆写 `luciolaHost`，接口默认 null，
 * 于是 luciolaMode 仍恒 false，桌面端永远进不了加密数据面。这条守卫钉住
 * 「平台必须提供宿主」这一件事。
 *
 * 曾经这里还有一条 `wiringDoesNotChangeDefaultGate`：断言 `TENON_LUCIOLA` 未设时
 * `configure()` 是 no-op。**开关与那条用例一起删掉了** —— 传输形态是协议事实，
 * 不允许一端开一端关（见 [DesktopLuciolaHost] 的 KDoc）。
 */
class DesktopLuciolaHostWiringTest {

    @Test
    fun platformProvidesLuciolaHost() {
        val platform = DesktopAppPlatform()
        val host = assertNotNull(platform.luciolaHost, "DesktopAppPlatform 必须接线 luciolaHost（否则是死代码）")
        assertTrue(host is DesktopLuciolaHost, "桌面的宿主实现必须是 DesktopLuciolaHost")
    }
}
