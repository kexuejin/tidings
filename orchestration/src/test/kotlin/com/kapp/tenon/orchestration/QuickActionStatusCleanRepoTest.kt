// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/status` 在**干净仓库**上不许退单。
 *
 * ## 真机现场（11-11，手机建群 → /status）
 *
 * 群里回的是 `no git workspace bound to this group`，而同一刻宿主
 * `host-directories.json` 里 `groupPointers` 已经指向正确目录、`branch=main` 也能读到。
 * 成因是 `gitOut` 把**空输出**当成"命令失败"：`git status --porcelain` 在干净仓库上
 * 什么都不打印，于是 `status()` 走进 `?: return null`，回执念成"没绑工作区"。
 *
 * 造一个未跟踪文件再发一次，回执立刻变成 `branch=main changed=0 untracked=1` ——
 * 同一份绑定、同一个进程，唯一变化是 porcelain 不再为空。这就是本用例钉住的那条语义。
 *
 * ## 反向探针
 *
 * 把 `parsePorcelainLines` 的过滤去掉（改成 `raw.lines()`），第 1 例仍绿但第 2 例绿得
 * 没有意义 —— 真正的反向探针是把 `status()` 里的 `runGit` 换回 `gitOut`：
 * 第 3 例（源码守卫）随之变红。
 */
class QuickActionStatusCleanRepoTest {

    @Test
    fun aCleanRepoIsAnEmptyChangeListNotAFailure() {
        assertEquals(
            emptyList(),
            parsePorcelainLines(""),
            "干净仓库的 porcelain 就是空 —— 这是一条**读数**，不是「环境不可用」",
        )
        assertEquals(
            emptyList(),
            parsePorcelainLines("\n\n"),
            "只有换行同样是没有改动：不许把它攒出一条假变更",
        )
    }

    @Test
    fun porcelainLinesKeepTheirStatusCode() {
        val raw = "?? .probe.tmp\n M src/App.kt\nA  new.txt\n"
        assertEquals(
            listOf("?? .probe.tmp", " M src/App.kt", "A  new.txt"),
            parsePorcelainLines(raw),
            "行首状态码是判据（`??` 算未跟踪、其余算已改动），不许 trim 掉",
        )
        // 与 `quickActionPort().status` 两处计数同一判据：别在这里另立一套。
        val lines = parsePorcelainLines(raw)
        assertEquals(2, lines.count { !it.startsWith("??") }, "已改动条数")
        assertEquals(1, lines.count { it.startsWith("??") }, "未跟踪条数")
    }

    @Test
    fun statusReadsPorcelainThroughTheBlankTolerantReader() {
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopExtension.kt",
            ),
        )
        val statusBody = source.substringAfter("override fun status(groupId: String?)")
            .substringBefore("override fun diff(")
        assertTrue(
            statusBody.contains("runGit(repo, \"status\", \"--porcelain\")"),
            "/status 必须走允许空输出的 runGit：换成 gitOut 就会把干净仓库念成「没绑工作区」",
        )
        assertFalse(
            statusBody.contains("gitOut(repo, \"status\""),
            "不许再对 porcelain 用 gitOut —— 它的 `ifBlank { null }` 正是那条误报的源头",
        )
        assertTrue(
            statusBody.contains("parsePorcelainLines("),
            "计数必须走同一个解析函数（两处各写一遍 filter 早晚不一致）",
        )
    }
}
