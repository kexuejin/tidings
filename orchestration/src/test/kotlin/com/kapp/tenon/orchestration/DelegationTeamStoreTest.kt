// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.DelegationTeam
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16.3 存储契约与三态失败语义 + §13.4 冷启动物理穿透。
 *
 * 这份文件是「配置真的写进磁盘了吗」的判据 —— 内存幻觉（看起来在记、其实没记）
 * 是本仓最贵的缺陷形态之一，所以冷启动回读是这里的**第一条**测试。
 */
class DelegationTeamStoreTest {

    private var dir: File? = null

    @AfterTest
    fun tearDown() {
        dir?.deleteRecursively()
        dir = null
    }

    private fun newFile(): File {
        val d = Files.createTempDirectory("delegation-teams-test").toFile().also { dir = it }
        return File(d, "delegation-teams.json")
    }

    private fun store(file: File = newFile()) = DelegationTeamStore(file)

    private fun team(groupId: String = "grp-1", worker: String = "claude", reviewer: String = "dsh") =
        DelegationTeam(groupId, worker, reviewer, updatedAt = 0L)

    // ------------------------------------------------ 冷启动物理穿透（§13.4）

    @Test
    fun coldStartReadsBackExactlyWhatWasSaved() {
        val file = newFile()
        val first = DelegationTeamStore(file)
        assertTrue(first.save(team()), "save 必须成功：失败通道被吞 = 「看起来在记其实没记」")
        // 冷启动：销毁当前实例（内存引用全清），用全新实例从物理磁盘重读。
        val reborn = DelegationTeamStore(file)
        val read = reborn.teamFor("grp-1")
        assertEquals("claude", read?.workerKey, "workerKey 必须逐字还原")
        assertEquals("dsh", read?.reviewerKey, "reviewerKey 必须逐字还原")
    }

    @Test
    fun filePermissionsAreOwnerOnly() {
        val file = newFile()
        assertTrue(store(file).save(team()))
        // 非 POSIX 文件系统（Windows CI 等）拿不到 posix 权限 —— 跳过，不算失败。
        val perms = runCatching {
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
        }.getOrNull() ?: return
        assertEquals("rw-------", perms, "0600：宿主侧派单策略不是偏好（照 agent-grants.json 同纪律）")
    }

    // ------------------------------------------------ 三态失败语义（§16.3）

    @Test
    fun sameVendorOrUnknownKeysAreRejectedAtSaveTime() {
        val s = store()
        assertFalse(s.save(team(worker = "codex", reviewer = "codex")), "干活 = 审核：必须拒绝")
        assertFalse(s.save(team(worker = "no-such-agent", reviewer = "dsh")), "键不在档案表：必须拒绝")
        assertFalse(s.save(team(worker = "claude", reviewer = "no-such-agent")), "审核键不在档案表：必须拒绝")
        assertNull(s.teamFor("grp-1"), "被拒绝的配置不许留下半条")
    }

    @Test
    fun corruptFileIsIgnoredNotFatal() {
        val file = newFile()
        file.writeText("{ this is not json")
        assertNull(store(file).teamFor("grp-1"), "损坏 ⇒ 忽略模板（回落保底），不抛、不卡死派单")
        // 且落盘未被覆盖：损坏文件仍在（诊断已有，Rediscover 由 loadLocked 记日志）。
        assertTrue(file.isFile)
    }

    @Test
    fun unknownSchemaVersionIsDistrustedAsAWhole() {
        val file = newFile()
        file.writeText("""{"schemaVersion": 99, "teams": []}""")
        assertNull(store(file).teamFor("grp-1"), "未知版本 ⇒ 整份不可信（§16.2）")
    }

    @Test
    fun illegalEntryMakesTheWholeFileUntrusted() {
        val file = newFile()
        file.writeText(
            """{"schemaVersion": 1, "teams": [
                {"groupId": "grp-1", "workerKey": "claude", "reviewerKey": "dsh", "updatedAt": 1},
                {"groupId": "grp-2", "workerKey": "made-up-key", "reviewerKey": "dsh", "updatedAt": 1}
            ]}""",
        )
        assertNull(store(file).teamFor("grp-1"), "一条非法 ⇒ 整份不可信：不许「好的留下、坏的跳过」（§16.2）")
    }

    @Test
    fun missingFileMeansNoTemplateAndSavesCreateIt() {
        val file = newFile()
        val s = store(file)
        assertNull(s.teamFor("grp-1"), "没配过 = 正常空，不是错误")
        assertTrue(s.save(team()), "首次保存要能把文件建出来")
        assertEquals("claude", DelegationTeamStore(file).teamFor("grp-1")?.workerKey)
    }

    // ------------------------------------------------ 并发可见性（入口 A / B 共用一份事实）

    @Test
    fun handEditedFileIsPickedUpByTheSameInstance() {
        val file = newFile()
        val s = store(file)
        assertTrue(s.save(team()))
        // 入口 B：绕过 store 直接手改文件（mtime 失效路径）。
        file.writeText(
            """{"schemaVersion": 1, "teams": [
                {"groupId": "grp-1", "workerKey": "codex", "reviewerKey": "dsh", "updatedAt": 2}
            ]}""",
        )
        assertEquals(
            "codex", s.teamFor("grp-1")?.workerKey,
            "同一实例必须看到手改：mtime 快照失效是入口 B 的接口",
        )
    }

    @Test
    fun savingAgainForTheSameGroupOverwrites() {
        val file = newFile()
        val s = store(file)
        assertTrue(s.save(team(worker = "claude")))
        assertTrue(s.save(team(worker = "codex")))
        assertEquals("codex", s.teamFor("grp-1")?.workerKey, "同群重配 = 覆盖，不追加第二份")
    }

    @Test
    fun otherGroupsSurviveASaveForOneGroup() {
        val file = newFile()
        val s = store(file)
        assertTrue(s.save(team(groupId = "grp-1")))
        assertTrue(s.save(team(groupId = "grp-2", worker = "codex")))
        val reborn = DelegationTeamStore(file)
        assertEquals("claude", reborn.teamFor("grp-1")?.workerKey, "保存 grp-2 不许动 grp-1")
        assertEquals("codex", reborn.teamFor("grp-2")?.workerKey)
    }
}
