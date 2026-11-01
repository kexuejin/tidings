// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.core.nowMillis
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 沙箱化 CLI 驱动（升级轮 T-3b，D-024）：[CliSubprocessDriver] 的 Git Worktree 沙箱形态。
 *
 * ## 路由规则（三条，各有理由）
 *
 * 1. **工作区非空** → 走沙箱：任务在宿主仓的独立 worktree 里跑（[WorktreeSandboxExecutor]），
 *    结束后提取 `git diff` 提纯成产物（patch.diff + report.json）+ 一张 diff-review 卡。
 *    群里派的活不再直接落在宿主工作树上 —— 一次未经审阅的重构就是一次未提交的污染。
 * 2. **工作区为空** → 走裸路径（[plain]）：没有仓库就没有需要隔离的面，沙箱无从建起，
 *    委托给既有裸驱动（行为与 T-3a 完全一致）。
 * 3. **工作区非空但不是 git 仓** → **大声失败**（Error 事件）：静默回退到裸跑等于
 *    「沙箱开关失效但不告诉你」，D-024 的隔离语义不能有静默降级档。
 *
 * ## 事件纪律（与裸驱动同一条诚信线）
 *
 * - 行事件复用 [CliLineEventMapper]（两处一份，不漂）；
 * - 有 diff 才出卡（`harvest != null`）—— 不为没发生的活出卡；卡是**提纯摘要**
 *   （文件数 / +A −D / repo 名），全量 patch 在产物文件里（§7.7 数据指针层）；
 * - 终局恰好一个：Finished（成功）/ Error（失败或中止）—— 中止不发 Finished
 *   （租约超时的回执由编排器答 timeout，这里只对台账说真话）。
 */
