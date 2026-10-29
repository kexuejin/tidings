// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面平台必须**真的接线** luciola 宿主。
 *
 * 真机现场（验证轮抓到）：`DesktopLuciolaHost` 连同 env 门/seed/配对/三个工厂全都在，
 * 单测也全绿 —— 但 `DesktopAppPlatform` 没有覆写 `luciolaHost`，接口默认 null，
 * 于是 `TIDINGS_LUCIOLA=1` 进了进程、seed 也落了盘，luciolaMode 仍恒 false，
 * 桌面端永远进不了加密数据面。这条守卫钉住「平台必须提供宿主」这一件事；
 * env/开关的门控行为由 [DesktopLuciolaHostTest] 负责。
 */
class DesktopLuciolaHostWiringTest {

    @Test
    fun platformProvidesLuciolaHost() {
        val platform = DesktopAppPlatform()
        val host = assertNotNull(platform.luciolaHost, "DesktopAppPlatform 必须接线 luciolaHost（否则是死代码）")
        assertTrue(host is DesktopLuciolaHost, "桌面的宿主实现必须是 DesktopLuciolaHost")
    }

    @Test
    fun wiringDoesNotChangeDefaultGate() {
        // 测试进程没有 TIDINGS_LUCIOLA ⇒ enabled=false ⇒ configure() 必须是 no-op：
        // 接线之后，没开 env 的桌面行为必须与从前逐字一致（工厂不建、身份不碰盘）。
        val dir = kotlin.io.path.createTempDirectory("relay-wiring").toString()
        val host = DesktopLuciolaHost(dataDirectory = { dir }, enabled = false)
        host.configure(testProfile(), udpPort = 17878)
        assertEquals(
            null,
            host.transportFactory(),
            "env 未开 ⇒ 不建工厂（与接线前的默认行为一致）",
        )
    }
}

/** 测试里只需要一份形状合法的 profile。 */
private fun testProfile(): Profile = Profile.mobile(
    nodeId = "00000000-0000-4000-8000-000000000000",
    nick = "测试机",
    host = "test.local",
    tcpPort = 17879,
    tlsPort = 17880,
    ver = "test",
)
