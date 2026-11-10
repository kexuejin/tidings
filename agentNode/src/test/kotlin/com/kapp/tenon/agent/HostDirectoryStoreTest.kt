// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 宿主本地项目目录档案（目录 / 群指针 / 白名单一份文件）。
 *
 * 钉五件事，每一条错了都会变成「面板假账」或「目录被静默放行」：
 *  1. **登记幂等**：同 canonical path 重入不换 dirId（群指针指着旧 id 就成了悬空）；
 *  2. **默认 deny**：白名单为空 / 无条目 ⇒ 全拒；readOnly 条目挡写；
 *  3. **指针不许悬空**：指向未登记目录的保存当场拒；
 *  4. **remove 级联**：删档案同时清指针与白名单条目，不留半份假账；
 *  5. **落盘可读回 + 读不回来 = 全空**（fail-closed：空档案的效果是全拒，不是全放）。
 *
 * 反向验证：把 [HostDirectoryStore.register] 的幂等分支去掉（每次都铸新 dirId），
 * [registerIsIdempotentOnTheCanonicalPath] 变红（两条档案、两个 dirId）；
 * 把 [HostDirectoryStore.remove] 的指针级联删掉，[removeCascadesPointersAndWhitelist] 变红。
 */
class HostDirectoryStoreTest {

    private val file: File = File.createTempFile("relay-host-dirs-", ".json").apply { delete() }

