// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 单向责任链的执行体 —— `docs/DYNAMIC-MULTI-AGENT-TEAM-ORCHESTRATION.md` §2/§3.3 的落地件。
 *
 * ## 它补的是什么
 *
 * 派单本身早就通了（群席位 → 沙箱 → diff 卡 → 回群 → 手机合并），缺的是**棒与棒之间**：
 * 一台宿主连跑 N 棒，第 N+1 棒的起点必须是第 N 棒的**提交**，而不是把上一棒的日志灌进
 * prompt 里假装交接。真机跑过一次手工版（三棒 6m10s/11m24s/18m39s），每一步都是操作方
 * 手动合入 —— 这一层就是把那些手工动作沉成代码。
 *
 * ## 三条规矩（都是踩过坑换来的）
 *
 * 1. **棒间只传摘要 + 指针**（§7.7）：下一棒拿到的是「基线提交 + ≤200 字摘要 + 产物路径」。
 *    原始 stdout 动辄几十 KB，塞进 prompt 既超预算又会把上一棒的幻觉一起传下去。
 * 2. **交接靠提交，不靠上下文**：每棒收割成 patch 后由链在宿主仓 `git apply` + `commit`，
 *    下一棒的沙箱从那个提交拉 worktree —— 这就是「产物真的传下去了」的可核对证据。
 * 3. **失败即停机**：某棒 `ok=false` 就出 blocked 卡并停，绝不「继续跑下一棒」把空改动
 *    一路传到底（那正是「绿了但什么都没做」的来源）。
 *
 * ## 与文档的差异（落地取舍）
 *
 * 文档 §3.2 的动态点将（PeerTagRouter 撮合跨机节点）与 §3.4 的四级降级**不在这一层**：
 * 这一层只管本机单仓的顺序链。跨机委派走既有的 `agent-channel`（channelDispatch 桥），
 * 二者不是一件事，混在一起写只会两边都写不清。
 */
data class ChainStage(
    /** 展示用角色名（「原型」「开发」「验收」）。不参与路由。 */
    val role: String,
    /** 驱动键：`ControlPlane.resolveCliDriver` 的入参（`agy` / `codex` / `claude`）。 */
    val agentKey: String,
    /** 本棒要干的活。业务指令，不含交接仪式（交接由 [StageChain.promptOf] 统一拼）。 */
    val instruction: String,
    /**
     * Supervisor 拆解标记：这棒要求独立 worktree 沙箱、可与相邻的隔离棒并行开工。
     *
     * 它是**调度属性**，不是传输协议字段 —— 不碰帧白名单；进卡 JSON 时走宽松读取
     * （[StageChain.requiresIsolationOf]），老卡没有这个键就是 false，零影响。
     * 连续多根 true 的棒由 [StageChainRunner] 并行分发，非隔离棒维持顺序依赖链。
     */
    val requiresIsolation: Boolean = false,
)

/** 一棒的起点事实：上一棒停在哪、它说了什么、产物指针在哪。 */
data class StageSeed(
    val baseSha: String,
    val repoPath: String,
    val previousSummary: String? = null,
    val previousArtifacts: List<String> = emptyList(),
)

/** 一棒的结论。[ok] = 这棒真的做成了（驱动诚信：不为没发生的活报 done）。 */
data class StageRun(
    val ok: Boolean,
    val sessionId: String? = null,
    val patchPath: String? = null,
    val summary: String? = null,
    val error: String? = null,
    /** 本棒落地后的提交；null = 这棒没有产生改动或没落成。 */
    val sha: String? = null,
    /** 审查棒的显式结论；null = 这棒不是审查棒，或驱动没给出可判的结论（fail-closed）。 */
    val verdict: ReviewVerdict? = null,
    /**
     * 本棒墙钟耗时（毫秒）。checks[] 的 `time` 字段从这来 —— 原型稿的验收清单每项
     * 带「0.6s」式的耗时标签，是机器事实不是装饰；null = 调用方没量（测试桩）。
     */
    val elapsedMs: Long? = null,
)

/**
 * 补丁与改动摘要（展示给机主审查与决定是否合流）。
 *
 * [hunk] 是**真预览**（原型稿 approval 卡的 `.df` 块）：收割到的 patch.diff 的首个
 * hunk 原文行（`@@` 头 + 上下文/+/− 行），客户端 [parseAgentCard] 的 `diff.hunk`
 * 契约逐字同形。没有它，合流卡就只是「标题 + 进度 + 按钮」——机主看不到自己
 * 在批准什么（用户验收意见：文档预览缺位）。
 */
data class ChainDiff(
    val path: String? = null,
    val summary: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val note: String? = null,
    val hunk: List<String> = emptyList(),
)

/** 链条的一次对外播报（一张卡一次 rev）。 */
data class ChainProgress(
    val chainId: String,
    val rev: Int,
    /** `executing` / `prototype` / `review_ready` / `blocked` / `aborted`（§6.1 的中止终态，不是 blocked）。 */
    val state: String,
    val title: String,
    val currentRole: String? = null,
    val stages: List<Pair<String, String>> = emptyList(),
    val percent: Int = 0,
    val banner: String? = null,
    val options: List<Pair<String, Boolean>> = emptyList(),
    val artifacts: List<Pair<String, Long>> = emptyList(),
    val diff: ChainDiff? = null,
    /**
     * 每一棒的**承办人**（与 [stages] 平行；短一截或缺位 = 该棒不标）。
     *
     * 文档 §3.5 的 roster 三元组（role/assignee/status）里，status 由 [stages] 承担，
     * assignee 落在这里。手机端 `parseAgentCard` 一直认 `items[].assignee`（渲染成
     * 步骤行内的小徽标），此前宿主从来不填 —— 卡上永远只有棒名，看不出是谁干。
     * 排在参数表末尾：[StageChainRunner] 与 DesktopStageChain 的既有位置参数调用
     * 不能因为加字段而逐个翻新。
     */
    val assignees: List<String> = emptyList(),
    /**
     * Reviewer 审计结论（合流卡专用）：每棒两条（语法规范/白名单键）+ 卡级一条（帧预算）。
     * 形状与手机端 parseAgentCard 的 checks[] 契约一字不差 —— status 用棒级词表
     * （done/failed/pending），SKIP 借 pending 承载并在观察语里写明原因（缺输入不是通过）。
     */
    val checks: List<ChainCheck> = emptyList(),
    /**
     * 每一棒**干了什么**（与 [stages] 平行；压到 200 字的棒摘要，来自 `StageRun.summary`）。
     * 卡面信息升级（卡片进展评审 P1）：此前摘要只交给下一棒，卡上
     * 只有棒名+状态词 —— 用户问「做了哪些事情」时卡答不上来。缺位/空 = 该棒不标。
     */
    val details: List<String> = emptyList(),
    /**
     * 进程内合流信号：所有审查棒显式 PASS 才为 true。刻意**不进 cardOf 的 JSON**
     * —— 它是宿主侧的调度事实，不是协议字段，线上字节一个都不变。
     */
    val reviewPassed: Boolean = false,
    val context: String? = null,
    val target: String? = null,
    val module: String? = null,
    /**
     * 执行中卡的**已用秒数**（规格二的 `编码中 (45s)`）：工单创建到本卡的墙钟差，
     * 由发射方算好，渲染端只负责展示。null = 不标（终态卡/无起始时刻的卡不冒充）。
     */
    val elapsedSec: Long? = null,
)

