// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * §9 移动端快捷入口的领域层（编排规范 §9.2）：指令注册表 + 执行端口。
 *
 * 纯 Kotlin、零 Compose、零进程/文件系统访问 —— `:cli` 直接复用做无 UI 验收，
 * Compose 侧的芯片只负责把指令文本送进既有的消息管线（没有第二条派单通路）。
 *
 * 词源边界：`/stop` 的**同义词**（stop/停/取消…）只有一份，住在编排层的
 * `AbortSignals` —— 这里不抄一份；本表只是「斜杠命令」的注册表，`/stop`
 * 到了宿主之后由既有打断桥消费（同一执行路径、同一回执）。
 */
enum class QuickAction { STATUS, DIFF, TEST, STOP }

/**
 * 全词精确匹配；未知返回 `null` —— **调用方不得消费消息**，且要给可见回执
 * （产品评审记录：「拼错 = 静默无效，用户以为停了」）。
 */
fun parseQuickAction(raw: String): QuickAction? = when (raw.trim().lowercase()) {
    "/status" -> QuickAction.STATUS
    "/diff" -> QuickAction.DIFF
    "/test" -> QuickAction.TEST
    "/stop" -> QuickAction.STOP
    else -> null
}

/**
 * 快捷指令的执行端口：`:app` 只见接口（`:domainComposeFreeGate`），实现落
 * `:orchestration` / `:desktopApp`。身份取 `groupId` —— 与编排器同一取值域
 * （§16.1：派单面手里没有 wsId）；工作区绑定在实现里解析。
 */
interface QuickActionPort {
    /** 群绑定工作树的状态。null = 群没绑仓 / 环境不可用 —— 调用方如实回执，不编造读数。 */
    fun status(groupId: String?): WorkspaceStatus?

    /** 变更统计（只统计，不带 diff 全文 —— 隐私收口：本机绝对路径不进协议）。 */
    fun diff(groupId: String?): DiffSummary?

    /** 跑单元测试：复用编排器既有工单管线（不新建任务流），返回工单引用。 */
    fun runTests(groupId: String?): TicketRef?

    /** 紧急刹车：与既有文本 stop **同一执行路径**；回执可证伪（pid 集合）。 */
    fun stop(groupId: String?, reason: String): StopAck
}

/** §9.2 字段裁决（§15 第 4 条）：刻意**不含** lastCommit —— 合流只 apply 不提交，commit 是误导读数。 */
data class WorkspaceStatus(
    val branch: String?,
    val changedFiles: Int,
    val untrackedFiles: Int,
    val activeTicket: String?,
)

/** 只统计：文件数 / 增删行（`git diff --shortstat` 口径；未跟踪文件不在 diff 里，如实不含）。 */
data class DiffSummary(
    val files: Int,
    val added: Int,
    val removed: Int,
)

/** 一次派单的引用（工单 id；卡片与进度走既有群广播）。取不到 id（环境不可用）⇒ null。 */
data class TicketRef(val ticketId: String)

/**
 * 刹车回执，**可证伪**：摘掉了几个沙箱、还剩几个在跑（摘完再数一遍，不是愿望）。
 * pid 级的「先记集合再逐个核对」纪律由 `WorktreeSandbox.destroyProcessTree` 在
 * 内部执行（它是既有守卫面）；P1 的用户可见面是这两个计数 ——
 * 「`ProcessHandle.descendants()` 是快照，根一退出后代过继给 init 枚举不到」，
 * 所以 P1 不把 pid 集合许诺成回执。
 */
data class StopAck(
    val abortedSandboxes: Int,
    val stillRunningSandboxes: Int,
)

/**
 * 判定一条群文本是否为宿主发出的快捷指令机器回执（方案 A：纯客户端特征识别）。
 *
 * 命中时聊天流将其提升为居中系统行（.notice），避免渲染成普通文本气泡。
 * 格式与 DesktopExtension 快捷指令桥及模板配置广播一致：
 * 1. status 回执：`branch=... changed=...` 或 `no git workspace bound to this group`
 * 2. diff 回执：`files=...`
 * 3. test 回执：`ticket ... (card follows in this group)` 或 `no worker available on this host`
 * 4. 团队配置回执：`team config saved for this group: ...`
 */
fun isQuickActionReceipt(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return (trimmed.startsWith("branch=") && trimmed.contains("changed=")) ||
        (trimmed.startsWith("files=") && (trimmed.contains(" +") || trimmed.contains(" -") || trimmed.contains("+0 -0"))) ||
        (trimmed.startsWith("ticket ") && trimmed.contains("(card follows in this group)")) ||
        trimmed == "no git workspace bound to this group" ||
        trimmed == "no worker available on this host" ||
        trimmed.startsWith("team config saved for this group: ")
}

