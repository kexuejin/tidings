// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 统一 diff 解析器（Phase 4）的纯函数钉子：文件树概览的 ±计数、分文件行序列、
 * 渲染前裁剪（行帽 + 上下文折叠）。
 */
class QuestDiffParserTest {

    private val sample = """
        diff --git a/app/main.py b/app/main.py
        index 111..222 100644
        --- a/app/main.py
        +++ b/app/main.py
        @@ -1,5 +1,6 @@
         def run():
        -    print("old")
        +    print("new")
        +    return 0
             pass
        diff --git a/lib/util.py b/lib/util.py
        index 333..444 100644
        --- a/lib/util.py
        +++ b/lib/util.py
        @@ -10,3 +10,4 @@
         def helper():
        -    return None
        +    return {}
    """.trimIndent()

    @Test
    fun parsesFileTreeWithAdditionAndDeletionCounts() {
        val diff = QuestDiffParser.parse(sample)
        assertEquals(2, diff.files.size, "两个文件各一段")
        assertEquals("app/main.py", diff.files[0].path, "取 b/ 侧路径")
        assertEquals(2, diff.files[0].additions)
        assertEquals(1, diff.files[0].deletions)
        assertEquals("lib/util.py", diff.files[1].path)
        assertEquals(1, diff.files[1].additions)
        assertEquals(1, diff.files[1].deletions)
    }

    @Test
    fun parsesLineTypesPerFile() {
        val diff = QuestDiffParser.parse(sample)
        val types = diff.files[0].lines.map { it.type }
        assertEquals(
            listOf(
                QuestDiffParser.LineType.HUNK,
                QuestDiffParser.LineType.CONTEXT,
                QuestDiffParser.LineType.DEL,
                QuestDiffParser.LineType.ADD,
                QuestDiffParser.LineType.ADD,
                QuestDiffParser.LineType.CONTEXT,
            ),
            types,
        )
        assertEquals("    print(\"new\")", diff.files[0].lines[3].text, "± 前缀剥掉（缩进是内容的一部分）")
    }

    @Test
    fun blankTextYieldsEmptyTreeNotAnError() {
        assertTrue(QuestDiffParser.parse("").files.isEmpty())
        assertTrue(QuestDiffParser.parse("   \n  ").files.isEmpty())
    }

    @Test
    fun trimsOverTheCapAndReportsTruncation() {
        // 行帽用 ADD 行测（CONTEXT 会被先折叠，测不到帽）。
        val lines = List(6_000) { QuestDiffParser.DiffLine(QuestDiffParser.LineType.ADD, "add-$it") }
        val trimmed = QuestDiffParser.trimForRender(lines)
        assertEquals(QuestDiffParser.MAX_LINES_PER_FILE, trimmed.lines.size)
        assertTrue(trimmed.truncated, "超帽必须如实上报，不假装审完了")
    }

    @Test
    fun collapsesLongContextRunsIntoPlaceholders() {
        val lines = buildList {
            add(QuestDiffParser.DiffLine(QuestDiffParser.LineType.HUNK, "@@ -1,9 +1,2 @@"))
            add(QuestDiffParser.DiffLine(QuestDiffParser.LineType.ADD, "new line"))
            repeat(6) { add(QuestDiffParser.DiffLine(QuestDiffParser.LineType.CONTEXT, "ctx-$it")) }
            add(QuestDiffParser.DiffLine(QuestDiffParser.LineType.DEL, "old line"))
        }
        val trimmed = QuestDiffParser.trimForRender(lines)
        assertEquals(4, trimmed.lines.size, "6 行上下文折叠成 1 条占位")
        assertEquals(1, trimmed.collapsedRuns)
        assertEquals(QuestDiffParser.LineType.COLLAPSED, trimmed.lines[2].type, "占位是结构化行型（文案归 UI 资源）")
        assertEquals("6", trimmed.lines[2].text, "text 只装被折叠行数")
        assertFalse(trimmed.truncated)
        // 短上下文（< 4 行）不折叠：保留原行。
        val short = List(3) { QuestDiffParser.DiffLine(QuestDiffParser.LineType.CONTEXT, "c-$it") }
        assertEquals(3, QuestDiffParser.trimForRender(short).lines.size)
    }

    private fun assertFalse(b: Boolean) = kotlin.test.assertFalse(b)
}
