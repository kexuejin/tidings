// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 卡片数据模型（13 个 data class / 枚举 + [AgentCardUi] 汇总结构）。
 *
 * 为什么单独一个文件：这些类型是**渲染引擎与解析的共同语言** —— 解析侧构造它们，
 * UI 侧只读它们。原来它们和渲染代码挤在同一个 2481 行的文件里，谁都可以顺手往
 * 里面加一个只有某一处用到的字段，而「协议里到底有哪些键」这件事反而看不出来。
 * 这里只放结构与默认值，不放任何判断。
 */

/** StepList 里一步的状态（§4.3 样例的三态 + agentState 四态里借来的失败/受阻）。 */
enum class AgentCardStepStatus { Done, Running, Pending, Failed, Blocked }

enum class AgentCardBannerTone { Info, Success, Warn, Error }

data class AgentCardStep(
    val id: String,
    val label: String,
    val status: AgentCardStepStatus,
    val assignee: String? = null,
    /** 这一步**干了什么**（棒摘要，压 200 字；null = 不显副行）。product-review P1。 */
    val detail: String? = null,
)

/** 单项验收检查（结构化验收契约）。包含单项耗时、观察结论与日志堆栈。 */
data class AgentCardCheck(
    val id: String,
    val label: String,
    val status: AgentCardStepStatus,
    val time: String? = null,
    val observation: String? = null,
    val drawer: String? = null,
    val open: Boolean = false,
)

/** 数字员工身份工牌元信息。 */
data class AgentCardBot(
    val name: String,
    val avatar: String? = null,
    val branch: String? = null,
    val bdot: String? = null,
)

/** 影响面与目标标签。 */
data class AgentCardChip(
    val text: String,
    val tone: String? = null,
)

data class AgentCardDiff(
    val path: String? = null,
    val summary: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val note: String? = null,
    val hunk: List<String> = emptyList(),
    val open: Boolean = false,
)

/**
 * 产物元数据（对齐 `agent-artifact`）：**只描述不传二进制** ——
 * 文件本体走 file-msg 那条通路，卡片里放的是「有这么个东西」。
 *
 * 自描述契约（升级计划 §7.4）：[intent] 是发出方给的**稳定键**（如 `patch-full`），
 * 人话由渲染端按键查词条 —— 键跨语言稳定，词条才可本地化；[delivered] 是发出方
 * 声明的**直投事实**（完整文件已随卡经 P2P 文件通路投出）。缺省 = 旧卡没带这些键，
 * 照旧渲染，不画意图行、不画「可预览」的承诺。
 */
data class AgentCardArtifact(
    val name: String,
    val bytes: Long? = null,
    val mime: String? = null,
    val intent: String? = null,
    val delivered: Boolean? = null,
)

/**
 * 反向多选卡的一个预设选项（detailed-design §7.9：手机端拇指一点决策，Agent 拿到
 * 确定性参数再开工）。[recommended] 是插件标注的推荐项（原型 `(推荐)` 后缀的语义化）。
 */
data class AgentCardOption(
    val label: String,
    val recommended: Boolean = false,
)

data class AgentCardBanner(
    val text: String,
    val tone: AgentCardBannerTone = AgentCardBannerTone.Info,
)

data class AgentCardAction(
    val actionId: String,
    val label: String,
    /**
     * 发出方给的**稳定文案键**（如 card.action.start_task），可选。
     *
     * 端上认识这个键就用本端词条，不认识就回退 [label] —— 这样同一个按钮在
     * 中英文两套壳上各说各的话，而旧端（只认 label）照样有字可显示。
     */
    val labelKey: String? = null,
    /** 文案键的插值参数（如倒计时秒数）；无参数时为空。 */
    val labelArgs: List<String> = emptyList(),
    /** 原样的 style 字符串（primary / secondary / danger…），插件自定义；UI 只认 approval。 */
    val style: String? = null,
    /** 高危审批按钮（§5.3）：享受乐观置灰与防重击语义。 */
    val approval: Boolean = false,
)

/**
 * 一张卡**此刻**的渲染形状。身份是二元组 `(cardId, rev)`：
 * 没有 cardId 就无法折叠，没有 rev 就没有「哪份更新」—— 两者缺一都不渲染。
 */
data class AgentCardUi(
    val cardId: String,
    val rev: Int,
    val type: String? = null,
    val title: String? = null,
    val progress: Int? = null,
    val steps: List<AgentCardStep> = emptyList(),
    val diff: AgentCardDiff? = null,
    val banner: AgentCardBanner? = null,
    val actions: List<AgentCardAction> = emptyList(),
    /** 产物元数据（只描述，不传二进制）。 */
    val artifacts: List<AgentCardArtifact> = emptyList(),
    /**
     * 审批到期时刻（毫秒，插件给的绝对时间）；null = 不会过期。
     *
     * **不占 `agentState` 四态**：过期是渲染层按 `expiresAt` 判出来的子态
     * （设计稿 v2 §6），协议面一个字没动。
     */
    val expiresAt: Long? = null,
    /** 宿主判定人（随 rev+1 的新帧到达）；null = 还没有判定。 */
    val decidedBy: String? = null,
    /** 宿主判定的 actionId；与 [decidedBy] 成对。 */
    val decidedActionId: String? = null,
    /** 反向多选预设选项（ask_question，§7.9）；空 = 这张卡没有选项块。 */
    val options: List<AgentCardOption> = emptyList(),
    /**
     * 发出方声明的任务状态（`working` / `review_ready` / `blocked` / `aborted`）。
     *
     * null = 老卡没带这个字段，此时退回「按 actions 与 progress 反推」的老办法。
     *
     * 为什么要有显式字段：反推那套里没有「已中止」的位置 —— 用户按了停止，
     * 卡片还挂着「待合并」。谁结束的、怎么结束的，
     * 该由发出方说了算，不该让渲染层猜。
     */
    val state: String? = null,
    val bot: AgentCardBot? = null,
    val chips: List<AgentCardChip> = emptyList(),
    val context: String? = null,
    val cmd: String? = null,
    val checks: List<AgentCardCheck> = emptyList(),
    val subActions: List<AgentCardAction> = emptyList(),

    /** 宿主最近一次心跳的墙钟毫秒（§六.1）。前端据此渲染「N 秒前心跳」，缺省 = 无心跳信息。 */
    val lastBeatTs: Long? = null,
)
