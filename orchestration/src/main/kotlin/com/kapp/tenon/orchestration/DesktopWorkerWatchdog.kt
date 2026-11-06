// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Limits
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 宿主侧看门狗与心跳的**纯判定件**（设计计划书 §六 防死锁看门狗机制）。
 *
 * 为什么单独一个文件：判定逻辑一旦混进编排器的执行循环就没法测 —— 时序类断言
 * 又不许用固定 delay 赌（AGENTS 测试纪律）。这里全部依赖**注入时钟**：测试拨动
 * 毫秒数即可推进「3 分钟无输出」，一个真实 sleep 都不需要。
 *
 * 与沙箱既有机制的分界（不要重复造）：
 * - agentNode 的沙箱执行器自带**墙钟**止损（整轮时长上限，env 可覆盖）与
 *   D-024 中止序（SIGTERM → 宽限 → SIGKILL，进程树级）——「跑太久」由它管；
 * - 本文件管的是**墙钟管不了的另一件事**：进程活着但长时间不产生任何可观测
 *   推进（挂在交互上、死循环空转）—— 死人开关按 I/O 空闲判，熔断动作仍复用
 *   既有中止序，不另写第二套杀进程的代码。
 */

/**
 * I/O 空闲看门狗（死人开关）。[feed] 是「有生命迹象」的唯一入口 —— 对编排器来说
 * 它就是驱动事件的每一次回调（事件 = 子进程输出管道里确实来了新字节）。
 *
 * 判定三态：推进中（OBSERVE）→ 空闲超租约先发 SIGTERM（TERMINATE）→ 宽限期满
 * 仍在（FORCE_KILL）。终止升级由调用方落实：TERMINATE 时调既有中止序
 * （沙箱 abort 自带 TERM→宽限→KILL 的树级升级），这里只负责「什么时候该动手」。
 */
class SandboxIdleWatchdog(
    private val clock: () -> Long,
    /** 空闲租约：持续无任何新字节的容忍上限（超时卡上的读数如实取它）。 */
    val idleTimeoutMs: Long = idleTimeoutMsOf(),
    /** SIGTERM 之后的宽限：期满仍无推进即升级 FORCE_KILL。 */
    private val graceMs: Long = KILL_GRACE_MS,
) {
    enum class Verdict { OBSERVE, TERMINATE, FORCE_KILL }

    private var lastProgressAt: Long = clock()
    private var termSentAt: Long? = null

    /** 生命迹象：任何来自子进程输出管道的新字节（到达编排器即一次驱动事件）。 */
    fun feed() {
        lastProgressAt = clock()
        termSentAt = null
    }

    fun poll(): Verdict {
        val now = clock()
        if (now - lastProgressAt < idleTimeoutMs) return Verdict.OBSERVE
        val sentAt = termSentAt
        if (sentAt == null) {
            termSentAt = now
            return Verdict.TERMINATE
        }
        return if (now - sentAt >= graceMs) Verdict.FORCE_KILL else Verdict.OBSERVE
    }

    companion object {
        /** 空闲租约默认值：复杂构建的长间隔（依赖安装、无输出的编译段）要盖得住。 */
        const val DEFAULT_IDLE_TIMEOUT_MS = 180_000L

        /** SIGTERM 后等待进程自行退出的宽限。 */
        const val KILL_GRACE_MS = 5_000L

        /** 空闲租约的显式覆盖 env 名（毫秒）。 */
        const val ENV_IDLE_TIMEOUT_MS = "TIDINGS_WATCHDOG_IDLE_MS"

        /**
         * 从 env 解析空闲租约。认不出的值一律回落默认：负数、乱写都不许变成
         * 「立刻熔断」—— 一个配置笔误不该把每次派单都杀掉。
         */
        fun idleTimeoutMsOf(env: Map<String, String> = System.getenv()): Long {
            val raw = env[ENV_IDLE_TIMEOUT_MS]?.trim()?.toLongOrNull()
            return raw?.takeIf { it > 0L } ?: DEFAULT_IDLE_TIMEOUT_MS
        }
    }
}

/** 一次心跳探针的载荷：当前子动作 + 心跳墙钟毫秒。 */
data class WorkerHeartbeat(
    val currentTool: String?,
    val observation: String?,
    val lastBeatTs: Long,
)

/**
 * 心跳节流器：**无变化不发**（§六.1 刷新节流）。变化了也要按最小间隔限流，
 * 否则一次高频工具调用会把聊天通道刷成瀑布 —— 卡片原位刷新省的是渲染，
 * 省不掉的是帧。
 */
