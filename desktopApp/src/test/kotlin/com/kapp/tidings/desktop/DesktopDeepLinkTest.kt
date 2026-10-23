// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `relay://` 深链解析的单测。
 *
 * 这个解析器的容错范围**故意窄于**手机扫码那一份（`com.kapp.tidings.domain.parseQrPayload`）：
 * 桌面拿到的是命令行参数与系统 URL 事件，裸 `host:port` 会和文件路径混在一起，
 * 所以只认带协议的完整写法。下面「拒绝」那一组用例就是这条边界的守卫 ——
 * 它们比「接受」更重要：解析错一个串的代价是往节点列表里塞进一个假节点，
 * 而假节点会让用户在「为什么连不上」上白花时间。
 */
class DesktopDeepLinkTest {

    @Test
    fun 解析IPv4地址() {
        assertEquals("192.168.1.5" to 8788, DesktopDeepLink.parse("relay://192.168.1.5:8788"))
    }

    @Test
    fun 解析主机名() {
        assertEquals("desktop-node.local" to 9000, DesktopDeepLink.parse("relay://desktop-node.local:9000"))
    }

    @Test
    fun 解析IPv6并去掉方括号() {
        // 冒号是 IPv6 的语法本身，所以端口必须取最后一个冒号之后的部分。
        assertEquals("2602:feda::1" to 8788, DesktopDeepLink.parse("relay://[2602:feda::1]:8788"))
        assertEquals("::1" to 1, DesktopDeepLink.parse("relay://[::1]:1"))
    }

    @Test
    fun 协议头大小写不敏感且容忍首尾空白() {
        assertEquals("10.0.0.2" to 8788, DesktopDeepLink.parse("  RELAY://10.0.0.2:8788  "))
    }

    @Test
    fun 端口边界被接受() {
        assertEquals("h" to 1, DesktopDeepLink.parse("relay://h:1"))
        assertEquals("h" to 65535, DesktopDeepLink.parse("relay://h:65535"))
    }

    // ---- 以下都是必须拒绝的形态 ----

    @Test
    fun 裸主机端口被拒绝因为会和文件路径混淆() {
        // Windows 路径 `C:\a\b` 也是一个「带冒号的串」，所以桌面端不接受裸 host:port。
        assertNull(DesktopDeepLink.parse("192.168.1.5:8788"))
        assertNull(DesktopDeepLink.parse("desktop-node.local:8788"))
    }

    @Test
    fun 其它协议与无关字符串被拒绝() {
        assertNull(DesktopDeepLink.parse("https://192.168.1.5:8788"))
        assertNull(DesktopDeepLink.parse("relay:/192.168.1.5:8788"))
        assertNull(DesktopDeepLink.parse("192.168.1.5"))
        assertNull(DesktopDeepLink.parse(""))
        assertNull(DesktopDeepLink.parse("   "))
    }

    @Test
    fun 缺主机或缺端口被拒绝() {
        assertNull(DesktopDeepLink.parse("relay://"))
        assertNull(DesktopDeepLink.parse("relay://:8788"))
        assertNull(DesktopDeepLink.parse("relay://192.168.1.5:"))
        assertNull(DesktopDeepLink.parse("relay://[::1]"))
    }

    @Test
    fun 非法端口被拒绝() {
        assertNull(DesktopDeepLink.parse("relay://h:0"))
        assertNull(DesktopDeepLink.parse("relay://h:65536"))
        assertNull(DesktopDeepLink.parse("relay://h:-1"))
        assertNull(DesktopDeepLink.parse("relay://h:abc"))
        assertNull(DesktopDeepLink.parse("relay://h:87 88"))
    }

    @Test
    fun 无方括号的IPv6被拒绝因为语法歧义() {
        assertNull(DesktopDeepLink.parse("relay://::1:8788"))
        // 多段冒号的主机同样歧义，不能把 `h:8788` 当主机、`9999` 当端口收下。
        assertNull(DesktopDeepLink.parse("relay://h:8788:9999"))
    }

    @Test
    fun 方括号不配对时被拒绝() {
        assertNull(DesktopDeepLink.parse("relay://[::1:8788"))
        assertNull(DesktopDeepLink.parse("relay://::1]:8788"))
    }

    // ---- 文件关联 ----

    @Test
    fun 只按后缀识别消息文件() {
        assertTrue(DesktopDeepLink.isMessageFile("/tmp/会话.relaymsg"))
        assertTrue(DesktopDeepLink.isMessageFile("C:\\Users\\me\\a.RELAYMSG"))
        assertTrue(DesktopDeepLink.isMessageFile("  b.relaymsg  "))
        assertFalse(DesktopDeepLink.isMessageFile("/tmp/relaymsg"))
        assertFalse(DesktopDeepLink.isMessageFile("/tmp/a.relaymsg.txt"))
        assertFalse(DesktopDeepLink.isMessageFile(""))
    }

    @Test
    fun 消息文件后缀与打包声明一致() {
        // build.gradle.kts 的 fileAssociation 与这里必须同源，否则双击关联到的后缀
        // 和运行期认识的后缀会对不上。
        assertEquals(".relaymsg", DesktopDeepLink.MESSAGE_SUFFIX)
        assertEquals("relay://", DesktopDeepLink.SCHEME)
    }
}
