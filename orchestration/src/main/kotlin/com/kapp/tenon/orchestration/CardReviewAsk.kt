// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainReviewTarget
import com.kapp.tenon.core.protocol.TeamMemberInfo

/**
 * 卡上那颗「请 TA 复检」该说什么话（纯函数，测试直喂字符串）。
 *
 * ## 为什么要**把 diff 抄进提示词**，而不是让审查员自己去工作区看
 *
 * 单兵的产物留在沙箱里：宿主仓一行未动，等机主点「合并」才落地（这是隔离的**全部意义**）。
 * 所以此刻工作区里看到的是**改动前**的代码 —— 让审查员「审当前工作区」，它审的是旧基线，
 * 会得到一份诚实、完整、且与本次改动无关的意见（本仓最贵的那类假结论：看不出来，因为它
 * 言之有物）。
 *
 * 补丁文件本身在产物目录里，路径也在卡上，但审查棒跑在它自己的沙箱里，"绝对路径能读到
 * 外面"是靠不住的假设。抄进提示词是唯一不依赖沙箱边界的做法；代价是要给 diff 设预算
 * （[MAX_PATCH_CHARS]），超了**如实说截断**，不假装那就是全部。
 */
internal object CardReviewAsk {

    /**
     * 进提示词的 diff 上限（字符）。取 6000 的理由：单棒补丁多数在几百到两千字符，
     * 6000 覆盖绝大多数「扫一眼就能判」的改动；再往上，模型的注意力会先被 `+` 行吃掉，
     * 结论质量反而下降 —— 那时更该由人把补丁发给审查员，而不是让提示词硬塞。
     */
    const val MAX_PATCH_CHARS = 6000

    /** 提示词里的 diff 段（纯函数，测试直喂）。 */
    data class Excerpt(val text: String, val truncated: Boolean, val droppedChars: Int)

    /**
     * 裁剪补丁原文。裁在**行边界**上：半行 diff 会让审查员把一处语法错误报成真实缺陷。
     */
    fun excerpt(patch: String, budget: Int = MAX_PATCH_CHARS): Excerpt {
        val text = patch.trim()
        if (text.length <= budget) return Excerpt(text, truncated = false, droppedChars = 0)
        val cut = text.take(budget)
        val boundary = cut.lastIndexOf('\n').takeIf { it > budget / 2 } ?: cut.length
        val kept = cut.take(boundary)
        return Excerpt(kept, truncated = true, droppedChars = text.length - kept.length)
    }

    /**
     * 审查员的完整指令。
     *
     * 前半句复用 [TeamPlanCompiler.reviewInstruction] —— 那是「审查棒该说什么」的**唯一**
     * 一处定义（测试棒跑命令、其余审工作区、都要求一句显式结论）。这里只补两件事：
     * 改动还没有落进工作区、以及 diff 原文。
     *
     * @param patch 补丁原文；null/空白 = 没有可审的东西，调用方**不该**派这一单
     *              （见 [CardReviewAsk.excerpt] 的调用点），这里如实写进提示词而不编一份。
     */
    fun instruction(
        goal: String,
        roleLabel: String?,
        customPrompt: String? = null,
        patch: String? = null,
        budget: Int = MAX_PATCH_CHARS,
    ): String {
        val base = TeamPlanCompiler.reviewInstruction(goal, roleLabel, customPrompt)
        val raw = patch?.takeIf { it.isNotBlank() }
            ?: return base + "\n\nThe change under review is not available on the host" +
                " (no patch was harvested for this task): say so in one line and stop."
        val cut = excerpt(raw, budget)
        val tail = if (cut.truncated) {
            "\n[diff truncated: " + cut.droppedChars +
                " chars not shown — the full patch.diff is attached to the card]"
        } else {
            ""
        }
        return base +
            "\n\nThe change is NOT in the workspace yet (it is isolated in a sandbox patch)." +
            " Judge it from the diff below rather than from files on disk." +
            "\n```diff\n" + cut.text + "\n```" + tail
    }

    /**
     * 单兵终态卡该找谁审：编制里**第一位可调起**的审查员。
     *
     * 判据与「点将」同一顺序感（编制顺序即候选顺序，`firstOrNull`），只是方向相反：
     * 点将挑的是干活的人，这里挑的是审的人。
     *
     * 四种情况返回 null，**都不给按钮**：
     * - 编制里没有审查员（没人可问，画一颗按钮等于画一个出口）；
     * - 审查员的底座不在位（点了必然失败 —— 那是拿卡面替宿主赌一把）；
     * - 控制面读不到（`available == null`，读不到 ≠ 没有，但也不等于可用）；
     * - 席位声明里查不到这个人名（回查时会 fail-closed，卡上就不该承诺）。
     *
     * @param excludeAgentId 干活的那位：复检不派回给刚干完活的人（自己审自己）。
     */
    fun pickReviewTarget(
        profile: TeamExecutionProfile,
        declared: List<TeamMemberInfo>,
        available: Set<String>?,
        excludeAgentId: String? = null,
    ): ChainReviewTarget? {
        val inPlace = available ?: return null
        val exclude = excludeAgentId?.trim()?.lowercase()
        return profile.reviewers
            .asSequence()
            .filter { it.driverKey in inPlace }
            .filter { exclude == null || it.agentId.trim().lowercase() != exclude }
            .mapNotNull { spec ->
                // 动作串里只能是**组员名**（token 判据在 core 的 resolveTeamMemberToken：
                // 按 name 匹配，不以 agentId 为准）。查不到名字就不是这颗按钮能落的人。
                val name = declared.firstOrNull {
                    it.agentId.trim().lowercase() == spec.agentId.trim().lowercase()
                }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ChainReviewTarget(token = name, name = name)
            }
            .firstOrNull()
    }
}