class HeartbeatEmitter(
    private val clock: () -> Long = { nowMillis() },
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    private var lastSentAt: Long = Long.MIN_VALUE
    private var lastTool: String? = null
    private var lastObservation: String? = null

    /**
     * 记一次子动作变化。返回非 null = 该发心跳卡了；返回 null = 没有新事实
     * （内容没变，或还在最小间隔内），调用方必须丢弃而不是硬发。
     */
    fun note(tool: String?, observation: String? = null): WorkerHeartbeat? {
        val now = clock()
        val changed = tool != lastTool || observation != lastObservation
        if (!changed) return null
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < minIntervalMs) return null
        lastSentAt = now
        lastTool = tool
        lastObservation = observation
        return WorkerHeartbeat(
            currentTool = tool?.takeIf { it.isNotBlank() },
            observation = observation?.takeIf { it.isNotBlank() },
            lastBeatTs = now,
        )
    }

    companion object {
        /** 探针卡的最小间隔：落在计划要求的 3~5 秒窗内。 */
        const val MIN_INTERVAL_MS = 4_000L
    }
}

/** 崩溃现场抽屉：从累积输出里截**尾部**若干行 —— 结论总在最后。 */
object CrashReport {
    /** 抽屉行数上限：堆栈头部的框架噪声对排障没有增量。 */
    const val DRAWER_TAIL_LINES = 20

    /** 抽屉字符上限：它要进卡片 JSON，预算是 24 KiB 的整卡（见 [OrchestratorCards]）。 */
    const val DRAWER_MAX_CHARS = 1_200

    fun drawerOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.replace("\r\n", "\n").split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val tail = lines.takeLast(DRAWER_TAIL_LINES).joinToString("\n")
        return if (tail.length <= DRAWER_MAX_CHARS) {
            tail
        } else {
            tail.takeLast(DRAWER_MAX_CHARS)
        }
    }

    fun drawerOf(throwable: Throwable): String =
        drawerOf(throwable.message + "\n" + throwable.stackTraceToString())
            ?: (throwable::class.simpleName ?: "crash")
}

/**
 * 打回反馈的**解码侧**。动作帧只有一个 actionId 字符串槽（协议白名单不动），
 * 反馈文本以 `[PREFIX]<文本>` 编码进 actionId；编码侧是 composeApp 卡片层的
 * 同名构造。两侧格式由各自的测试钉住 —— 改一侧必须同步另一侧。
 */
object RevisionFeedback {
    const val PREFIX = "reject-revision|"

    /** 非该前缀的 actionId 返回 null（维持既有路由）；空反馈也算没有反馈。 */
    fun feedbackOf(actionId: String): String? =
        actionId.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * 自愈提示词组装：用户的打回反馈必须以**硬约束前置**进入下一轮 —— 放在 containment
 * 与任务正文之前，让「必须满足什么」成为提示词读到的第一件事，而不是附录。
 */
object WorkerPrompt {
    private const val CONTAINMENT =
        "You are working strictly inside the current workspace repository directory. " +
            "Do NOT inspect, search, or read files outside this repository directory.\n\n"

    fun build(devPrompt: String, constraints: String? = null): String {
        val body = CONTAINMENT + devPrompt
        if (constraints.isNullOrBlank()) return body
        return "HARD CONSTRAINTS from the user review (acceptance criteria; the run is not " +
            "done until every one of them holds):\n" + constraints.trim() + "\n\n" + body
    }
}

/**
 * 工单卡之外的**宿主侧卡片构建器**：心跳探针卡、崩溃失败卡、看门狗超时卡。
 *
 * 三张卡都吃同一份 24 KiB 预算 —— 构建器内部对可变长字段截断，并在结尾校验整卡
 * 字节数（发送侧不产出注定被拒的帧，AGENTS 协议纪律）。超预算在这里抛，调用方的
 * 发布路径把它当失败如实上报 —— 静默截断会把「证据被吃掉」变成没人知道的事。
 */
object OrchestratorCards {
    /** 工单卡的 cardId 前缀：与 [StageChain.cardOf] 同一形状（chain-<ticketId>）。 */
    const val TICKET_CARD_PREFIX = "chain-"

    /** 探针观察文本的截断上限：工具名与参数摘要在百字符级已可读。 */
    const val PROBE_OBSERVATION_MAX_CHARS = 160

