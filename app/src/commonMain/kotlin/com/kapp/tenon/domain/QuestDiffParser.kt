// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 统一 diff（unified diff）的**纯解析器**（Phase 4「移动端 Diff 审查」）：
 * `patch.diff` 文本 → 文件树概览（每文件 ±行数）+ 分文件的行序列。
 *
 * 纯函数、零 IO、零平台依赖 —— 判据全部可单测（`QuestDiffParserTest`）。
 * 解析不了的行按上下文兜底（不丢字节、不抛异常）：diff 的消费方是**人**，
 * 展示层宁可多画一行也别把补丁截没。
 */
object QuestDiffParser {

    /**
     * 单行类型（渲染层据此着色与折叠）。[COLLAPSED] 是**结构化占位**：text 只装
     * 被折叠的行数（数字串），文案归 UI 资源 —— 领域层产出用户可见文案的唯一
     * 通道是 RelayUiTexts（本类没有），硬编码中文会被 cjkLiteralGate 当场拦下。
     */
    enum class LineType { ADD, DEL, CONTEXT, HUNK, COLLAPSED }

    data class DiffLine(val type: LineType, val text: String)

    data class DiffFile(
        val path: String,
        val additions: Int,
        val deletions: Int,
        val lines: List<DiffLine>,
    )

    data class QuestDiff(val files: List<DiffFile>)

    /** 每文件渲染行数的硬上限（方案 v3 §Phase 4.2：超限提示去桌面看完整补丁）。 */
    const val MAX_LINES_PER_FILE = 5_000

    /** 解析统一 diff 文本。空文本 → 空文件树（调用方画空态，不是错误）。 */
    fun parse(text: String): QuestDiff {
        if (text.isBlank()) return QuestDiff(emptyList())
        val files = ArrayList<DiffFile>()
        var currentPath: String? = null
        var currentLines = ArrayList<DiffLine>()
        var additions = 0
        var deletions = 0

        fun flush() {
            val path = currentPath ?: return
            if (currentLines.isEmpty() && additions == 0 && deletions == 0) return
            files.add(DiffFile(path, additions, deletions, currentLines.toList()))
            currentLines = ArrayList()
            additions = 0
            deletions = 0
        }

        val allLines = text.lines()
        for (i in allLines.indices) {
            val raw = allLines[i]
            val nextIsPlusPlusPlus = i + 1 < allLines.size && allLines[i + 1].startsWith("+++ ")
            when {
                raw.startsWith("diff --git ") -> {
                    flush()
                    currentPath = gitPathOf(raw) ?: "unknown"
                }
                raw.startsWith("--- ") && nextIsPlusPlusPlus -> Unit // diff 头对的前半
                raw.startsWith("index ") || raw.startsWith("new file mode") ||
                    raw.startsWith("deleted file mode") || raw.startsWith("similarity index") ||
                    raw.startsWith("rename ") || raw.startsWith("old mode") ||
                    raw.startsWith("new mode") -> Unit // 头部元数据
                raw.startsWith("+++ ") && i > 0 && allLines[i - 1].startsWith("--- ") -> {
                    if (currentPath == null) currentPath = raw.drop(4).trim().removePrefix("b/")
                }
                raw.startsWith("@@") -> currentLines.add(DiffLine(LineType.HUNK, raw))
                raw.startsWith("+") -> {
                    additions++
                    currentLines.add(DiffLine(LineType.ADD, raw.drop(1)))
                }
                raw.startsWith("-") -> {
                    deletions++
                    currentLines.add(DiffLine(LineType.DEL, raw.drop(1)))
                }
                else -> {
                    if (currentPath != null) currentLines.add(DiffLine(LineType.CONTEXT, raw.removePrefix(" ")))
                }
            }
        }
        flush()
        return QuestDiff(files)
    }

    /** `diff --git a/x.py b/x.py` → `x.py`（rename 场景取 b 侧）。带空格路径不截断（review P2-9）。 */
    private fun gitPathOf(line: String): String? {
        val bIdx = line.indexOf(" b/")
        return if (bIdx >= 0) line.substring(bIdx + 3).takeIf { it.isNotBlank() } else null
    }

    /**
     * 渲染前的**行帽 + 上下文折叠**：
     * - 单文件超 [MAX_LINES_PER_FILE] 行 → 截尾（调用方据 [Trimmed.truncated] 出提示）；
     * - 连续 ≥ [CONTEXT_COLLAPSE_MIN] 行上下文折叠成一条占位（「未改动上下文」
     *   在手机上是噪音，方案 v3 §Phase 4.2）。
     */
    data class Trimmed(val lines: List<DiffLine>, val truncated: Boolean, val collapsedRuns: Int)

    const val CONTEXT_COLLAPSE_MIN = 4

    fun trimForRender(lines: List<DiffLine>, maxLines: Int = MAX_LINES_PER_FILE): Trimmed {
        val out = ArrayList<DiffLine>()
        var collapsedRuns = 0
        var truncated = false
        var i = 0
        while (i < lines.size) {
            if (out.size >= maxLines) {
                truncated = true
                break
            }
            val line = lines[i]
            if (line.type != LineType.CONTEXT) {
                out.add(line)
                i++
                continue
            }
            var run = 0
            while (i + run < lines.size && lines[i + run].type == LineType.CONTEXT) run++
            if (run >= CONTEXT_COLLAPSE_MIN) {
                if (out.size >= maxLines) {
                    truncated = true
                    break
                }
                out.add(DiffLine(LineType.COLLAPSED, run.toString()))
                collapsedRuns++
            } else {
                for (k in 0 until run) {
                    if (out.size >= maxLines) {
                        truncated = true
                        break
                    }
                    out.add(lines[i + k])
                }
                if (truncated) break
            }
            i += run
        }
        if (i < lines.size) truncated = true
        return Trimmed(out, truncated = truncated, collapsedRuns = collapsedRuns)
    }
}