/** 合流卡上的一条审计结论（与手机端 checks[] 解析契约同形）。 */
data class ChainCheck(
    val id: String,
    val label: String,
    val status: String,
    val observation: String? = null,
    /** 该棒墙钟耗时的人读形态（"1.4s"）；null = 调用方没量。手机端 check 行渲染成等宽小标签。 */
    val time: String? = null,
)

object StageChain {

    /** §7.7 的 200 字预算：摘要就是摘要，不是日志的另一个名字。 */
    const val MAX_SUMMARY = 200

    /**
     * 卡上 diff 预览的**量入为出**参数：帧预算（[Limits.CARD_BYTES]）是硬上限，
     * hunk 行数与行长在这里裁死，别让一张大 patch 把整卡顶爆 —— 顶爆的下场不是
     * 「预览长一点」，是整卡被对端拒收（bad-payload）。
     */
    const val PREVIEW_MAX_HUNK_LINES = 12
    const val PREVIEW_MAX_LINE_CHARS = 160

    /**
     * 从收割到的 patch.diff 原文构建卡上预览（纯函数，测试直喂字符串）。
     *
     * 只取**第一个文件**的首个 hunk：预览的目的是「机主扫一眼知道改的是什么」，
     * 不是替代 review 工具 —— 多文件 patch 用 [ChainDiff.summary] 说「N files」，
     * 全文在 artifact 的 patch.diff 里（交付块），点开有全量。
     *
     * 行计数与 git 完全同口径：`+`/`-` 行计入增删，`+++`/`---` 文件头不计。
     */
    fun patchPreviewOf(patchText: String, summary: String? = null): ChainDiff {
        var path: String? = null
        var additions = 0
        var deletions = 0
        var files = 0
        val hunk = mutableListOf<String>()
        var inFirstHunk = false
        var truncated = false
        fun capture(line: String) {
            if (hunk.size < PREVIEW_MAX_HUNK_LINES) {
                hunk.add(line.take(PREVIEW_MAX_LINE_CHARS))
            } else {
                truncated = true
            }
        }
        for (raw in patchText.lineSequence()) {
            when {
                raw.startsWith("diff --git") -> {
                    files++
                    if (path == null) path = raw.removePrefix("diff --git a/").substringBefore(" b/")
                    if (inFirstHunk) truncated = true
                    inFirstHunk = false
                }
                raw.startsWith("@@") -> {
                    if (inFirstHunk) {
                        // 同文件的第二个 hunk：预览只取第一个，标记截断。
                        truncated = true
                        inFirstHunk = false
                    } else if (files == 1) {
                        inFirstHunk = true
                        capture(raw)
                    }
                }
                (raw.startsWith('+') && !raw.startsWith("+++")) ||
                    (raw.startsWith('-') && !raw.startsWith("---")) -> {
                    if (raw.startsWith('+')) additions++ else deletions++
                    if (inFirstHunk) capture(raw)
                }
                raw.startsWith(' ') || raw.isEmpty() -> {
                    if (inFirstHunk) capture(if (raw.isEmpty()) "" else raw)
                }
            }
        }
        if (hunk.firstOrNull()?.startsWith("@@") != true) hunk.clear()
        val multi = files > 1
        return ChainDiff(
            path = path,
            summary = summary ?: if (multi) files.toString() + " files changed" else null,
            additions = additions,
            deletions = deletions,
            note = if (truncated) "preview-truncated" else null,
            hunk = hunk,
        )
    }

    /** 摘要被截断时的标注。宁可承认缺了，也不要让下游以为自己看到了全文。 */
    const val TRUNCATION_MARKER = " …（上游摘要已截断）"

    /**
     * 隔离棒并行分发的并发上限：防一张派单一口气开满宿主 CPU。可测 ——
     * [StageChainRunner] 允许注入更小的值来钉住上限语义。
     */
    const val PARALLEL_MAX = 4

    /**
     * 派单文本里的隔离标记：命中的棒要求独立沙箱。写在任务书任何位置都认、并从任务书里
     * 摘掉 —— 标记只归调度，不混进交给棒的任务书。
     */
    val ISOLATION_MARKER = Regex("""\[iso(lated)?\]""", RegexOption.IGNORE_CASE)

    /**
     * 卡片 items 的宽松读取：requiresIsolation 缺失、畸形、非布尔一律 false ——
     * 老卡零影响。这是宿主装配侧从卡片读拆解标记的唯一入口（帧白名单不收这个键，
     * 它只活在已经解出的卡 JSON 里）。
     */
    fun requiresIsolationOf(item: JsonObject?): Boolean =
        (item?.get("requiresIsolation") as? JsonPrimitive)?.booleanOrNull ?: false

    /**
     * 拼一棒的 prompt。
     *
     * **只吃 [StageSeed]，不吃上一棒的原始输出** —— 这是 §7.7 唯一真正的约束，
     * 也是唯一能在这里用类型挡住的地方（签名里根本没有装日志的参数）。
     */
    fun promptOf(stage: ChainStage, seed: StageSeed): String = buildString {
        appendLine("你是一条多智能体责任链上的「${stage.role}」这一棒。只做本棒的事，不要越到下一棒。")
        if (stage.requiresIsolation) {
            appendLine("- 本棒标记了隔离执行：全部改动只能落在本棒的独立 worktree 沙箱里，绝不写宿主工作树。")
        }
        appendLine()
        appendLine("## 起点")
        appendLine("- 仓库（就是当前工作目录）：" + seed.repoPath)
        appendLine("- 基线提交：" + seed.baseSha + " —— 先 `git log --oneline -1` 核对，对不上就先停下来说明原因")
        if (!seed.previousSummary.isNullOrBlank()) {
            appendLine()
            appendLine("## 上一棒交接（摘要，原始日志不外传）")
            appendLine(seed.previousSummary.trim())
        }
        if (seed.previousArtifacts.isNotEmpty()) {
            appendLine()
            appendLine("## 上一棒产物指针")
            seed.previousArtifacts.forEach { appendLine("- " + it) }
        }
        appendLine()
        appendLine("## 本棒任务")
        appendLine(stage.instruction.trim())
        appendLine()
        appendLine("## 纪律")
        appendLine("- 只改本棒职责范围内的文件；与本棒无关的改动一律不动。")
        appendLine("- 不要执行 `git commit` / `git push`：宿主会替你提交，并把那个提交交给下一棒。")
        appendLine("- 收尾用一句话说清「改了什么、依据是什么、留了什么风险」—— 这句话会被截到 "
            + MAX_SUMMARY + " 字交给下一棒。")
    }

