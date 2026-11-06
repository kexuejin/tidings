// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.AgentHealthLedger

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 健康账本（G1 另一半）的判据：**错误史要活过重启**。
 * 真机现场：第一单链 error 在内存台账里，重启后第二单 planner 又把审核棒
 * 指给同一个 agent —— 这组用例把「重启后 lastState 仍能读到 error」钉住。
 */
class AgentHealthLedgerTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("health").toFile()
        return File(dir, "agent-health.jsonl")
    }

    @Test
    fun anErrorSurvivesANewLedgerInstanceLikeAHostRestart() {
        val file = tempFile()
        val first = AgentHealthLedger(file)
        first.record("claude-cli", "s1", "error", 1_000L)
        first.record("codex-cli", "s2", "done", 2_000L)

        // 新实例 = 宿主重启：从文件回放
        val second = AgentHealthLedger(file)
        assertEquals("error", second.lastState("claude-cli"), "错误史必须活过重启")
        assertEquals("done", second.lastState("codex-cli"))
        assertNull(second.lastState("opencode-cli"), "没有史 = null，不冤枉")
    }

    @Test
    fun theLatestStateWinsRegardlessOfWriteOrder() {
        val file = tempFile()
        val ledger = AgentHealthLedger(file)
        ledger.record("dsh-cli", "s1", "error", 1_000L)
        ledger.record("dsh-cli", "s2", "done", 2_000L)
        assertEquals("done", ledger.lastState("dsh-cli"))
        // 迟到的旧帧不许倒转状态
        ledger.record("dsh-cli", "s0", "error", 500L)
        assertEquals("done", ledger.lastState("dsh-cli"))
    }

    @Test
    fun sameSessionSameStateIsNotRewritten() {
        val file = tempFile()
        val ledger = AgentHealthLedger(file)
        ledger.record("codex-cli", "s1", "working", 1_000L)
        ledger.record("codex-cli", "s1", "working", 1_100L)
        ledger.record("codex-cli", "s1", "working", 1_200L)
        assertEquals(1, file.readLines().size, "同会话同状态只写一次：帧量不决定记录量")
    }
}
