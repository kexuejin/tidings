// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.HostDirectoryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 项目目录端口的逐方法转发钉子（项目目录决策稿 §5.1）。
 *
 * 端口是 store 的**同实例视图**，这里钉两件事：
 *  1. UI 形状与存储形状**逐字段对齐**（多一列少一列都会变成面板上的假数据）；
 *  2. 转发不吞语义：null/false 的失败方向与 store 一致（注册失败 ≠ 已存在；
 *     悬空指针保存被拒 = 什么都没改）。
 *
 * 反向验证：把 [DesktopHostDirectoryPort.register] 的 blank 前置删掉、也不捕获
 * IllegalArgumentException（让 store 直接上抛到 UI 层），[registerRejectsBlankPathAsNull]
 * 变红（抛异常而不是回 null）。
 */
class DesktopHostDirectoryPortTest {

    private val file: File = Files.createTempFile("relay-host-dir-port", ".json").toFile()
    private val tempDirs: MutableList<File> = mutableListOf()

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
        tempDirs.forEach { runCatching { it.delete() } }
    }

    private fun newDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun port() = DesktopHostDirectoryPort(HostDirectoryStore(file))

    @Test
    fun registerForwardsAndSnapshotCarriesIt() {
        val dir = newDir("relay-port-beta-")
        val p = port()

        val d = p.register(dir.absolutePath, "Beta")
        assertTrue(d != null, "登记成功要回条目")
        assertEquals(dir.name, d.path.substringAfterLast('/'), "path 落的是 canonical 形态")
        assertEquals("Beta", d.displayName)

        val snap = p.snapshot()
        assertEquals(1, snap.directories.size)
        assertEquals(d.dirId, snap.directories.single().dirId)
        assertEquals(d.path, snap.directories.single().path)
        assertEquals("Beta", snap.directories.single().displayName)
        assertNull(snap.directories.single().gitRemote, "没给 gitRemote 就如实是 null")
        // 登记即纳入白名单（readOnly=false），快照里那条也在。
        assertEquals(1, snap.whitelist.size)
        assertEquals(d.dirId, snap.whitelist.single().dirId)
        assertFalse(snap.whitelist.single().readOnly)
    }

    @Test
    fun registerRejectsBlankPathAsNull() {
        val p = port()

        assertNull(p.register("   ", null), "空白路径 = 没成（null），不是异常冒到 UI 层")
        assertEquals(0, p.snapshot().directories.size)
    }

    @Test
    fun setGroupPointerForwardsTheVerdict() {
        val dir = newDir("relay-port-gamma-")
        val p = port()
        val d = p.register(dir.absolutePath, null)!!

        assertFalse(p.setGroupPointer("g-1", "dir-does-not-exist"), "悬空 dirId 的拒绝原样透传")
        assertTrue(p.setGroupPointer("g-1", d.dirId))
        assertEquals(d.dirId, p.snapshot().groupPointers["g-1"])
        assertTrue(p.setGroupPointer("g-1", null))
        assertEquals(emptyMap(), p.snapshot().groupPointers, "null = 清除")
    }

    @Test
    fun setWhitelistForwardsTheVerdict() {
        val dir = newDir("relay-port-delta-")
        val p = port()
        val d = p.register(dir.absolutePath, null)!!

        assertFalse(p.setWhitelist("dir-does-not-exist", readOnly = false, allowed = true))
        assertTrue(p.setWhitelist(d.dirId, readOnly = true, allowed = true))
        val entry = p.snapshot().whitelist.single { it.dirId == d.dirId }
        assertTrue(entry.readOnly, "只读档逐字段对齐")
        assertTrue(p.setWhitelist(d.dirId, readOnly = false, allowed = false))
        assertTrue(p.snapshot().whitelist.none { it.dirId == d.dirId }, "收回后条目消失")
    }

    /**
     * 系统选目录窗的**能力位**：没注入选择器就要如实说「弹不出来」。
     *
     * 界面靠这一位决定画不画那颗图标 —— 若它恒 true，无头宿主/测试装配会画出一颗
     * 点下去什么都不发生的按钮（正是本轮要修的那个形态）。
     */
    @Test
    fun withoutAPickerThePortSaysItCannotPick() = runBlocking {
        val p = port()

        assertFalse(p.canPickDirectory, "没注入选择器就必须报 false")
        assertNull(p.pickDirectory(), "没有选择器时调用要回 null，不是抛异常")
    }

    /** 有选择器：原样转述返回值；用户取消（null）不是失败，不许被吞成空串或异常。 */
    @Test
    fun pickerReturnsTheChosenPathAndCancelIsNull() = runBlocking {
        var answer: String? = "/tmp/某个目录"
        val p = DesktopHostDirectoryPort(
            store = HostDirectoryStore(file),
            directoryPicker = { answer },
        )

        assertTrue(p.canPickDirectory, "注入了选择器要报 true")
        assertEquals("/tmp/某个目录", p.pickDirectory(), "选中的绝对路径原样返回")

        answer = null
        assertNull(p.pickDirectory(), "用户取消 = null（调用方据此保持原输入不变）")
        assertEquals(0, p.snapshot().directories.size, "选目录本身不登记任何东西")
    }
}
