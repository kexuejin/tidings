// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L1 会话免审台账的判据：落表（含过期语义）、查表命中、重落续期、撤销。
 * 全部用合成时刻 —— 不读时钟（envDependentTestGate 的口径）。
 */
class SessionExemptLedgerTest {

    /** 与 PitfallLedgerTest 同一条惯例：tmpdir + nanoTime，不用被弃用的 createTempDir。 */
    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "session-exempt-" + System.nanoTime())

    private fun store(dir: File) = SessionExemptLedger(File(dir, "session-exempts.json"))

    @Test
    fun grantLandsWithExpiryAndVerdictHitsWithinTtl() {
        val dir = tempDir()
        val s = store(dir)
        val now = 1_000_000L
        assertTrue(s.grant(sessionKey = "agy-conv1", agentKey = "codex", grantedBy = "phone-1", at = now))
        // TTL 内命中。
        assertEquals(SessionExemptLedger.Verdict.HIT, s.verdictOf("agy-conv1", "codex", now + 1))
        assertEquals(SessionExemptLedger.Verdict.HIT, s.verdictOf("agy-conv1", "codex", now + s.DEFAULT_TTL_MS - 1))
        // 过期边界（now == expiresAt 算过期）：判 EXPIRED 并清行 —— 会话粒度不是无限期。
        assertEquals(SessionExemptLedger.Verdict.EXPIRED, s.verdictOf("agy-conv1", "codex", now + s.DEFAULT_TTL_MS))
        assertEquals(0, s.snapshot(now + s.DEFAULT_TTL_MS).size)
    }

    @Test
    fun verdictFailsClosedOnEmptyOrMismatchedKeys() {
        val dir = tempDir()
        val s = store(dir)
        val now = 1_000_000L
        assertTrue(s.grant("agy-conv1", "codex", at = now))
        // 空会话键 ⇒ MISS（没有范围的放行无法表达「只放行哪个会话」）。
        assertEquals(SessionExemptLedger.Verdict.MISS, s.verdictOf("  ", "codex", now))
        assertEquals(SessionExemptLedger.Verdict.MISS, s.verdictOf("", "codex", now))
        // grant 侧对称：空键拒写。
        assertFalse(s.grant(" ", "codex", at = now))
        assertFalse(s.grant("agy-conv2", " ", at = now))
    }

    @Test
    fun regrantSameKeysRefreshesLease() {
        val dir = tempDir()
        val s = store(dir)
        val t0 = 1_000_000L
        assertTrue(s.grant("agy-conv1", "codex", at = t0))
        assertTrue(s.grant("agy-conv1", "codex", grantedBy = "phone-2", at = t0 + 60_000))
        val rows = s.snapshot(t0 + 61_000)
        assertEquals(1, rows.size, "同 (会话, agent) 重落是续期不是追加")
        assertEquals(t0 + 60_000, rows.single().grantedAt)
        assertEquals("phone-2", rows.single().grantedBy)
    }

    @Test
    fun otherAgentRowIsNotClearedWhenOneExpires() {
        val dir = tempDir()
        val s = store(dir)
        val t0 = 1_000_000L
        assertTrue(s.grant("agy-conv1", "codex", at = t0, ttlMs = 1000))
        assertTrue(s.grant("agy-conv1", "claude", at = t0))
        // codex 的行过期：清它，但不能把 claude 还在租期内的行一起清掉。
        assertEquals(SessionExemptLedger.Verdict.EXPIRED, s.verdictOf("agy-conv1", "codex", t0 + 1000))
        assertEquals(1, s.snapshot(t0 + 1001).size, "别的 agent 的免审不受牵连")
        assertEquals(SessionExemptLedger.Verdict.HIT, s.verdictOf("agy-conv1", "claude", t0 + 1001))
    }

    @Test
    fun persistRoundTripKeepsLiveGrantsOnlyAcrossInstances() {
        val dir = tempDir()
        val t0 = 1_000_000L
        store(dir).grant("agy-conv1", "codex", grantedBy = "phone-1", at = t0)
        // 新实例读回：租约内 ⇒ 命中（丢一条免审只多问一次，但这里根本不该丢）。
        val s2 = store(dir)
        assertEquals(SessionExemptLedger.Verdict.HIT, s2.verdictOf("agy-conv1", "codex", t0 + 1000))
        // 撤销立刻生效。
        assertEquals(1, s2.revoke("agy-conv1"))
        val s3 = store(dir)
        assertEquals(SessionExemptLedger.Verdict.MISS, s3.verdictOf("agy-conv1", "codex", t0 + 1000))
    }

    @Test
    fun corruptFileLoadsAsEmptyTable() {
        val dir = tempDir().also { it.mkdirs() }
        File(dir, "session-exempts.json").writeText("{ not json")
        val s = store(dir)
        assertEquals(0, s.snapshot(1L).size, "读不回来 ⇒ 空表 ⇒ 照常问（fail-closed）")
        // 坏文件也绝不放行：查表必须是 MISS 而不是 HIT。
        assertEquals(SessionExemptLedger.Verdict.MISS, s.verdictOf("agy-conv1", "codex", 1L))
    }
}