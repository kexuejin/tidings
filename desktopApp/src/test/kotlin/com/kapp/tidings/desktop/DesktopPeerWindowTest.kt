// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D10「多窗口」里唯一能在离屏环境验证的那部分：窗口表的开/关规则。
 *
 * 真正的窗口本身（`ComposeWindow`）需要图形环境，只能手工验收；但「同一会话不开
 * 第二扇」「关掉就真的消失」这两条如果错了，表现是"点了两次出现两扇窗、关不掉"，
 * 值得用测试钉住。
 */
class DesktopPeerWindowTest {

    @Test
    fun 首次打开返回真并进入窗口表() {
        val registry = PeerWindowRegistry()
        assertTrue(registry.open("peer-a"))
        assertEquals(listOf("peer-a"), registry.open)
    }

    @Test
    fun 同一会话不会开出第二扇窗口() {
        val registry = PeerWindowRegistry()
        registry.open("peer-a")
        // 第二次点击不是"新开"，也不该在表里多出一项 —— 否则会出现两扇窗口显示
        // 同一个会话，还各自算一套滚动位置。
        assertFalse(registry.open("peer-a"))
        assertEquals(listOf("peer-a"), registry.open)
    }

    @Test
    fun 不同会话各自一扇窗口并保持打开顺序() {
        val registry = PeerWindowRegistry()
        registry.open("peer-a")
        registry.open("peer-b")
        registry.open("peer-c")
        assertEquals(listOf("peer-a", "peer-b", "peer-c"), registry.open)
    }

    @Test
    fun 关掉一扇窗口后可以重新打开() {
        val registry = PeerWindowRegistry()
        registry.open("peer-a")
        registry.open("peer-b")
        registry.close("peer-a")
        // 关中间那个不影响别的窗口。
        assertEquals(listOf("peer-b"), registry.open)
        // 关掉之后必须能再开 —— 否则用户关错了就再也打不开这个会话的独立窗口。
        assertTrue(registry.open("peer-a"))
        assertEquals(listOf("peer-b", "peer-a"), registry.open)
    }

    @Test
    fun 关闭不存在的窗口是无害的() {
        val registry = PeerWindowRegistry()
        registry.open("peer-a")
        registry.close("peer-missing")
        assertEquals(listOf("peer-a"), registry.open)
    }

    @Test
    fun 空白会话ID不会开出空窗口() {
        val registry = PeerWindowRegistry()
        // 空 id 的窗口没有会话可显示，用户只能把它关掉 —— 不如不开。
        assertFalse(registry.open(""))
        assertFalse(registry.open("   "))
        assertTrue(registry.open.isEmpty())
    }

    @Test
    fun 快照与窗口表解耦() {
        // DesktopPeerWindows 先取快照再遍历：遍历时关窗会改表，直接遍历可变列表
        // 会抛 ConcurrentModificationException。快照必须是独立对象。
        val registry = PeerWindowRegistry()
        registry.open("peer-a")
        registry.open("peer-b")
        val snapshot = registry.open.toList()
        registry.close("peer-a")
        assertEquals(listOf("peer-a", "peer-b"), snapshot)
        assertEquals(listOf("peer-b"), registry.open)
    }
}
