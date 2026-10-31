// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.kapp.tidings.core.protocol.CodecWire.boolOf
import com.kapp.tidings.core.protocol.CodecWire.hasOnlyKeys
import com.kapp.tidings.core.protocol.CodecWire.intOf
import com.kapp.tidings.core.protocol.CodecWire.isStr
import com.kapp.tidings.core.protocol.CodecWire.isStrAllowEmpty
import com.kapp.tidings.core.protocol.CodecWire.utf8ByteLength
import com.kapp.tidings.core.protocol.CodecWire.wireJson

/**
 * M7-T2（决策包 §1.4）：`agent-channel` 的 op 两态。**封闭取值、全仓唯一来源** ——
 * 校验器（收端）、构造器（发端）、编排器（互斥台账）三处引用同一个对象；
 * 谁在别处手写第二份清单，第 7 种「没收到」就从那里长出来（AGENTS.md §3 #7）。
 */
object ChannelOps {
    /** 子任务委派，**所有权保留**：调用方挂起等结果，接收方完成后经 result 帧唤醒。 */
    const val ASSIGN = "assign"

    /** 完整任务移交，**所有权转移**：调用方彻底退出，接收方成为新所有者。 */
    const val TRANSFER = "transfer"

    val ALL: Set<String> = setOf(ASSIGN, TRANSFER)
}

/**
 * `targetTag` 的形状谓词（唯一来源）：`[a-z0-9][a-z0-9._-]*`，≤ [Limits.AGENT_CHANNEL_TAG]。
 * 标签是**能力声明**（`gpu` / `docker-sandbox` 这类），路由到「有能力的那台」而不是写死 ID。
 *
 * `model:` 前缀族（D-030，方案 §2.5「模型即能力标签」）：`model:claude` /
 * `model:deepseek-v4` 这类模型标签 —— 冒号**只认这一个前缀**（不开放任意冒号，
 * 避免标签空间被任意 namespace 撑爆）；前缀后的后缀走同一字符集。
 */
object ChannelTags {
    const val MODEL_PREFIX = "model:"

    fun isWellFormed(value: String): Boolean {
        if (value.length !in 1..Limits.AGENT_CHANNEL_TAG) return false
        return if (value.startsWith(MODEL_PREFIX)) {
            // model: 后缀：同一字符集但**不含冒号**（嵌套 namespace 不开放），
            // 首字符不限小写字母 —— 模型版本号可能以数字开头。
            val suffix = value.drop(MODEL_PREFIX.length)
            suffix.isNotEmpty() &&
                suffix.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
        } else {
            value.first() in 'a'..'z' &&
                value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
        }
    }
}

/**
 * `resources` 的取值域 + 唯一谓词（决策包 §1.4）：固定 token `browser` / `gpu`
 * （来自 OpenBot channel-tools 语义）+ 前缀族 `workspace:<绝对路径>`（路径 ≤
 * [Limits.AGENT_WS_PATH] 且以 `/` 开头）。声明同一资源的任务在接收宿主侧串行化。
 */
object ChannelResources {
    const val BROWSER = "browser"
    const val GPU = "gpu"
    private const val WORKSPACE_PREFIX = "workspace:"

    fun isWellFormed(value: String): Boolean = when {
        value == BROWSER || value == GPU -> true
        value.startsWith(WORKSPACE_PREFIX) -> {
            val path = value.removePrefix(WORKSPACE_PREFIX)
            path.isNotEmpty() && path.length <= Limits.AGENT_WS_PATH && path.startsWith("/")
        }
        else -> false
    }
}

/**
 * M7-T2（决策包 §1）：跨宿主 Channel 委派的编解码（cap `agc1`）。先例即
 * [CodecInvoke]——两种职责不共用一帧，op 两态互斥的判法照抄 `validateInvoke`。
 *
 * 纪律与 `AgentAckKinds` 的教训逐字相同：收端校验、发端构造、编排器互斥台账，
 * 三处引用同一份 [ChannelOps] / [ChannelResources] / [ChannelTags]。
 */
object CodecChannel {