class SandboxedCliDriver(
    override val driverId: String,
    override val displayName: String,
    private val cliPath: String,
    private val argSpec: (prompt: String) -> List<String>,
    private val sandbox: WorktreeSandboxExecutor,
    private val envOverrides: Map<String, String> = emptyMap(),
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
) : AgentDriver {

    /** 裸路径的委托对象（同一 CLI 身份、无沙箱）。 */
    private val plain = CliSubprocessDriver(
        driverId = "$driverId-plain",
        displayName = displayName,
        cliPath = cliPath,
        argSpec = argSpec,
        envOverrides = envOverrides,
        newSessionId = newSessionId,
    )

    /** sessionId → wsPath（会话内的每次任务都从这同一个宿主仓开沙箱）。 */
    private val sessions = ConcurrentHashMap<String, String>()

    /** 会话内的任务序号（taskId 的可读后缀）。 */
    private val taskSeq = AtomicLong(0)

    override suspend fun listCapabilities(): List<DriverCapability> = listOf(
        DriverCapability(
            id = "cli-task",
            name = "CLI delegation (sandboxed)",
            description = "Run one task in an isolated git worktree and deliver its diff",
            category = "model",
        ),
    )

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        val id = resumeSessionId ?: newSessionId()
        sessions[id] = wsPath
        // 裸会话同步开：空工作区的任务走 plain 的 executeTask（它按自己的会话表寻址）。
        if (wsPath.isBlank()) plain.openSession(wsPath, id)
        return SessionHandle(
            sessionId = id,
            wsPath = wsPath.ifBlank { null },
            resumed = resumeSessionId != null,
            createdAt = nowMillis(),
        )
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        val wsPath = sessions[sessionId]
        if (wsPath == null) {
            onEvent(DriverEvent.Error("unknown session \"$sessionId\" — call openSession first"))
            return
        }
        if (wsPath.isBlank()) {
            plain.executeTask(sessionId, prompt, onEvent)
            return
        }
        val taskId = "t-" + taskSeq.incrementAndGet() + "-" + java.util.UUID.randomUUID().toString().take(8)
        val outcome = sandbox.run(
            taskId = taskId,
            hostRepo = wsPath,
            command = listOf(cliPath) + argSpec(prompt),
            env = envOverrides,
            onLine = { line -> CliLineEventMapper.map(line)?.let { onEvent(it) } },
        )
        when (outcome) {
            is WorktreeSandboxExecutor.Outcome.Finished -> {
                val harvest = outcome.harvest
                if (harvest != null) {
                    // 产物事件先于卡片：桥收到后把文件投进群，卡片紧随其后说明这是什么。
                    // 只发 patch.diff —— report.json 本身就是摘要（随卡与台账走），
                    // 而 Harvest 里没有它的 SHA（硬凑一个 log 的哈希是假元数据）。
                    onEvent(
                        DriverEvent.Artifact(
                            path = harvest.patchPath.absolutePath,
                            name = "patch.diff",
                            bytes = harvest.patchPath.length(),
                            mime = "text/x-diff",
                            sha256 = harvest.patchSha256,
                        ),
                    )
                    onEvent(DriverEvent.Card(diffCardJson(taskId, wsPath, harvest)))
                }
                onEvent(DriverEvent.Finished())
            }
            is WorktreeSandboxExecutor.Outcome.Failed ->
                onEvent(DriverEvent.Error(outcome.message))
            is WorktreeSandboxExecutor.Outcome.Aborted ->
                onEvent(DriverEvent.Error("task aborted in sandbox (SIGTERM/SIGKILL)"))
            is WorktreeSandboxExecutor.Outcome.TimedOut ->
                // 超时对用户就是一条失败：讲清发生了什么（跑了多久、已收场），
                // 不给「还在跑」的错觉 —— 那条工作树此刻已经清理干净了。
                onEvent(
                    DriverEvent.Error(
                        "task timed out in sandbox after ${outcome.timeoutMs}ms — process tree killed, " +
                            "worktree cleaned (raise TIDINGS_SANDBOX_TIMEOUT_MS for genuinely long tasks)",
                    ),
                )
        }
    }

    /** 卡片动作与裸驱动同一条纪律：CLI 从不发卡片，误达的动作说明路由出了错。 */
    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw IllegalStateException("cli driver \"$driverId\" does not support card actions")
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? = null

    /**
     * diff-review 卡：**提纯摘要**，全量 patch 留在产物文件里（§7.7）。cardId 带
     * taskId —— 同一会话多次任务各占一张卡（原位刷新是同一任务的 rev 推进，不是
     * 不同任务）；预算：卡只有统计字段，远小于 CARD_BYTES（发送侧宿主把关的那道
     * 闸门永远不该被这条路径触发）。
     */
    private fun diffCardJson(
        taskId: String,
        hostRepo: String,
        harvest: WorktreeSandboxExecutor.Harvest,
    ): String = buildJsonObject {
        put("cardId", JsonPrimitive("diff-$taskId"))
        put("rev", JsonPrimitive(1))
        put("type", JsonPrimitive("diff-review"))
        put("title", JsonPrimitive("${File(hostRepo).name} 沙箱任务完成"))
        // 合流动作（T-G1）：宿主机主在卡上点 [合并此补丁] → 宿主 git apply。
        // label 放 actionId 占位 —— 手机端按已知系统 actionId 本地化（数据不背文案）。
        put("actions", buildJsonArray {
            add(buildJsonObject {
                put("actionId", JsonPrimitive("merge-patch"))
                put("label", JsonPrimitive("merge-patch"))
            })
        })
        put("diff", buildJsonObject {
            put("path", JsonPrimitive(File(hostRepo).name))
            put("summary", JsonPrimitive("${harvest.filesChanged} 个文件 +${harvest.additions} −${harvest.deletions}"))
            put("additions", JsonPrimitive(harvest.additions))
            put("deletions", JsonPrimitive(harvest.deletions))
        })
        // 产物条目（展示元数据：name/bytes/mime —— 路径与 SHA 不进协议，路径只走
        // 进程内的 Artifact 事件；SHA256 的对账在 report.json 里）。
        put("artifacts", buildJsonArray {
            add(buildJsonObject {
                put("name", JsonPrimitive("patch.diff"))
                put("bytes", JsonPrimitive(harvest.patchPath.length()))
                put("mime", JsonPrimitive("text/x-diff"))
            })
        })
    }.toString()
}
