// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.core.nowMillis
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/** 卡片 state 的取值（与宿主编排器的 state 命名同一套，前端按它渲染状态胶囊）。 */
private const val STATE_WORKING = "working"
private const val STATE_REVIEW_READY = "review_ready"
private const val STATE_BLOCKED = "blocked"
private const val STATE_ABORTED = "aborted"

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
 * - **先出受理卡，再干活**：`run` 之前发一张 `state=working` 的卡，说明「收到、开始做了」。
 *   此前只在拿到 harvest 后才出卡，于是「派单到结论」之间群里什么都没有 —— 用户发完
 *   一句话就只能盯着一片空白猜它跑没跑；
 * - 结论卡与受理卡**同 cardId**，rev 1→2 原位刷新：一次任务恒一张卡；卡是**提纯摘要**
 *   （文件数 / +A −D / repo 名），全量 patch 在产物文件里（§7.7 数据指针层）；
 * - 终局恰好一个，且必然有人给受理卡收尾：Finished（成功，出 diff 卡）/ 失败与超时
 *   （出 `state=blocked` 卡）/ 中止（出 `state=aborted` 卡）——**中止不是失败**，
 *   它报的是「你让它停，它停了」，不能混进错误路径。
 */
class SandboxedCliDriver(
    override val driverId: String,
    override val displayName: String,
    private val cliPath: String,
    private val argSpec: (prompt: String, model: String?) -> List<String>,
    private val sandbox: WorktreeSandboxExecutor,
    private val envOverrides: Map<String, String> = emptyMap(),
    /**
     * 该驱动是否用**宿主真实 HOME** 跑（不落 `.sandbox-home`）。
     *
     * agy 需要：其登录态走系统 keyring（cli.log 实证 `keyringAuth: loaded token`
     * / `ChainedAuth: authenticated via keyring`），而 keyring 的可达性对 HOME
     * 有 CLI 内部未知之门——假 HOME/符号链接/全拷贝三种形态实测全部读不到
     * （同机真 HOME 直跑 2.4s SUCCESS）。竞品同模型：Multica 明说「CLI 需已安装
     * 并登录——Multica 只驱动，不代管」，daemon 用宿主真实环境跑 CLI；凭据隔离
     * 由各家 CLI 自己的沙箱旗标承担（agy 有 `--sandbox`）。
     *
     * 隔离边界的交代：网络仍走 seatbelt 白名单（真正的边界）；文件暴露 = 与机主
     * 在终端里自己跑 agy 完全一致（agy 是机主自己的工具、用自己的凭据）。
     */
    private val useRealHome: Boolean = false,
    /**
     * 该驱动的网络策略（三档见 [OsSandboxGuard.NetworkPolicy]）：quest 内置驱动全集 = OPEN
     * （模型 API 必须出网，air-gapped 下必死）。
     *
     * **默认值读真源**：`driver-specs.json` 的 `networkPolicy` 经
     * [OsSandboxGuard.declaredPolicyOf] 转成执行面枚举。曾经这里是字面量 `OPEN`，而 JSON 里
     * 的声明零消费——把某家改成 AIR_GAPPED 只会写进生成物、不影响任何一条命令，症状是
     * 「策略改了没反应」。缺席不兜底：查不到该驱动就抛（投影漏了）。
     *
     * 为什么「默认放开」这个方向是对的：过去默认是 AIR_GAPPED，未显式传的四家
     * （claude/codex/opencode/dsh）静默跑在断网策略下——strict 沙箱里模型 API、
     * 以及 claude/opencode 要连的 127.0.0.1:8788 MCP 全被 `deny network*` 掐死，
     * 症状是「认证超时 / 工具连不上」，没人会想到是默认参数。
     *
     * 注意 OPEN 已含 loopback（`allow network*`）——不要再给任何一家设 LOOPBACK：
     * 那档是 `deny network*` 只留 localhost，会把模型 API 一起断掉。
     */
    private val networkPolicy: OsSandboxGuard.NetworkPolicy = OsSandboxGuard.declaredPolicyOf(driverId),
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** 瞬时失败判别与重试档位：与 [CliSubprocessDriver] 同一默认（provider 断流/5xx 白名单）。 */
    private val transientOf: (exitCode: Int, output: String) -> Boolean =
        CliSubprocessDriver::defaultTransientOf,
    private val maxExtraAttempts: Int = CliSubprocessDriver.DEFAULT_MAX_EXTRA_ATTEMPTS,
    private val retryBackoff: suspend (attempt: Int) -> Unit = { attempt ->
        kotlinx.coroutines.delay(
            if (attempt <= 1) CliSubprocessDriver.DEFAULT_BACKOFF_FIRST_MS
            else CliSubprocessDriver.DEFAULT_BACKOFF_NEXT_MS,
        )
    },
    /** 断点续跑形态（dsh 有）：重试跳以续跑参数重放，从上一跳断点继续。 */
    private val resumeSpec: CliResumeSpec? = null,
    /** 本次任务的模型（[withModel] 写入）；沙箱与裸路径必须用同一个值，否则「切了沙箱就变默认模型」。 */
    private val model: String? = null,
    /** 判「支持不支持按次选模型」的键；裸路径委托实例 id 带 `-plain`，不能用它去查词表。 */
    private val modelKey: String = driverId,
) : AgentDriver {

    /**
     * 兼容构造：只收 prompt，模型按**末尾追加**拼。
     *
     * **生产装配一律用主构造**（显式给出旗标位置），本构造现在只服务测试探针。
     * 原因是它把「模型插在哪」交给追加行为，而各家 CLI 的位置约束不同：
     * codex / opencode 的 prompt 是**末尾位置参数**，追加在它后面的 `--model x` 会被
     * 当成 prompt 的一部分吞掉 —— CLI 退出码 0、事件流正常、全链路不报错。
     * 这类缺陷只能靠 [ProductionDriverArgvGuardTest] 这种「测装配」的守卫拦。
     */
    constructor(
        driverId: String,
        displayName: String,
        cliPath: String,
        argSpec: (prompt: String) -> List<String>,
        sandbox: WorktreeSandboxExecutor,
        envOverrides: Map<String, String> = emptyMap(),
        newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
        transientOf: (exitCode: Int, output: String) -> Boolean =
            CliSubprocessDriver::defaultTransientOf,
        maxExtraAttempts: Int = CliSubprocessDriver.DEFAULT_MAX_EXTRA_ATTEMPTS,
        retryBackoff: suspend (attempt: Int) -> Unit = { attempt ->
            kotlinx.coroutines.delay(
                if (attempt <= 1) CliSubprocessDriver.DEFAULT_BACKOFF_FIRST_MS
                else CliSubprocessDriver.DEFAULT_BACKOFF_NEXT_MS,
            )
        },
        resumeSpec: CliResumeSpec? = null,
        model: String? = null,
        modelKey: String = driverId,
    ) : this(
        driverId = driverId,
        displayName = displayName,
        cliPath = cliPath,
        argSpec = { prompt, m -> argSpec(prompt) + DriverModelFlags.flagsFor(driverId, m) },
        sandbox = sandbox,
        envOverrides = envOverrides,
        newSessionId = newSessionId,
        transientOf = transientOf,
        maxExtraAttempts = maxExtraAttempts,
        retryBackoff = retryBackoff,
        resumeSpec = resumeSpec,
        model = model,
        modelKey = modelKey,
    )

    /** 裸路径的委托对象（同一 CLI 身份、无沙箱）。 */
    private val plain = CliSubprocessDriver(
        driverId = "$driverId-plain",
        displayName = displayName,
        cliPath = cliPath,
        argSpec = argSpec,
        envOverrides = envOverrides,
        newSessionId = newSessionId,
        transientOf = transientOf,
        maxExtraAttempts = maxExtraAttempts,
        retryBackoff = retryBackoff,
        resumeSpec = resumeSpec,
        model = model,
        modelKey = modelKey,
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

    /**
     * 把本驱动名下所有在跑的沙箱任务摘掉，返回个数。
     *
     * 「停」的**确定性落点**：编排器那条协程取消对慢启动/阻塞读的 CLI 不一定及时落地
     * （真机实测：卡片已 `Aborted`，进程 7 分钟后仍在），而这里直接对着 `running`
     * 里的 Process 动手。两条一起用，取消是常态、这条是兜底。
     */
    suspend fun abortAll(): Int = sandbox.abortAll()

    /** 当前在跑的任务数（入站「停」要不要被消费，看它）。 */
    fun runningCount(): Int = sandbox.runningCount()

    /**
     * 换模型 = 换一个同驱动实例（沙箱执行器、重试档位、env 注入全部沿用）。
     *
     * **必须在 openSession 之前调用**：会话表是实例状态，换实例不会带走已开的会话 ——
     * 派单链路的顺序是「解析驱动 → withModel → openSession → executeTask」，别反过来。
     */
    override fun withModel(model: String?): AgentDriver = SandboxedCliDriver(
        driverId = driverId,
        displayName = displayName,
        cliPath = cliPath,
        argSpec = argSpec,
        sandbox = sandbox,
        envOverrides = envOverrides,
        useRealHome = useRealHome,
        networkPolicy = networkPolicy,
        newSessionId = newSessionId,
        transientOf = transientOf,
        maxExtraAttempts = maxExtraAttempts,
        retryBackoff = retryBackoff,
        resumeSpec = resumeSpec,
        model = model,
        modelKey = modelKey,
    )

    /**
     * 本次尝试的完整 argv —— 与裸驱动同一口径，生产与守卫共用这一个出口。
     *
     * 沙箱分支过去用单参 `argSpec` + 「末尾追加模型」的兼容构造，于是「模型插在哪」由
     * 追加行为决定，而不是由该 CLI 的参数形态决定 —— codex/opencode 的 trailing 位置参数
     * 就是这么被吃掉的。现在两条分支都从 [argSpec] 的双参形态取，位置显式。
     */
    internal fun attemptArgv(prompt: String, resumedCliSessionId: String?): List<String> =
        if (resumedCliSessionId != null && resumeSpec != null) {
            resumeSpec.args(resumedCliSessionId, prompt, model)
        } else {
            argSpec(prompt, model)
        }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) = executeTask(sessionId, prompt, onEvent, onRawLine = null)

    /**
     * 带原始行出口的执行（quest 日志泵专用，Phase 1）：[onRawLine] 在事件映射**之前**
     * 收到每一行原始 stdout/stderr（stderr 已并入；`CliLineEventMapper` 认不出的行
     * 也在内 —— 日志流要的正是全量，不是事件映射后的子集）。
     *
     * 只有沙箱分支透传：空工作区委托裸路径（[plain]），那条路没有 worktree 也就没有
     * quest —— 派单链路保证 quest 的 wsPath 永远非空，所以这里不为裸路径开第二份出口。
     */
    suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
        onRawLine: (suspend (String) -> Unit)?,
    ) {
        val wsPath = sessions[sessionId]
        if (wsPath == null) {
            onEvent(DriverEvent.Error("unknown session \"$sessionId\" — call openSession first"))
            return
        }
        // 在**分流之前**判模型：空工作区会委托给裸路径，两边各判一次不如在这里判一次
        // ——「沙箱分支不支持、裸分支静默忽略」是本仓最不该有的那种分叉。
        DriverModelFlags.unsupportedReason(modelKey, model)?.let { reason ->
            onEvent(DriverEvent.Error(reason))
            return
        }
        if (wsPath.isBlank()) {
            plain.executeTask(sessionId, prompt, onEvent)
            return
        }
        val taskId = "t-" + taskSeq.incrementAndGet() + "-" + java.util.UUID.randomUUID().toString().take(8)
        // **先出受理卡，再干活。**
        //
        // 此前这里只在拿到 harvest 之后发一张 diff-review 卡，于是「派单到结论之间」
        // 群里什么都没有：派了活，屏幕上连一张本轮任务卡都找不到，用户只能
        // 盯着一条自己发的消息猜它在不在跑。DSH 的第一条要求就是「确认一次请求并说明
        // 它会做什么」，所以这张卡必须在 run 之前发，且与终态卡**同 cardId**（原位刷新
        // ⇒ 一次任务恒一张卡，rev 从 1 走到 2）。
        onEvent(DriverEvent.Card(runningCardJson(taskId, wsPath)))
        var resumedCliSessionId: String? = null
        val totalAttempts = 1 + maxExtraAttempts.coerceAtLeast(0)
        for (attempt in 1..totalAttempts) {
            // 近邻判别的输出累积：瞬时判别与续跑 id 都从这里取。总量有上限（BoundedOutputTail），
            // 沙箱是生产路径，长任务几百万行输出不能把宿主堆吃光。
            val outputTail = BoundedOutputTail()
            val attemptArgs = attemptArgv(prompt, if (attempt > 1) resumedCliSessionId else null)
            val env = envOverrides +
                mapOf(DriverKeys.ENV_HOOK_SESSION_ID to sessionId) +
                if (useRealHome) {
                    // 显式 HOME ⇒ WorktreeSandbox 的 `.sandbox-home` 覆盖被跳过（它只
                    // 在 env["HOME"] 缺席时生效）。
                    mapOf("HOME" to (System.getProperty("user.home") ?: ""))
                } else {
                    emptyMap()
                }
            val outcome = sandbox.run(
                taskId = taskId,
                hostRepo = wsPath,
                command = listOf(cliPath) + attemptArgs,
                env = env,
                networkPolicy = networkPolicy,
                onLine = { line ->
                    outputTail.appendLine(line)
                    if (resumedCliSessionId == null) {
                        resumeSpec?.sessionIdFromLine?.invoke(line)?.let { resumedCliSessionId = it }
                    }
                    // 原始行先于事件映射出去（日志泵的全量出口）；映射器认不出的行
                    // 只有这一条路能活。
                    onRawLine?.invoke(line)
                    CliLineEventMapper.map(line)?.let { onEvent(it) }
                },
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
                    return
                }
                is WorktreeSandboxExecutor.Outcome.Failed -> {
                    // 沙箱失败不带退出码，按「跑过但非零」判（spawn 失败才有代位 <0 形态）。
                    if (!transientOf(1, outputTail.toString()) || attempt == totalAttempts) {
                        // 失败也必须把受理卡收尾。否则那张「正在处理」会一直挂着 ——
                        // 一个永远转圈的进行中，比没有卡更糟：它让人等一个不会来的结果。
                        val ledger = if (attempt > 1) {
                            outcome.message + " — gave up after " + attempt +
                                " attempts (transient retry exhausted)"
                        } else {
                            outcome.message
                        }
                        onEvent(DriverEvent.Card(endCardJson(taskId, wsPath, STATE_BLOCKED, ledger)))
                        onEvent(DriverEvent.Error(ledger + CliSubprocessDriver.stderrTailOf(outputTail.toString())))
                        return
                    }
                }
                is WorktreeSandboxExecutor.Outcome.Aborted -> {
                    // **中止不是失败**。用户按了停止，事实是「他让它停，它停了」——
                    // 报成 Error 会让手机端弹一句「没有完成这次任务」，把人做的决定
                    // 说成机器的故障。
                    onEvent(
                        DriverEvent.Card(
                            endCardJson(taskId, wsPath, STATE_ABORTED, "已停止，这次改动没有保留"),
                        ),
                    )
                    onEvent(DriverEvent.Error("task aborted by user request"))
                    return
                }
                is WorktreeSandboxExecutor.Outcome.TimedOut -> {
                    // 超时不是瞬时：任务真的跑不动预算，重试只会再烧一整份时长 ——
                    // 如实终局，讲清发生了什么（跑了多久、已收场），不给「还在跑」的错觉。
                    onEvent(
                        DriverEvent.Card(
                            endCardJson(
                                taskId,
                                wsPath,
                                STATE_BLOCKED,
                                "执行超过 ${outcome.timeoutMs} ms，已停止；工作树已清理。",
                            ),
                        ),
                    )
                    onEvent(
                        DriverEvent.Error(
                            "task timed out in sandbox after ${outcome.timeoutMs}ms — process tree killed, " +
                                "worktree cleaned (raise TENON_SANDBOX_TIMEOUT_MS for genuinely long tasks)",
                        ),
                    )
                    return
                }
            }
            retryBackoff(attempt)
        }
    }

    /** 卡片动作与裸驱动同一条纪律：CLI 从不发卡片，误达的动作说明路由出了错。 */
    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw IllegalStateException("cli driver \"$driverId\" does not support card actions")
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? = null

    /**
     * 受理卡：只说明「收到、开始干了」，不带任何结果字段。
     *
     * 必须与 [diffCardJson] **同 cardId**、同 type：前端按 cardId 原位刷新，rev 1→2
     * 就是一张卡从「进行中」翻到「待合并」。type 仍写 `diff-review` 而不是另造一个
     * —— 一次任务从头到尾是同一类东西，中途换 type 会让卡片的类型徽标跳变。
     *
     * `progress` 是前端判「进行中」的依据（`CardStateTag` 在没有 merge 动作、
     * progress 非空且不是 100 时显示进行中），所以这里给一个小值而不是 0。
     */
    private fun runningCardJson(taskId: String, hostRepo: String): String = buildJsonObject {
        put("cardId", JsonPrimitive("diff-$taskId"))
        put("rev", JsonPrimitive(1))
        put("type", JsonPrimitive("diff-review"))
        put("state", JsonPrimitive(STATE_WORKING))
        put("title", JsonPrimitive("${File(hostRepo).name} 正在处理"))
        put("progress", JsonPrimitive(5))
        // 进行中的任务必须给一个「停」的出口。此前唯一的办法是在群里打 stop 之类的
        // 关键词，打错就静默无效；卡上一个按钮都不给，用户只能干等。
        put("actions", buildJsonArray {
            add(buildJsonObject {
                put("actionId", JsonPrimitive("abort-task"))
                put("label", JsonPrimitive("abort-task"))
            })
        })
        putJsonObject("banner") {
            put("text", JsonPrimitive("正在处理，完成后结果会回到这里"))
            put("tone", JsonPrimitive("info"))
        }
    }.toString()

    /**
     * 收尾卡（失败/中止/超时）：把受理卡翻到一个**终态**，不留悬空的进行中。
     *
     * `state` 是本轮新增的显式字段。此前卡片状态全靠前端从 `actions`（有 merge
     * 就是待合并）和 `progress`（有值且不到 100 就是进行中）反推 —— 那套推导里
     * 根本没有「已中止」的位置，于是用户按了停止、卡片还写着「待合并」。显式字段
     * 让「谁结束了、怎么结束的」由发出方说了算。
     */
    private fun endCardJson(
        taskId: String,
        hostRepo: String,
        state: String,
        banner: String,
    ): String = buildJsonObject {
        put("cardId", JsonPrimitive("diff-$taskId"))
        put("rev", JsonPrimitive(2))
        put("type", JsonPrimitive("diff-review"))
        put("state", JsonPrimitive(state))
        put(
            "title",
            JsonPrimitive("${File(hostRepo).name} " + if (state == STATE_ABORTED) "已停止" else "没有完成"),
        )
        putJsonObject("banner") {
            put("text", JsonPrimitive(banner))
            // 中止用中性色：那是用户自己按下的停止，不是机器出错。红色 ✕ 会让人以为
            // 出了问题、下次不敢再停。只有真的没跑成才用 error。
            put("tone", JsonPrimitive(if (state == STATE_ABORTED) "info" else "error"))
        }
    }.toString()

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
        put("rev", JsonPrimitive(2))
        put("type", JsonPrimitive("diff-review"))
        put("state", JsonPrimitive(STATE_REVIEW_READY))
        put("title", JsonPrimitive("${File(hostRepo).name} 改好了，等你合并"))
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
            // summary **只放文件数**：`+N −N` 由渲染侧按 additions/deletions 单独上色，
            // 这里再写一遍就会显示成「15 个文件 +1817 −449 +1817 −449」
            // （真机截图上亲眼见过）。
            put("summary", JsonPrimitive("${harvest.filesChanged} 个文件"))
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