    internal val CHANNEL_EXPECTED_KEYS = setOf(
        "channelId", "op", "fromAgent", "toAgent", "targetTag", "task", "expectedResult",
        "resources", "tools", "sourceMessageIds", "deadlineMs", "sessionId", "groupId", "groupRev", "at",
    )

    /**
     * `tools`（固定层工具声明，§7.7 / D-034）：发起方告诉执行侧「本任务只许用这些
     * 工具」。形状 = 小写 snake/点 id，≤64 字符（与 CLI 的 --allowedTools 词法同族）。
     * 执行侧消费是插件/驱动自己的责任（协议只管形状与传递）。
     */
    object ChannelTools {
        fun isWellFormed(value: String): Boolean =
            value.length in 1..64 &&
                value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    }

    internal val RESULT_EXPECTED_KEYS = setOf("channelId", "ok", "status", "summary", "artifactIds", "error", "at")

    internal fun validateChannel(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, CHANNEL_EXPECTED_KEYS)) return false
        if (!isStr(o["channelId"], Limits.AGENT_RUN_ID)) return false
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (op !in ChannelOps.ALL) return false
        if (!isStr(o["fromAgent"], Limits.AGENT_GRANT_AGENT_KEY)) return false
        // toAgent ⊕ targetTag 恰一：指名与按标签路由互斥。
        val toAgent = (o["toAgent"] as? JsonPrimitive)?.contentOrNull
        val targetTag = (o["targetTag"] as? JsonPrimitive)?.contentOrNull
        if ((toAgent != null) == (targetTag != null)) return false
        if (toAgent != null && !isStr(o["toAgent"], Limits.AGENT_GRANT_AGENT_KEY)) return false
        if (targetTag != null && !ChannelTags.isWellFormed(targetTag)) return false
        val task = (o["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (task.isNullOrEmpty() || utf8ByteLength(task) > Limits.AGENT_PROMPT) return false
        if (o.containsKey("expectedResult") && !isStrAllowEmpty(o["expectedResult"], Limits.AGENT_NOTE)) return false
        if (o.containsKey("resources")) {
            val arr = o["resources"] as? JsonArray ?: return false
            if (arr.size > Limits.AGENT_CHANNEL_RESOURCES_MAX) return false
            for (item in arr) {
                val res = (item as? JsonPrimitive)?.contentOrNull ?: return false
                if (!ChannelResources.isWellFormed(res)) return false
            }
        }
        if (o.containsKey("sourceMessageIds")) {
            val arr = o["sourceMessageIds"] as? JsonArray ?: return false
            if (arr.size > Limits.AGENT_CHANNEL_SOURCES_MAX) return false
            for (item in arr) {
                if (!isStr(item, Limits.ID)) return false
            }
        }
        if (o.containsKey("deadlineMs")) {
            val deadline = intOf(o["deadlineMs"]) ?: return false
            if (deadline <= 0) return false
        }
        if (o.containsKey("sessionId") && !isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (o.containsKey("groupId") && !isStr(o["groupId"], Limits.ID)) return false
        if (o.containsKey("groupRev")) {
            val rev = intOf(o["groupRev"]) ?: return false
            if (rev < 0) return false
        }
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return when (op) {
            ChannelOps.ASSIGN ->
                // assign 必须带租约（「没有期限的派单等于永远等」）；expectedResult 允许。
                (intOf(o["deadlineMs"]) ?: 0) > 0
            ChannelOps.TRANSFER ->
                // transfer 禁三样：租约/验收标准/按标签——所有权已整体交出。
                !o.containsKey("deadlineMs") && !o.containsKey("expectedResult") &&
                    !o.containsKey("targetTag") && toAgent != null
            else -> false // op ∈ ChannelOps.ALL 已在上面验过；这里恒假只是编译器的穷举要求
        }
    }

    internal fun validateChannelResult(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, RESULT_EXPECTED_KEYS)) return false
        if (!isStr(o["channelId"], Limits.AGENT_RUN_ID)) return false
        val ok = boolOf(o["ok"]) ?: return false
        val status = (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (status !in CodecInvoke.InvokeResultStates.ALL) return false
        // 自洽判法照抄 validateInvokeResult：ok ⟺ done，混写一律拒。
        if (ok != (status == CodecInvoke.InvokeResultStates.DONE)) return false
        if (o.containsKey("summary") && !isStrAllowEmpty(o["summary"], Limits.AGENT_INVOKE_SUMMARY)) return false
        if (o.containsKey("artifactIds")) {
            val arr = o["artifactIds"] as? JsonArray ?: return false
            if (arr.size > Limits.AGENT_CHANNEL_RESOURCES_MAX) return false
            for (item in arr) {
                if (!isStr(item, Limits.AGENT_ARTIFACT_ID)) return false
            }
        }
        if (o.containsKey("error") && !isStrAllowEmpty(o["error"], Limits.AGENT_NOTE)) return false
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return true
    }

    /**
     * 构造 `agent-channel` 负载。**发送侧不产出注定被拒的帧**：op / resources /
     * targetTag 在这里先过一遍同一份谓词，op 两态的互斥在构造期就断言 —— 违反即
     * `require` 崩在发送侧，而不是帧到对端被拒后变「静默丢失」。
     */
    fun channelPayload(
        channelId: String,
        op: String,
        fromAgent: String,
        task: String,
        toAgent: String? = null,
        targetTag: String? = null,
        expectedResult: String? = null,
        resources: List<String>? = null,
        tools: List<String>? = null,
        sourceMessageIds: List<String>? = null,
        deadlineMs: Long? = null,
        sessionId: String? = null,
        groupId: String? = null,
        groupRev: Long? = null,
        at: Long? = null,
    ): JsonElement {
        require(op in ChannelOps.ALL) { "unknown channel op \"$op\"" }
        require(resources == null || resources.all { ChannelResources.isWellFormed(it) }) {
            "resources 里有不在取值域内的项"
        }
        require(tools == null || tools.all { ChannelTools.isWellFormed(it) }) {
            "tools 里有形状不合法的项"
        }
        require(targetTag == null || ChannelTags.isWellFormed(targetTag)) { "targetTag 形状不合法" }
        when (op) {
            ChannelOps.ASSIGN -> require(deadlineMs != null && deadlineMs > 0) { "assign 必须带正的 deadlineMs" }
            ChannelOps.TRANSFER -> {
                require(deadlineMs == null && expectedResult == null && targetTag == null) {
                    "transfer 禁止携带 deadlineMs / expectedResult / targetTag"
                }
                require(toAgent != null) { "transfer 必须 toAgent 指名" }
            }
        }
        return wireJson.encodeToJsonElement(
            AgentChannelPayload.serializer(),
            AgentChannelPayload(
                channelId = channelId,
                op = op,
                fromAgent = fromAgent,
                toAgent = toAgent,
                targetTag = targetTag,
                task = task,
                expectedResult = expectedResult,
                resources = resources,
                tools = tools,
                sourceMessageIds = sourceMessageIds,
                deadlineMs = deadlineMs,
                sessionId = sessionId,
                groupId = groupId,
                groupRev = groupRev,
                at = at,
            ),
        )
    }

    /** 构造 `agent-channel-result` 负载；`ok ⟺ done` 的自洽在这里就断言。 */
    fun channelResultPayload(
        channelId: String,
        ok: Boolean,
        status: String,
        summary: String? = null,
        artifactIds: List<String>? = null,
        error: String? = null,
        at: Long? = null,
    ): JsonElement {
        require(status in CodecInvoke.InvokeResultStates.ALL) { "unknown result status \"$status\"" }
        require(ok == (status == CodecInvoke.InvokeResultStates.DONE)) { "ok 与 status 必须自洽" }
        return wireJson.encodeToJsonElement(
            AgentChannelResultPayload.serializer(),
            AgentChannelResultPayload(
                channelId = channelId,
                ok = ok,
                status = status,
                summary = summary,
                artifactIds = artifactIds,
                error = error,
                at = at,
            ),
        )
    }
}