    /** 登记用的真实临时目录（只登记路径，不往里写东西，清理只需删目录本身）。 */
    private val tempDirs: MutableList<File> = mutableListOf()

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
        tempDirs.forEach { runCatching { it.delete() } }
    }

    private fun newDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun store() = HostDirectoryStore(file)

    @Test
    fun registerIsIdempotentOnTheCanonicalPath() {
        val dir = newDir("relay-proj-")
        val s = store()

        val first = s.register(dir.absolutePath, displayName = null)
        // 尾斜杠是同一路径的另一种拼写：canonical 归一后必须落回同一条。
        val second = s.register(dir.absolutePath + "/", displayName = "renamed")

        assertEquals(first.dirId, second.dirId, "同一路径重入不许换 dirId —— 群指针指着旧 id")
        assertEquals(first.displayName, second.displayName, "重入是幂等回读，不是改名通道")
        assertEquals(1, s.directories().size)
        s.setGroupPointer("g-1", first.dirId)
        assertEquals(first.dirId, s.pointerOf("g-1"))
    }

    @Test
    fun registerFillsDefaultsAndAutoAllowlists() {
        val dir = newDir("relay-alpha-")
        val s = store()

        val d = s.register(dir.absolutePath, displayName = null, gitRemote = "git@example.com/a.git")

        assertEquals(dir.name, d.displayName, "displayName 缺省取目录名（路径最后一段）")
        assertEquals("git@example.com/a.git", d.gitRemote)
        assertEquals(d.path, File(dir.absolutePath).canonicalPath, "档案里落的必须是 canonical path")
        assertTrue(
            s.whitelistAllows(d.dirId, requireWrite = true),
            "登记是显式动作即授权（D15）：登记即纳入白名单为可读写",
        )
    }

    @Test
    fun blankPathIsRejectedNotRegisteredAsCwd() {
        val s = store()

        assertFailsWith<IllegalArgumentException> { s.register("   ", displayName = null) }
        assertEquals(0, s.directories().size, "空白路径不许静默登记成当前目录")
    }

    @Test
    fun whitelistIsDefaultDenyAndReadOnlyBlocksWrites() {
        val dir = newDir("relay-beta-")
        val s = store()
        val unknown = "dir-000000000000"

        assertFalse(s.whitelistAllows(unknown, requireWrite = false), "白名单为空 = 全拒（默认 deny）")
        assertFalse(s.whitelistAllows("", requireWrite = false), "判据缺一角只能更严")

        val d = s.register(dir.absolutePath, displayName = null)
        assertTrue(s.whitelistAllows(d.dirId, requireWrite = true))

        assertTrue(s.setWhitelist(d.dirId, readOnly = true, allowed = true))
        assertTrue(s.whitelistAllows(d.dirId, requireWrite = false), "只读档放行读")
        assertFalse(s.whitelistAllows(d.dirId, requireWrite = true), "只读档必须挡写")

        assertTrue(s.setWhitelist(d.dirId, readOnly = false, allowed = false))
        assertFalse(s.whitelistAllows(d.dirId, requireWrite = false), "收回后连读也拒")
        assertFalse(s.setWhitelist(unknown, readOnly = false, allowed = true), "未登记的目录谈不到放行")
    }

    @Test
    fun setGroupPointerRejectsDanglingDirId() {
        val dir = newDir("relay-gamma-")
        val s = store()
        val d = s.register(dir.absolutePath, displayName = null)

        assertFalse(s.setGroupPointer("", d.dirId), "群 id 空白 = 参数不成立")
        assertFalse(s.setGroupPointer("g-1", "dir-does-not-exist"), "悬空指针在保存这一刻就拒")
        assertFalse(s.setGroupPointer("g-1", "   "))
        assertEquals(null, s.pointerOf("g-1"), "被拒的保存什么都没改")

        assertTrue(s.setGroupPointer("g-1", d.dirId))
        assertEquals(d.dirId, s.pointerOf("g-1"))
        assertTrue(s.setGroupPointer("g-1", null))
        assertEquals(null, s.pointerOf("g-1"), "null = 清除")
        assertTrue(s.setGroupPointer("g-1", null), "清除幂等：本来就没设也是成功")
    }

    @Test
    fun removeCascadesPointersAndWhitelist() {
        val dir = newDir("relay-delta-")
        val s = store()
        val d = s.register(dir.absolutePath, displayName = null)
        s.setGroupPointer("g-1", d.dirId)
        assertTrue(s.whitelistAllows(d.dirId, requireWrite = true))

        assertTrue(s.remove(d.dirId))
        assertEquals(null, s.directoryOf(d.dirId), "档案删了")
        assertEquals(null, s.pointerOf("g-1"), "指向它的群指针一并清掉，不留假账")
        assertFalse(s.whitelistAllows(d.dirId, requireWrite = false), "白名单条目一并清掉")
        assertFalse(s.remove(d.dirId), "再删一次是 no-op")
        assertFalse(s.remove("dir-does-not-exist"))
        assertFalse(s.remove(""))
    }

    @Test
    fun aBrokenFileMeansAnEmptyLedgerNotAnOpenDoor() {
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON")
        var s = store()

        assertEquals(0, s.directories().size, "读不回来 = 整份按空（fail-closed）")
        assertFalse(s.whitelistAllows("dir-whatever", requireWrite = false), "空档案的效果是全拒")

        // 版本不认识 = 整份不可信，同样按空（不许拿半旧数据当现状）。
        file.writeText("""{"schemaVersion":99,"directories":[],"groupPointers":{},"whitelist":[]}""")
        s = store()
        assertEquals(0, s.directories().size)
    }

    @Test
    fun persistedFileIsReadableJsonAndSurvivesARestart() {
        val dir = newDir("relay-eps-")
        val s = store()
        val d = s.register(dir.absolutePath, displayName = "Eps", gitRemote = null)
        s.setGroupPointer("g-9", d.dirId)
        s.setWhitelist(d.dirId, readOnly = true, allowed = true)

        assertTrue(file.isFile, "persist 后文件必须存在")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText())
            as kotlinx.serialization.json.JsonObject
        assertEquals(1L, (root["schemaVersion"] as kotlinx.serialization.json.JsonPrimitive).content.toLong())

        // 重启（新实例从同一份文件装载）后档案、指针、白名单都还在。
        val reopened = store()
        val again = reopened.directoryOf(d.dirId)
        assertEquals("Eps", again?.displayName)
        assertEquals(d.dirId, reopened.pointerOf("g-9"))
        assertTrue(reopened.whitelistAllows(d.dirId, requireWrite = false))
        assertFalse(reopened.whitelistAllows(d.dirId, requireWrite = true), "readOnly 档重启后不许悄悄变回可写")
    }

    @Test
    fun changeListenerFiresWhenTheLedgerChanges() {
        val dir = newDir("relay-zeta-")
        val s = store()
        var fired = 0
        val stop = s.addChangeListener { fired += 1 }

        s.register(dir.absolutePath, displayName = null)
        assertEquals(1, fired, "登记要通知（写它的人不在盯着这块 UI）")

        s.setGroupPointer("g-1", "dir-does-not-exist")
        assertEquals(1, fired, "被拒的保存不发通知（什么都没变）")

        stop()
        val other = newDir("relay-zeta2-")
        s.register(other.absolutePath, displayName = null)
        assertEquals(1, fired, "注销后不再收")
    }
}