    fun heartbeatCardJson(
        ticketId: String,
        rev: Int,
        title: String,
        roleName: String,
        beat: WorkerHeartbeat,
    ): String = guarded {
        buildJsonObject {
            put("cardId", JsonPrimitive(TICKET_CARD_PREFIX + ticketId))
            put("rev", JsonPrimitive(rev))
            put("type", JsonPrimitive("task-board"))
            put("state", JsonPrimitive("working"))
            put("title", JsonPrimitive(title.take(60)))
            put("progress", JsonPrimitive(60))
            put("bot", buildJsonObject {
                put("name", JsonPrimitive(roleName))
                put("bdot", JsonPrimitive("run"))
            })
            put("checks", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("probe"))
                    put("label", JsonPrimitive("live-probe"))
                    put("status", JsonPrimitive("running"))
                    beat.observation?.let { put("observation", JsonPrimitive(it.clamped())) }
                })
            })
            put("lastBeatTs", JsonPrimitive(beat.lastBeatTs))
        }
    }

    fun failedCardJson(
        ticketId: String,
        rev: Int,
        title: String,
        banner: String,
        observation: String?,
        drawer: String?,
        roleName: String,
    ): String = guarded {
        buildJsonObject {
            put("cardId", JsonPrimitive(TICKET_CARD_PREFIX + ticketId))
            put("rev", JsonPrimitive(rev))
            put("type", JsonPrimitive("task-board"))
            put("state", JsonPrimitive("failed"))
            put("title", JsonPrimitive(title.take(60)))
            put("progress", JsonPrimitive(40))
            put("bot", buildJsonObject {
                put("name", JsonPrimitive(roleName))
                put("bdot", JsonPrimitive("fail"))
            })
            put("banner", buildJsonObject {
                put("text", JsonPrimitive(banner.take(300)))
                put("tone", JsonPrimitive("error"))
            })
            put("checks", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("crash"))
                    put("label", JsonPrimitive("worker exit"))
                    put("status", JsonPrimitive("failed"))
                    observation?.let { put("observation", JsonPrimitive(it.clamped())) }
                    drawer?.let { put("drawer", JsonPrimitive(it.clampedDrawer())) }
                })
            })
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("retry"))
                    put("label", JsonPrimitive("Retry"))
                })
            })
        }
    }

    fun timeoutCardJson(
        ticketId: String,
        rev: Int,
        title: String,
        banner: String,
        roleName: String,
    ): String = guarded {
        buildJsonObject {
            put("cardId", JsonPrimitive(TICKET_CARD_PREFIX + ticketId))
            put("rev", JsonPrimitive(rev))
            put("type", JsonPrimitive("task-board"))
            put("state", JsonPrimitive("timeout"))
            put("title", JsonPrimitive(title.take(60)))
            put("progress", JsonPrimitive(40))
            put("bot", buildJsonObject {
                put("name", JsonPrimitive(roleName))
                put("bdot", JsonPrimitive("warn"))
            })
            put("banner", buildJsonObject {
                put("text", JsonPrimitive(banner.take(300)))
                put("tone", JsonPrimitive("warn"))
            })
            put("checks", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("watchdog"))
                    put("label", JsonPrimitive("watchdog"))
                    put("status", JsonPrimitive("blocked"))
                })
            })
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("actionId", JsonPrimitive("retry"))
                    put("label", JsonPrimitive("Retry"))
                })
            })
        }
    }

    /**
     * 在既有工单卡上追加一条 **failed 检查项**（Fail-Loud 抽屉的载体）。
     *
     * 为什么是「追加」而不是重造：blocked 卡的阶段项、banner、diff 都是事实，
     * 崩溃抽屉只是往上面多钉一条证据 —— 丢掉它们等于把线索换成一个光秃秃的状态词。
     */
    fun withFailureCheck(
        base: kotlinx.serialization.json.JsonObject,
        observation: String,
        drawer: String?,
    ): kotlinx.serialization.json.JsonObject {
        val check = buildJsonObject {
            put("id", JsonPrimitive("crash"))
            put("label", JsonPrimitive("worker exit"))
            put("status", JsonPrimitive("failed"))
            put("observation", JsonPrimitive(observation.take(PROBE_OBSERVATION_MAX_CHARS)))
            drawer?.let { put("drawer", JsonPrimitive(it.take(CrashReport.DRAWER_MAX_CHARS))) }
        }
        val existing = base["checks"]
        val checks = if (existing is kotlinx.serialization.json.JsonArray) {
            buildJsonArray { existing.forEach { add(it) }; add(check) }
        } else {
            buildJsonArray { add(check) }
        }
        val json = kotlinx.serialization.json.JsonObject(base.toMap() + ("checks" to checks))
        val bytes = Codec.utf8ByteLength(json.toString())
        check(bytes <= Limits.CARD_BYTES) {
            "worker card exceeds CARD_BYTES: " + bytes + " > " + Limits.CARD_BYTES
        }
        return json
    }

    private fun String.clamped(): String = take(PROBE_OBSERVATION_MAX_CHARS)
    private fun String.clampedDrawer(): String = take(CrashReport.DRAWER_MAX_CHARS)

    /** 整卡字节预算闸门：超 24 KiB 在这里响，而不是在对端静默拒收时才被发现。 */
    private inline fun guarded(build: () -> kotlinx.serialization.json.JsonObject): String {
        val json = build()
        val bytes = Codec.utf8ByteLength(json.toString())
        check(bytes <= Limits.CARD_BYTES) {
            "worker card exceeds CARD_BYTES: " + bytes + " > " + Limits.CARD_BYTES +
                " — clamp its variable fields"
        }
        return json.toString()
    }
}