    /**
     * 把一段自由文本压成 ≤[max] 字的摘要。
     *
     * 先折叠空白：CLI 的收尾说明常带一行一个字的折行，直接截会截成一堆空行。
     * 超长时**保留头部**并挂 [TRUNCATION_MARKER] —— 结论通常在开头，尾部多是复述。
     */
    fun summarize(raw: String?, max: Int = MAX_SUMMARY): String? {
        val folded = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (folded.isEmpty()) return null
        if (folded.length <= max) return folded
        val keep = (max - TRUNCATION_MARKER.length).coerceAtLeast(1)
        return folded.take(keep).trimEnd() + TRUNCATION_MARKER
    }

    /**
     * 链卡的形状：与手机端 `parseAgentCard` 认的字段名一字不差。
     *
     * 卡片字段不是自由发挥 —— 手机只认 `cardId/rev/type/items/progress/banner`，写错一个键
     * 的后果是「群里出现一张空白卡」，两端都不报错。所以形状定义放在模型旁边，
     * 由 [StageChainTest] 钉住，而不是散在桌面壳的字符串拼接里。
     *
     * `type` 取 `task-board`（手机注册表里已有的那类：Progress + StepList + ButtonGroup），
     * 不新造 `orchestration_kanban` —— 新类型名不会让卡变得更好看，只会让它查不到推荐构成。
     */
    fun cardOf(progress: ChainProgress): JsonObject = buildJsonObject {
        put("cardId", "chain-" + progress.chainId)
        put("rev", progress.rev)
        put("type", "task-board")
        // **把状态写进卡里**，不让客户端去反推。反推只能看「有没有某个动作 / 进度到没到
        // 100」—— 于是「原型待选」被推成「待合并」、「已合并」推成什么都没有。
        // 状态是发出方唯一知道的事实，就该由发出方声明。
        put("state", progress.state)
        put("title", progress.title)
        put("progress", progress.percent)
        // 执行中卡的耗时段（规格二的 `(45s)`）：发射方算好秒数，渲染端只展示。
        // null 不发射 —— 终态卡与无起始时刻的卡不冒充计时。
        progress.elapsedSec?.let { put("elapsedSec", it) }
        put("items", buildJsonArray {
            progress.stages.forEachIndexed { index, (role, status) ->
                add(buildJsonObject {
                    put("id", role)
                    put("label", role)
                    put("status", status)
                    progress.assignees.getOrNull(index)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("assignee", it) }
                    // 这棒干了什么（P1）：有 detail 就上卡，用户不用去别处求证。
                    progress.details.getOrNull(index)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("detail", it) }
                })
            }
        })
        if (progress.checks.isNotEmpty()) {
            put("checks", buildJsonArray {
                progress.checks.forEach { check ->
                    add(buildJsonObject {
                        put("id", check.id)
                        put("label", check.label)
                        put("status", check.status)
                        check.observation?.takeIf { it.isNotBlank() }?.let { put("observation", it) }
                        check.time?.takeIf { it.isNotBlank() }?.let { put("time", it) }
                    })
                }
            })
        }
        // 数字员工工牌（原型稿卡片头行：bot 名 + worktree 分支 + 呼吸点）。
        // 名字取当值棒的承办人（正在运行的棒，终态取最后一棒，缺省回退首棒）；分支就是链条工作树
        // branch（真机日志同款 `chain/<id>`）；bdot = 卡级状态点，词表与原型一致
        // （run/done/fail）。全部从既有事实派生，没有一份新造的数据。
        val activeAssignee = run {
            if (progress.assignees.isEmpty()) return@run null
            val runningIdx = progress.stages.indexOfFirst { it.second == "running" }
            if (runningIdx in progress.assignees.indices) {
                return@run progress.assignees[runningIdx]
            }
            if (progress.currentRole != null) {
                val roleIdx = progress.stages.indexOfFirst { it.first == progress.currentRole }
                if (roleIdx in progress.assignees.indices) {
                    return@run progress.assignees[roleIdx]
                }
            }
            if (progress.state in setOf("review_ready", "done", "merged")) {
                return@run progress.assignees.lastOrNull()
            }
            progress.assignees.firstOrNull()
        }
        activeAssignee?.let { current ->
            putJsonObject("bot") {
                put("name", current)
                put("branch", "chain/" + progress.chainId)
                put("bdot", when (progress.state) {
                    "review_ready" -> "done"
                    "blocked", "aborted" -> "fail"
                    else -> "run"
                })
            }
        }
        // 状态 chips（原型稿 .mchips：目标/范围/门禁/风险的胶囊行）。这里
        // 包含机器事实（进度/门禁/受阻）与 Planner 产出的语义胶囊（目标/模块）。
        val chips = buildList {
            progress.target?.takeIf { it.isNotBlank() }?.let { add("🎯 目标: $it" to "pri") }
            progress.module?.takeIf { it.isNotBlank() }?.let { add("📦 模块: $it" to "") }
            when (progress.state) {
                "executing", "working", "prototype" -> {
                    val doneCount = progress.stages.count { it.second == "done" }
                    add("第 " + (doneCount + 1) + "/" + progress.stages.size + " 棒 · " + progress.percent + "%" to "pri")
                }
                "review_ready" -> {
                    // 没跑门禁（checks 空）就不许说「门禁 0 项全绿」—— 那是把缺输入说成通过。
                    if (progress.checks.isNotEmpty()) {
                        val failed = progress.checks.count { it.status == "failed" }
                        add("门禁 " + progress.checks.size + " 项" + (if (failed > 0) " · " + failed + " 红" else "全绿") to if (failed > 0) "err" else "ok")
                    }
                }
                "blocked" -> add("受阻 · 等你给方向" to "warn")
            }
        }
        if (chips.isNotEmpty()) {
            put("chips", buildJsonArray {
                chips.forEach { (text, tone) ->
                    add(buildJsonObject {
                        put("text", text)
                        put("tone", tone)
                    })
                }
            })
        }
        progress.context?.takeIf { it.isNotBlank() }?.let {
            put("context", it)
        }
        progress.banner?.let { text ->
            putJsonObject("banner") {
                put("text", withNextStep(progress.state, text))
                put("tone", toneOf(progress.state))
            }
        }
        // **干到一半的工单必须卡上有「停」**（D-NEW-2 的残留形态）。
        //
        // 在此之前这件事只做在 agentNode 的 `diff-*` 卡上（`SandboxedCliDriver.runningCardJson`），
        // 而工单卡走的是这里 —— `cardOf` 只产出 `options`，从不产出 `actions`，于是真机上
        // 「进行中」的工单卡整卡没有任何按钮（实测只有一行「进展 40%」）。用户唯一的出口
        // 仍是回群里打 `stop` 之类的关键词，打错一个字（`stp`）就静默无效。
        //
        // `abort-task` 是客户端已注册的系统动作（`SYSTEM_ACTION_ABORT_TASK`），文案由
        // 客户端本地化成「停止」并配红叉图标，这里只给动作串，不塞文案（cjkLiteralGate 口径）。
        // 只在 `working` 给：`proposed` 有「立即执行/取消」，终态与 `blocked`（等人给方向）
        // 都不该出现「停止」——那会把「等你说话」说成「还在跑」。
        if (progress.state == "working") {
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("abort-task"))
                    put("label", JsonPrimitive("abort-task"))
                })
            })
        }
        if (progress.options.isNotEmpty()) {
            put("options", buildJsonArray {
                progress.options.forEach { (label, recommended) ->
                    add(buildJsonObject {
                        put("label", label)
                        put("recommended", recommended)
                    })
                }
            })
        }
        if (progress.artifacts.isNotEmpty()) {
            put("artifacts", buildJsonArray {
                progress.artifacts.forEach { (name, bytes) ->
                    add(buildJsonObject {
                        put("name", name)
                        put("bytes", bytes)
                        if (name.endsWith(".html")) put("mime", "text/html")
                        else if (name.endsWith(".diff")) put("mime", "text/x-diff")
                        put("delivered", true)
                    })
                }
            })
        }
        progress.diff?.let { d ->
            putJsonObject("diff") {
                // 文档预览（原型稿 approval 卡的 `.df` 块）：diff hunk 原文 + 增删统计。
                // 键名与手机端 parseAgentCard 的 diff 契约逐字同形（path/summary/additions/
                // deletions/note/hunk[]）—— 这是**补完既有契约**，不是新增协议键：解析与
                // 渲染（CardDiffViewerRow / CardDiffHunkViewer）在客户端早已存在，只是宿主
                // 从来没填过 hunk/note。用户验收意见「卡片只有标题和选项，
                // 文档预览缺位」。注意 hunk 已在 patchPreviewOf 里按帧预算裁剪。
                d.path?.let { put("path", it) }
                d.summary?.let { put("summary", it) }
                d.additions?.let { put("additions", it) }
                d.deletions?.let { put("deletions", it) }
                d.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                if (d.hunk.isNotEmpty()) {
                    put("hunk", buildJsonArray {
                        d.hunk.forEach { line -> add(JsonPrimitive(line)) }
                    })
                }
            }
        }
        if (progress.state == "review_ready") {
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", "merge-patch")
                    put("label", "Merge")
                    put("approval", true)
                })
            })
        } else if (progress.state == "prototype") {
            put("actions", buildJsonArray {
                // 评审入口排在批准之前：阶段 A 的动作是「先看原型、再批准」。
                // 没有这一颗，用户是在看不到原型的情况下点 Approve & Develop ——
                // 全生命周期验收里「人审」名义存在、实质空转。
                //
                // 它是**客户端本地动作**：真正要打开的文件在本机（产物已按
                // purpose=artifact 投递），宿主替不了。客户端不许把它派发过来 ——
                // 认不出的动作会被 HostCardActions 丢掉并顺手把卡翻成 stale。
                add(buildJsonObject {
                    put("actionId", "open-prototype")
                    put("label", "Open in browser")
                    put("labelKey", "card.action.open_prototype")
                })
                add(buildJsonObject {
                    put("actionId", "approve-prototype")
                    put("label", "Approve & Develop")
                    put("approval", true)
                })
            })
        } else if (progress.state == "proposed") {
            // 团队点将卡（文档 §二 Proposed 态 / §3.5 卡片状态 1）：roster 已就位，
            // 人可以提前开工或撤销 —— 但**不是审批门**，倒计时到点自动开工。
            // 不写 approval=true：那会把「开始干活」变成又一道等待人点头的手续。
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", "start-task")
                    // 旧端只认 label（英文兜底，不空白）；新端按 labelKey 翻成本地词条。
                    put("label", "Start now")
                    put("labelKey", "card.action.start_task")
                })
                add(buildJsonObject {
                    put("actionId", "cancel-task")
                    put("label", "Cancel")
                    put("labelKey", "card.action.cancel_task")
                })
            })
        } else if (progress.state == "blocked") {
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", "retry")
                    put("label", "Retry")
                })
            })
        }
    }

    /**
     * 从 CLI 的输出里捞出**它真说的话**。
     *
     * 为什么不能直接截日志尾部：三个驱动各有各的信封 —— codex 是
     * `{"type":"item.completed","item":{"type":"agent_message","text":…}}`，agy 是
     * `{"event":"step_update","step_update":{…}}`，claude 又是另一种。截尾部的结果是
     * 把 `"_response","text_delta"` 这种机器噪声当成交接摘要传给下一棒（真机实测，
     * 责任链提交信息曾全被噪声占满）。
     *
     * 判据：**逐行试 JSON**，命中已知的文本键就取出来；一行都没命中（纯文本 CLI）
     * 就原样返回，交给 [summarize] 折叠截断。识别不了也不猜 —— 宁可退回原文。
     */
    fun saidText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val picked = StringBuilder()
        var sawJson = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return@forEach
            sawJson = true
            val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return@forEach
            collectText(element)?.takeIf { it.isNotBlank() }?.let { text ->
                if (picked.isNotEmpty()) picked.append(' ')
                picked.append(text)
            }
        }
        if (picked.isNotBlank()) return picked.toString()
        // 全是 JSON 但一个文本键都没认出来（未知信封）：如实退回原文，不假装拿到了摘要。
        return if (sawJson) raw.trim() else raw.trim()
    }

    /** 深度优先找 `text` / `text_delta` / `delta` 这类字符串值（各驱动的共同落点）。 */
    private fun collectText(element: JsonElement): String? = when (element) {
        is JsonObject -> element.entries
            .filter { it.key in TEXT_KEYS }
            .mapNotNull { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .joinToString(" ")
            .ifBlank { element.values.mapNotNull { collectText(it) }.joinToString(" ") }
            .ifBlank { null }

        is JsonArray -> element.mapNotNull { collectText(it) }.joinToString(" ").ifBlank { null }
        else -> null
    }

    private val TEXT_KEYS = setOf("text", "text_delta", "delta", "message", "summary")

    private fun toneOf(state: String): String = when (state) {
        "blocked", "aborted" -> "error"
        "review_ready" -> "success"
        else -> "info"
    }

    /**
     * 「下一步轮到你」——卡片状态到**人话**的翻译（卡片进展评审 P1）。
     *
     * 用户三问之一是「哪些需要人、哪些自动流转」：state 词表是给程序看的，人看到
     * `review_ready` 未必知道那是「该我合并了」。这里把每态的人动作追加到 banner 尾部，
     * 单点生效（所有经 [cardOf] 的卡都有）。执行中的卡明确说「无需你操作」——
     * 自动流转不该制造需要人扫读的悬念。
     */
    private fun withNextStep(state: String, text: String): String {
        if (text.contains("下一步")) return text
        val next = when (state) {
            "review_ready" -> "等你合并或打回"
            "blocked" -> "需要你给方向：可重试或补充说明"
            "prototype" -> "等你选稿"
            "working", "executing" -> "无需你操作"
            else -> return text
        }
        return text + "｜下一步：" + next
    }

    /**
     * 卡片 `state` → 阶段项 `status` 的**翻译**。两套词表，别混用。
     *
     * - `state` 说的是**这张卡此刻处于哪一步**，它的词表里装着 `review_ready` / `merged` /
     *   `aborted` 这类**卡级**终态；
     * - `items[].status` 说的是**这一棒跑成什么样**，词表只有 `pending` / `running` /
     *   `done` / `failed` / `blocked`（渲染层 `parseAgentCard` 就按这五个词认）。
     *
     * 从前单棒工单卡直接把 state 塞进 item.status（`stages = listOf(role to state)`），
     * 于是：
     *
     * - `prototype` / `executing` / `merged` / `aborted` 这四个词**不在** item 词表里，
     *   渲染层一律退成 `pending` —— 卡上写着「在做原型」，唯一那一步却显示「未开始」；
     * - 翻面成 `merged` 之后，卡变成 `state=merged` + `item=review_ready`，**自相矛盾**
     *   （D6）。翻面只改 state、不动 items，矛盾就一直留在落库的卡里。
     *
     * 所以翻译要显式写出来：卡级状态说「等合流」，棒级状态说「这棒做完了」。
     */
    fun stageStatusOf(state: String): String = when (state) {
        // 还在干：棒在跑。
        "executing", "working", "prototype", "queued" -> "running"
        // 棒已经交活了 —— 无论后面等的是合流、是原型确认，还是已经合进去。
        "review_ready", "merged", "done" -> "done"
        "blocked" -> "blocked"
        // 中止没有自己的棒级词：这棒确实没跑完，`failed` 是唯一不说谎的选项
        // （写成 `pending` 会让它看起来还能接着跑）。
        "aborted" -> "failed"
        else -> "pending"
    }

    /**
     * 翻面成 `merged` 时把阶段项一起推进：还在「等合流」的棒，合流之后就是 `done`。
     *
     * **只动 `review_ready`**。`pending`（没跑）与 `failed`（跑挂了）都不许被改成 `done`
     * —— 那是把没发生的事写成发生过，比状态陈旧更糟。
     *
     * 为什么翻面这里还要再洗一遍：`stageStatusOf` 管的是**新发**的卡，而落过库的旧卡
     * 里可能已经躺着 `review_ready` 的阶段项；翻面是它最后一次被改写的机会。
     */
    fun itemsAfterMerge(items: JsonElement?): JsonElement? {
        val array = items as? JsonArray ?: return items
        return JsonArray(
            array.map { element ->
                val obj = element as? JsonObject ?: return@map element
                val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
                if (status != "review_ready") return@map element
                JsonObject(obj + ("status" to JsonPrimitive("done")))
            },
        )
    }

    /**
     * 把自然语言提示词解析为多角色责任链。
     *
     * 支持「角色(驱动键): 任务」或「角色: 任务」格式（分号或换行分隔）。
     * 至少匹配到 2 棒才视为链（否则返回空列表，退回单 Agent 任务）。
     */
    fun parseStages(text: String, defaultKey: String = "codex"): List<ChainStage> {
        val lines = text.split(Regex("[\r\n;\uff1b]+")).map { it.trim() }.filter { it.isNotBlank() }
        val rolePattern = Regex("""^([^\s:()\uff1a]+)(?:\(([^()]+)\))?[:\uff1a]\s*(.+)$""")
        val matched = lines.mapNotNull { line ->
            val match = rolePattern.find(line) ?: return@mapNotNull null
            // 标记可以贴在棒名上（开发[iso]: …）也可以落在任务书里（… [iso] …），
            // 两处都认、都摘掉 —— 派单人写哪边都不该改变语义。
            val rawRole = match.groupValues[1].trim()
            val isolatedInRole = ISOLATION_MARKER.containsMatchIn(rawRole)
            val role = if (isolatedInRole) {
                rawRole.replace(ISOLATION_MARKER, " ").replace(Regex("\\s+"), " ").trim()
            } else {
                rawRole
            }
            val explicitKey = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
            val rawInstruction = match.groupValues[3].trim()
            val isolatedInInstruction = ISOLATION_MARKER.containsMatchIn(rawInstruction)
            val isolated = isolatedInRole || isolatedInInstruction
            // 标记从任务书里摘掉（只在真带了标记时才重排空白，老文本一字不动）。
            val instruction = if (isolatedInInstruction) {
                rawInstruction.replace(ISOLATION_MARKER, " ").replace(Regex("\\s+"), " ").trim()
            } else {
                rawInstruction
            }
            if (role.isEmpty() || instruction.isEmpty()) null
            else ChainStage(role = role, agentKey = explicitKey ?: defaultKey,
                instruction = instruction, requiresIsolation = isolated)
        }
        return if (matched.size >= 2) matched else emptyList()
    }

    /**
     * 卡片的**跨模块形态**：一串 JSON。
     *
     * 为什么不让桌面壳直接拿 [JsonObject]：`desktopApp` 的主源集没有
     * `kotlinx.serialization.json` 的编译期依赖（只有 testImplementation，见该模块
     * build.gradle.kts 的注释），引用这个类型会直接编译不过。形状留在本模块定义、
     * 跨模块只传字符串 —— 解析交给同样依赖齐备的域层。
     */
    fun cardJsonOf(progress: ChainProgress): String = cardOf(progress).toString()

    /**
     * 从沙箱的 `patch.diff` 里抽出**某个文件的完整新侧内容**。
     *
     * ## 为什么需要
     *
     * 沙箱只把改动交出来（patch.diff），任务跑完 worktree 就被无痕清理 —— 沙箱里那个
     * `prototype.html` 在宿主侧从来没有过实体文件。而阶段 A 的验收要求卡上有**能打开的
     * 原型产物**：卡上写一行「prototype.html 4 KB」但既没有文件也没有投递，是把
     * 「有个东西」当成了「你拿得到」。真机上它的形态就是一行点不动的文字。
     *
     * ## 为什么只认「完整」的那两种形状
     *
     * unified diff 对**改过的文件**只带变化的 hunk，上下文之外的内容不在补丁里。拿它拼
     * 出来的文件是**残缺的**，而残缺的 HTML 打开是白屏 —— 那比没有产物更糟（用户会以为
     * 原型坏了）。所以只接受两种能还原整份文件的形状：
     *
     * 1. **新文件**（`--- /dev/null`）：`+` 行就是全文；
     * 2. **单 hunk 且从第 1 行开始**：一个从 `-1` 起的 hunk 一直延伸到文件尾，新侧即全文。
     *
     * 其余情况返回 null —— 调用方据此**不宣称**产物，而不是给个编出来的字节数。
     */
    fun extractFileFromPatch(patchFile: java.io.File, path: String): String? {
        if (!patchFile.exists() || patchFile.length() == 0L) return null
        val wanted = path.trim().removePrefix("./")
        val out = StringBuilder()
        var inTarget = false
        var sawHeader = false
        var isNewFile = false
        var hunkCount = 0
        var startsAtLineOne = false
        // 目标段落结束时才算「完整不完整」：补丁里通常有多个文件，用最后一节的状态
        // 去判定目标文件，会把「新文件」判成不完整（第一节之后跟着别的文件就重置了）。
        var targetComplete = false
        fun closeSection() {
            if (inTarget) targetComplete = isNewFile || (hunkCount == 1 && startsAtLineOne)
        }
        patchFile.forEachLine { line ->
            if (line.startsWith("diff --git ")) {
                closeSection()
                inTarget = false
                sawHeader = false
                isNewFile = false
                hunkCount = 0
                startsAtLineOne = false
            } else if (!sawHeader && (line.startsWith("--- ") || line.startsWith("+++ "))) {
                if (line.startsWith("--- ") && line.removePrefix("--- ").trim() == "/dev/null") {
                    isNewFile = true
                }
                if (line.startsWith("+++ ")) {
                    val target = line.removePrefix("+++ ").trim().removePrefix("b/")
                    inTarget = target == wanted
                    sawHeader = true
                }
            } else if (inTarget && line.startsWith("@@")) {
                hunkCount++
                // `@@ -1,N +1,M @@`：从第 1 行开始 = 这个 hunk 覆盖到文件尾。
                if (hunkCount == 1) startsAtLineOne = line.removePrefix("@@").trim().startsWith("-1,")
            } else if (inTarget && hunkCount > 0) {
                when {
                    line.startsWith("+") -> out.append(line.substring(1)).append('\n')
                    // 上下文行同样属于新侧；漏掉它们，改过的文件会被抽成「只剩新增的几行」。
                    line.startsWith(" ") -> out.append(line.substring(1)).append('\n')
                    // `-` 行是旧侧；`\ No newline at end of file` 是标记不是内容。
                    else -> Unit
                }
            }
        }
        closeSection()
        return out.toString().takeIf { targetComplete && it.isNotEmpty() }
    }

    /**
     * 解析沙箱产物目录里的 report.json 或 patch.diff，提纯用于卡片展示的 Diff 与字节大小。
     */
    /** 审阅卡 diff 可视化的行预算：超出的行如实计数（完整内容在 patch.diff 附件）。 */
    val DIFF_HUNK_MAX_LINES = 60
    val DIFF_HUNK_LINE_MAX = 200

    fun parseHarvest(patchFile: java.io.File, repoName: String? = null): Pair<ChainDiff, Long>? {
        if (!patchFile.exists() || patchFile.length() == 0L) return null
        val reportFile = java.io.File(patchFile.parentFile, "report.json")
        var files = 1
        var adds = 0
        var dels = 0
        if (reportFile.exists()) {
            runCatching {
                val rep = Json.parseToJsonElement(reportFile.readText()) as? JsonObject
                if (rep != null) {
                    files = (rep["filesChanged"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                    adds = (rep["additions"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    dels = (rep["deletions"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                }
            }
        } else {
            val text = patchFile.readText()
            files = maxOf(1, text.split("diff --git").size - 1)
            adds = text.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
            dels = text.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
        }
        // summary **只放文件数**：`+N −N` 由渲染侧按 additions/deletions 单独上色。
        // 这里再写一遍，卡片上就会显示「15 个文件 +1817 −449 +1817 −449」
        // （真机截图上亲眼见过，SandboxedCliDriver 的 diffCardJson 有同一个毛病）。
        // 卡面上给人读的一句话：与驱动侧同一写法（「2 个文件」），不把英文复数后缀露给用户。
        val summaryText = "$files 个文件"
        // **hunk 必须是真补丁内容**（A22 用户验收判据：diff 是可视化的，不是一句
        // 「已就绪」的空话）—— 渲染端 CardDiffHunkViewer（原型 .df-hunk）等着这份数据，
        // 此前 hunk 恒空 ⇒ 审阅卡只剩统计行，用户看不到改了什么。取变更行
        // （diff --git 文件头 + @@ + 加减行），剔 index/+++ /--- 信封行；超出
        // 预算截断并如实标注行数 —— 完整内容仍在 patch.diff 附件里。
        val hunkLines = buildList {
            var budget = DIFF_HUNK_MAX_LINES
            var truncated = 0
            patchFile.useLines { lines ->
                for (line in lines) {
                    val keep = line.startsWith("diff --git") || line.startsWith("@@") ||
                        (line.startsWith("+") && !line.startsWith("+++")) ||
                        (line.startsWith("-") && !line.startsWith("---"))
                    if (!keep) continue
                    if (budget <= 0) { truncated++; continue }
                    add(line.take(DIFF_HUNK_LINE_MAX))
                    budget--
                }
            }
            if (truncated > 0) add("… +$truncated more lines (see patch.diff)")
        }
        return Pair(
            ChainDiff(
                path = repoName,
                summary = summaryText,
                additions = adds,
                deletions = dels,
                hunk = hunkLines,
            ),
            patchFile.length(),
        )
    }
}

/** 链执行器只需要一个只读的 JSON 解析器（逐行拆 CLI 的各种信封）。 */
private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** 外壳装配链执行器；null 的重返值 = 这条请求接不了（宿主没装驱动器等）。 */
fun interface AgentChainDispatcher {
    /** @return chainId（已受理，后台在跑）；null = 没有链执行器。 */
    suspend fun start(
        groupId: String?,
        repoPath: String?,
        title: String,
        stages: List<ChainStage>,
        target: String?,
        module: String?,
        context: String?,
    ): String?
}

/** 兼容旧调用的 4 参扩展函数（不带 target/module/context）。 */
suspend fun AgentChainDispatcher.start(
    groupId: String?,
    repoPath: String?,
    title: String,
    stages: List<ChainStage>,
): String? = start(groupId, repoPath, title, stages, null, null, null)

/**
 * 顺序跑一条链。
 *
 * 四个动作全部注入：读 HEAD、跑一棒、把一棒的产出落到宿主仓、播报。
 * 这样这一层不碰进程、不碰 git、不碰 UI，判据可以写成普通断言（见 StageChainTest）。
 */
class StageChainRunner(
    private val headSha: suspend (repoPath: String) -> String?,
    private val runStage: suspend (stage: ChainStage, prompt: String, repoPath: String) -> StageRun,
    /**
     * 落一棒：把 patch 应用到宿主仓并提交。返回新 HEAD。
     * null = 这棒没有产生可提交的改动（**不是**失败：审查棒只读也能判 PASS）。
     */
    private val land: suspend (repoPath: String, run: StageRun, message: String) -> String?,
    private val report: suspend (ChainProgress) -> Unit = {},
    /**
     * Reviewer 白名单键审计的取值域清单（宿主装配注入）。null = 宿主没有清单可对照
     * ⇒ 该判据如实 SKIP（缺输入不是通过，但也不冒充失败）。
     */
    private val valueDomains: List<ReviewerAudit.ValueDomain>? = null,
    /** 隔离棒并行分发的并发上限（防一张派单开满宿主 CPU）；测试注入更小值钉住语义。 */
    private val parallelLimit: Int = StageChain.PARALLEL_MAX,
    /** 线性多棒审查流水线；null = 旧的单链行为逐字不变（P2 规格 3 的可逆开关）。 */
    private val review: ReviewPipeline? = null,
    /** 从一棒的结论里取审查判定；缺省 null = 不解析（与 review=null 成对）。 */
    private val verdictOf: (StageRun) -> ReviewVerdict? = { null },
) {

    suspend fun run(
        chainId: String,
        repoPath: String,
        title: String,
        stages: List<ChainStage>,
    ): List<StageRun> {
        require(stages.isNotEmpty()) { "chain $chainId has no stages" }
        var rev = 0
        val statuses = stages.map { it.role to "pending" }.toMutableList()
        // 线性多棒模式拒绝并行隔离组：扇出与投票会把确定性时序变成锁竞争（规格明确不做）。
        if (review != null && stages.any { it.requiresIsolation }) {
            report(ChainProgress(chainId, ++rev, "blocked", title, null, statuses.toList(), 0,
                "Review pipeline is strictly linear: isolation stages are not allowed"))
            return emptyList()
        }
        var attempt = 1
        var reviewMerge = false
        // 每棒摘要（P1）：与 statuses 平行，随每次 report 上卡 —— 「做了哪些事情」的答案。
        val details = arrayOfNulls<String>(stages.size)
        fun detailsArg(): List<String> = details.map { it.orEmpty() }
        // 读不到基线就不开跑：没有起点的链跑出来的「全绿」谁也核对不了。
        val base = headSha(repoPath) ?: run {
            report(ChainProgress(chainId, ++rev, "blocked", title, null, statuses.toList(), 0,
                "读不到 " + repoPath + " 的 HEAD —— 这不是 git 仓库，或宿主动不了它", details = detailsArg()))
            return emptyList()
        }
        report(ChainProgress(chainId, ++rev, "executing", title, null, statuses.toList(), 0,
            "链条开工：" + stages.size + " 棒，基线 " + base.take(8), details = detailsArg()))

        var cursor = base
        var prevSummary: String? = null
        var prevArtifacts: List<String> = emptyList()
        val done = mutableListOf<StageRun>()
        // 审计要按棒对账（哪根棒的 patch 犯了哪条判据），执行完的棒与棒定义成对留存。
        val executed = mutableListOf<Pair<ChainStage, StageRun>>()

        var index = 0
        while (index < stages.size) {
            if (stages[index].requiresIsolation) {
                // —— 隔离组：连续多根 requiresIsolation=true 的棒 ——
                // 同一基线并行开工（每根棒由驱动侧的 worktree 沙箱各给一个实例），
                // 全部完成才按棒序落盘、才进下一棒。非隔离棒的顺序依赖链不动。
                var end = index
                while (end < stages.size && stages[end].requiresIsolation) end++
                val group = index until end
                group.forEach { i -> statuses[i] = stages[i].role to "running" }
                report(ChainProgress(chainId, ++rev, "executing", title,
                    if (group.count() == 1) stages[index].role else null, statuses.toList(),
                    percentOf(index, stages.size),
                    if (group.count() == 1) {
                        stages[index].role + " 开跑（第 " + (index + 1) + "/" + stages.size + " 棒）"
                    } else {
                        "第 " + (index + 1) + "–" + end + " 棒（隔离组 " + group.count() + " 根）并行开跑"
                    }))

                val permits = Semaphore(parallelLimit.coerceAtLeast(1))
                val seed = StageSeed(cursor, repoPath, prevSummary, prevArtifacts)
                // async 默认继承 runBlocking 的单线程事件循环：不真正让出就退化成
                // 顺序执行（峰值=1、区间相离的假并行）。显式上 Default：隔离棒的执行
                // 本来就是另一条工作线程上的事，测试与生产同一条代码路径。
                val runs: List<StageRun> = coroutineScope {
                    group.map { i ->
                        async(Dispatchers.Default) {
                            permits.withPermit {
                                val t0 = System.nanoTime()
                                val r = runStage(stages[i], StageChain.promptOf(stages[i], seed), repoPath)
                                r.copy(elapsedMs = (System.nanoTime() - t0) / 1_000_000)
                            }
                        }
                    }.awaitAll()
                }
                runs.forEachIndexed { k, r ->
                    val i = group.first + k
                    statuses[i] = stages[i].role to (if (r.ok) "done" else "failed")
                }
                executed += group.map { stages[it] }.zip(runs)
                // 全部完成才判停：任一棒失败 ⇒ 整组不落盘、不进下一棒。部分合入比不合入
                // 更难对账（「哪半个改动是谁的」变成悬案），fail-stop 保持整组原子。
                val firstFailure = runs.indexOfFirst { !it.ok }
                if (firstFailure >= 0) {
                    val failIndex = group.first + firstFailure
                    details[failIndex] = (runs[firstFailure].error ?: "原因未报").take(200)
                    report(ChainProgress(chainId, ++rev, "blocked", title, stages[failIndex].role,
                        statuses.toList(), percentOf(failIndex, stages.size),
                        "第 " + (failIndex + 1) + " 棒失败，链条已停：" + (runs[firstFailure].error ?: "原因未报"),
                        details = detailsArg()))
                    done += runs
                    return done
                }
                // 按棒序串行落盘：宿主仓的 git 是共享面，不能并发 apply。
                group.forEachIndexed { k, i ->
                    val stage = stages[i]
                    val run = runs[k]
                    val summary = StageChain.summarize(run.summary)
                    val sha = land(repoPath, run.copy(summary = summary),
                        commitMessageOf(chainId, i, stages.size, stage, summary))
                    done += run.copy(summary = summary, sha = sha)
                    if (sha != null) cursor = sha
                    details[i] = summary
                    // 完成事件带上「干了什么」的尾巴：卡上不用点开也知道这棒做成了什么。
                    val tail = summary?.take(80)?.let { "：" + it } ?: ""
                    report(ChainProgress(chainId, ++rev, "executing", title, stage.role, statuses.toList(),
                        percentOf(i + 1, stages.size),
                        stage.role + " 完成" + (if (sha == null) "（无改动）" else "（提交 " + sha.take(8) + "）") + tail,
                        details = detailsArg()))
                }
                prevSummary = runs.mapNotNull { StageChain.summarize(it.summary) }.joinToString("；")
                prevArtifacts = runs.mapNotNull { it.patchPath }
                index = end
                continue
            }

            // —— 非隔离棒：维持原顺序依赖链 ——
            val stage = stages[index]
            statuses[index] = stage.role to "running"
            report(ChainProgress(chainId, ++rev, "executing", title, stage.role, statuses.toList(),
                percentOf(index, stages.size), stage.role + " 开跑（第 " + (index + 1) + "/" + stages.size + " 棒）",
                details = detailsArg()))

            val prompt = StageChain.promptOf(stage, StageSeed(cursor, repoPath, prevSummary, prevArtifacts))
            val t0 = System.nanoTime()
            val run = runStage(stage, prompt, repoPath)
                .copy(elapsedMs = (System.nanoTime() - t0) / 1_000_000)
            executed += stage to run
            if (!run.ok) {
                // 停机而不是跳过：一棒失败还继续跑，产出的「全绿」是假的。
                statuses[index] = stage.role to "failed"
                details[index] = (run.error ?: "原因未报").take(200)
                report(ChainProgress(chainId, ++rev, "blocked", title, stage.role, statuses.toList(),
                    percentOf(index, stages.size),
                    "第 " + (index + 1) + " 棒失败，链条已停：" + (run.error ?: "原因未报"),
                    details = detailsArg()))
                return done + run
            }
            val summary = StageChain.summarize(run.summary)
            val sha = land(repoPath, run.copy(summary = summary),
                commitMessageOf(chainId, index, stages.size, stage, summary))
            statuses[index] = stage.role to "done"
            done += run.copy(summary = summary, sha = sha)
            if (sha != null) cursor = sha
            details[index] = summary
            prevSummary = summary
            prevArtifacts = listOfNotNull(run.patchPath)
            val tail = summary?.take(80)?.let { "：" + it } ?: ""
            report(ChainProgress(chainId, ++rev, "executing", title, stage.role, statuses.toList(),
                percentOf(index + 1, stages.size),
                stage.role + " 完成" + (if (sha == null) "（无改动）" else "（提交 " + sha.take(8) + "）") + tail,
                details = detailsArg()))
            // 线性多棒审查：REJECT 打回第 0 棒（保留已落地的提交现场），全棒 PASS 才给合流信号。
            // review=null 时走原来的 index += 1，既有链行为逐字不变。
            val advance = review?.advance(
                index, stages.size, attempt, verdictOf(run), run.summary ?: run.error.orEmpty(),
            )
            if (advance == null) {
                index += 1
            } else when (advance) {
                is ReviewPipeline.Advance.Next -> index = advance.stageIndex
                is ReviewPipeline.Advance.Merge -> {
                    reviewMerge = true
                    index = stages.size
                }
                is ReviewPipeline.Advance.BackToWorker -> {
                    attempt = advance.attempt
                    prevSummary = advance.feedback
                    prevArtifacts = emptyList()
                    index = 0
                    statuses.indices.forEach { i -> statuses[i] = stages[i].role to "pending" }
                    report(ChainProgress(chainId, ++rev, "executing", title, stages[0].role,
                        statuses.toList(), percentOf(1, stages.size),
                        "attempt " + advance.attempt + ": review rejected, back to worker with feedback",
                        details = detailsArg()))
                }
                is ReviewPipeline.Advance.Blocked -> {
                    report(ChainProgress(chainId, ++rev, "blocked", title, stage.role,
                        statuses.toList(), percentOf(index, stages.size), advance.reason,
                        details = detailsArg()))
                    return done
                }
            }
        }

        // —— Reviewer 三项审计：全部执行棒完成、验收棒合流前（fail-closed）——
        // a) 语法规范 + b) 白名单键按棒判定；c) 帧预算对**合流卡整体**判定。
        val auditChecks = ReviewerAudit.checksFor(executed, valueDomains)
        val readyBanner = "全部 " + stages.size + " 棒完成，等待机主终审"
        // 文档预览（用户验收意见）：最后一根有产出的棒的 patch，
        // 在合流卡上给**真 hunk 预览 + 交付产物块** —— 机主批准前看得到自己批的是什么。
        // 读不到/超预算由 patchPreviewOf 的裁剪兜底；没有产出的棒（nothing to harvest）
        // 保持最小卡，不硬造一个空 diff。
        val lastPatchRun = executed.lastOrNull { it.second.patchPath != null }?.second
        val patchFile = lastPatchRun?.patchPath?.let { java.io.File(it) }
        val patchPreview = if (patchFile?.isFile == true) {
            runCatching { StageChain.patchPreviewOf(patchFile.readText(), lastPatchRun.summary) }.getOrNull()
        } else {
            null
        }
        val patchArtifacts = if (patchFile?.isFile == true) {
            listOf("patch.diff" to patchFile.length())
        } else {
            emptyList()
        }
        // 量测自指：checks[] 里写下的量值本身会改变卡的字节数 —— 迭代到量值稳定
        // （位数不变即收敛），让「合流卡实测 N B」里的 N 就是发出的那张卡自己的字节数，
        // 不存在「量的是一张卡、发的是另一张」。
        var measured = 0
        var readyJson = ""
        repeat(3) {
            readyJson = StageChain.cardJsonOf(
                ChainProgress(chainId, rev + 1, "review_ready", title, stages.last().role,
                    statuses.toList(), 100, readyBanner,
                    checks = auditChecks + ReviewerAudit.budgetCheck(measured), details = detailsArg(),
                    diff = patchPreview, artifacts = patchArtifacts),
            )
            measured = readyJson.toByteArray(Charsets.UTF_8).size
        }
        val budgetFailed = ChainCheck("budget", "帧预算 ≤ " + Limits.CARD_BYTES + " B", ReviewerAudit.FAILED,
            ReviewerAudit.budgetFailureOf(readyJson) ?: "")
        val failedCheck = if (budgetFailed.observation!!.isNotEmpty()) budgetFailed
            else auditChecks.firstOrNull { it.status == ReviewerAudit.FAILED }
        if (failedCheck != null) {
            val full = ChainProgress(chainId, rev + 1, "blocked", title, stages.last().role,
                statuses.toList(), 100, "审计未通过，停止合流：" + failedCheck.observation.orEmpty(),
                checks = auditChecks + budgetFailed, details = detailsArg())
            if (StageChain.cardOf(full).toString().toByteArray(Charsets.UTF_8).size <= Limits.CARD_BYTES) {
                rev++
                report(full)
            } else {
                // 卡本体已经超预算（典型：棒名或棒数把看板撑爆）：完整 blocked 卡对端会
                // 整帧拒收。发一张最小卡如实说「看板承载不下」—— 比静默丢卡强。
                rev++
                report(ChainProgress(chainId, rev, "blocked", title, null,
                    listOf("合并看板" to "blocked"), 100,
                    "审计未通过（合流卡超出帧预算，完整看板无法入卡）——已停止合流"))
            }
            return done
        }
        rev++
        report(ChainProgress(chainId, rev, "review_ready", title, stages.last().role,
            statuses.toList(), 100, readyBanner, checks = auditChecks + ReviewerAudit.budgetCheck(measured),
            details = detailsArg(), reviewPassed = reviewMerge,
            diff = patchPreview, artifacts = patchArtifacts))
        return done
    }

    private fun percentOf(finished: Int, total: Int): Int =
        if (total <= 0) 0 else (finished * 100 / total).coerceIn(0, 100)

    private fun commitMessageOf(
        chainId: String,
        index: Int,
        total: Int,
        stage: ChainStage,
        summary: String?,
    ): String = "chain(" + chainId + ") " + (index + 1) + "/" + total + " " + stage.role + ": " +
        (summary ?: "无摘要")
}