// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 派单 prompt 的预算裁剪（T-G6，§7.7 第一层）：上游长上下文（前序任务日志、
 * 大段引用）不能原样灌进下游 prompt —— 预算内原样，超限**头尾保留 + 如实标注**
 * （假压缩比截断更糟：模型按截断文继续推理，不如知道哪里缺了）。
 *
 * 纯函数：可单测，不碰发送路径。
 */
object TaskPromptBudget {

    fun clamp(prompt: String, budget: Int = Limits.AGENT_TASK_BUDGET): String {
        if (prompt.length <= budget) return prompt
        val headKeep = budget * 3 / 4
        val tailKeep = budget - headKeep - TRUNCATION_MARKER.length
        if (tailKeep <= 0) return prompt.take(budget)
        return prompt.take(headKeep) + TRUNCATION_MARKER + prompt.takeLast(tailKeep)
    }

    /** 截断标注：如实告诉下游「中间缺了多少字符」（§7.7 语义摘要层的底线诚实）。 */
    const val TRUNCATION_MARKER = "\n[…上游上下文已截断，中段缺失…]\n"
}
