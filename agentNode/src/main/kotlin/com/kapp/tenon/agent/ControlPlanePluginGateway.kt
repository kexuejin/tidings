// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.drivers.DriverCooldownTracker
import com.kapp.tenon.agent.drivers.DriverEvent
import com.kapp.tenon.agent.drivers.ExternalPluginDriver
import com.kapp.tenon.agent.plugins.PluginManifest
import com.kapp.tenon.agent.plugins.PluginRegistration
import com.kapp.tenon.agent.plugins.PluginRegistry
import com.kapp.tenon.agent.plugins.TriggerTypes
import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.sameAgent
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 插件网关（M2 宿主网关）。
 *
 * 插件入驻、台账、派单与两条执行通路（插件 HTTP / CLI 驱动）。
 *
 * **为什么它们在一个文件里**：派单的几个入口（`/v1/plugin/task`、托管方的
 * `executePluginTask`、`/v1/agent/hook/dispatch`）最后都落到同一个执行器；`driverFor` 是
 * 插件条目到驱动的唯一换算。把它们分开，改「插件怎么被调用」就要跳三个文件才能确定行为。
 *
 * token 门在自己这一侧：`/v1/plugin/register|unregister` 认的是 **插件 token**，与控制面的
 * `authorized` 是两套体系 —— 分发器里那两条路由刻意排在鉴权之前，别挪。
 */

// ------------------------------------------------------------ 插件注册（M2 宿主网关）
//
// 方案 §2.3：`POST /v1/plugin/register` 是插件的唯一入驻口，**强制**校验
// TENON_PLUGIN_TOKEN（没有它，本机任意进程都能自我入驻并领到任务与工作区授权）。
// 注册通过后插件条目进 [plugins]，宿主按 baseUrl 主动探测 healthz 维护在线状态。

/** 插件注册/注销共用的 token 门：没配（关闸）与不匹配（拒绝）是两个不同的答案。 */
private fun ControlPlane.pluginTokenGuard(exchange: HttpExchange): Boolean {
    if (!pluginTokenGate.isConfigured()) {
        respond(
            exchange,
            503,
            errorJson(
                "plugin-token-unconfigured",
                "plugin registration is disabled: set TENON_PLUGIN_TOKEN or ensure the " +
                    "plugins/plugin-token file under the host data dir (fail-closed by design)",
            ),
        )
        return false
    }
    val presented = presentedPluginToken(exchange)
    if (presented == null) {
        respond(
            exchange,
            401,
            errorJson(
                "unauthorized",
                "plugin registration requires a token: Authorization: Bearer <TENON_PLUGIN_TOKEN> " +
                    "or X-Tenon-Token header",
            ),
        )
        return false
    }
    if (!pluginTokenGate.matches(presented)) {
        respond(exchange, 401, errorJson("unauthorized", "plugin token mismatch"))
        return false
    }
    return true
}

/** 插件 token 的请求头读取：与 [authorized] 同一份头约定（Bearer / X-Tidings-Token）。 */
private fun ControlPlane.presentedPluginToken(exchange: HttpExchange): String? {
    val bearer = exchange.requestHeaders.getFirst("Authorization")
    if (bearer != null) {
        val value = bearer.removePrefix("Bearer ").trim()
        if (value.isNotEmpty()) return value
    }
    return exchange.requestHeaders.getFirst("X-Tidings-Token")?.takeIf { it.isNotEmpty() }
}

internal fun ControlPlane.pluginRegister(exchange: HttpExchange) {
    if (!pluginTokenGuard(exchange)) return
    val obj = readJsonObject(exchange) ?: return
    val manifestElement = obj["manifest"]
    if (manifestElement == null || manifestElement !is JsonObject) {
        respond(
            exchange,
            400,
            errorJson(
                "bad-request",
                "body must be {\"manifest\": {…§2.2…}, \"httpPort\": <1..65535>} " +
                    "(httpPort = the plugin's own loopback IPC port)",
            ),
        )
        return
    }
    val httpPort = numberOrNull(obj["httpPort"])
    if (httpPort == null || httpPort < 1 || httpPort > 65535) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "httpPort must be an integer in 1..65535"),
        )
        return
    }
    val verdict = PluginRegistration.parse(manifestElement, runtime.nodeId)
    val accepted = when (verdict) {
        is PluginRegistration.Verdict.Accepted -> verdict
        is PluginRegistration.Verdict.Rejected -> {
            respond(exchange, 400, errorJson(verdict.error, verdict.message))
            return
        }
    }
    val manifest = accepted.manifest
    // 触发器的宿主级事实（提案 §5.2）：webhook 密钥必须已配置、回投会话必须
    // 本机存在。结构判据在 PluginRegistration.validate，这两道在这里——它们
    // 是宿主状态，不是清单能自证的；缺一道就是「注册收了、触发永不生效」。
    validateTriggerHostFacts(manifest)?.let { rejected ->
        respond(exchange, 400, errorJson(rejected.error, rejected.message))
        return
    }
    // 端口冲突检查：两个插件不能共用同一条回环 IPC（驱动会互相打穿对方的会话）。
    plugins.findByPort(httpPort.toInt())?.let { existing ->
        if (existing.pluginId != manifest.pluginId) {
            respond(
                exchange,
                409,
                errorJson(
                    "port-conflict",
                    "port " + httpPort + " is already claimed by plugin \"" + existing.pluginId + "\"",
                ),
            )
            return
        }
    }
    val baseUrl = "http://" + ControlPlane.LOOPBACK + ":" + httpPort
    val entry = plugins.register(manifest, baseUrl, httpPort.toInt(), accepted.agentSenderIds)
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", manifest.pluginId)
            put("status", entry.status)
            put("baseUrl", baseUrl)
            putJsonArray("agentSenderIds") {
                accepted.agentSenderIds.forEach { add(JsonPrimitive(it)) }
            }
            put(
                "note",
                "registered; the host probes " + baseUrl + "/healthz every " +
                    plugins.probeIntervalMs + "ms and flips status to offline when it stops answering",
            )
        },
    )
}

/**
 * 触发器的宿主级事实校验（提案 §5.2）：webhook 触发的 sourceId 必须已配密钥
 * （未配置即拒——与入站 fail-closed 同一条纪律）；targetConvId 必须本机存在，
 * 且校验器未接线时整体拒绝（fail-closed）。返回 null = 通过。
 */
internal fun ControlPlane.validateTriggerHostFacts(manifest: PluginManifest): PluginRegistration.Verdict.Rejected? {
    if (manifest.triggers.isEmpty()) return null
    manifest.triggers.forEach { trigger ->
        if (trigger.type == TriggerTypes.WEBHOOK) {
            val sourceId = trigger.spec?.sourceId.orEmpty()
            if (sourceWebhooks.getSecret(sourceId) == null) {
                return PluginRegistration.Verdict.Rejected(
                    "secret-not-configured",
                    "webhook trigger \"" + trigger.id + "\" needs a configured secret for source \"" + sourceId +
                        "\"; set TENON_WEBHOOK_SECRET_" + sourceId.replace('-', '_').uppercase() +
                        " (or registerSecret) before registering this plugin",
                )
            }
        }
        val validator = triggerTargetConvKnown
            ?: return PluginRegistration.Verdict.Rejected(
                "target-conv-unverifiable",
                "host has no conversation validator wired; manifests with triggers cannot be accepted " +
                    "(fail-closed: an unverifiable targetConvId would send result cards where nobody sees them)",
            )
        val convId = trigger.dispatch.targetConvId
        if (!validator(convId)) {
            return PluginRegistration.Verdict.Rejected(
                "unknown-target-conv",
                "trigger \"" + trigger.id + "\": targetConvId \"" + convId + "\" does not exist on this host; " +
                    "result cards are delivered via group cards, so only existing group conversations qualify",
            )
        }
    }
    return null
}

internal fun ControlPlane.pluginUnregister(exchange: HttpExchange) {
    if (!pluginTokenGuard(exchange)) return
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (pluginId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "pluginId is required"))
        return
    }
    val removed = plugins.unregister(pluginId)
    if (!removed) {
        respond(exchange, 404, errorJson("not-found", "no registered plugin \"" + pluginId + "\""))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            put("removed", true)
        },
    )
}

internal fun ControlPlane.pluginsList(exchange: HttpExchange) {
    val rows = plugins.list()
    respond(
        exchange,
        200,
        buildJsonObject {
            putJsonArray("plugins") {
                rows.forEach { entry ->
                    addJsonObject {
                        put("pluginId", entry.pluginId)
                        put("name", entry.manifest.name)
                        put("version", entry.manifest.version)
                        put("type", entry.manifest.type)
                        put("leader", entry.manifest.leader?.agentId)
                        put("status", entry.status)
                        put("baseUrl", entry.baseUrl)
                        put("httpPort", entry.httpPort)
                        putJsonArray("capabilities") {
                            entry.manifest.capabilities.forEach { add(JsonPrimitive(it)) }
                        }
                        putJsonArray("agentSenderIds") {
                            entry.agentSenderIds.forEach { add(JsonPrimitive(it)) }
                        }
                        put("registeredAt", entry.registeredAt)
                        entry.lastError?.let { put("lastError", it) }
                    }
                }
            }
            put("total", rows.size)
        },
    )
}

/** 派单/操作共用的条目解析；null = 已回过响应。 */
private fun ControlPlane.pluginEntryOrRespond(exchange: HttpExchange, pluginId: String): PluginRegistry.Entry? {
    if (pluginId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "pluginId is required"))
        return null
    }
    val entry = plugins.get(pluginId)
    if (entry == null) {
        respond(
            exchange,
            404,
            errorJson("plugin-not-found", "no registered plugin \"" + pluginId + "\" (GET /v1/plugins lists what is registered)"),
        )
        return null
    }
    return entry
}

// ------------------------------------------------------- 宿主侧派单（M2 网关执行面）
//
// `POST /v1/plugin/task` 是「宿主把任务交给插件」的唯一入口：Layer 3 的
// 「模拟群内派发任务」走它，群聊桥（@agent → 派单）接线后也走它。与 /v1/send
// 同一信任模型（回环调用方 = 机主），因此不再过 grants；而插件侧写台账需要的
// 身份只有一个来源 —— 注册时算好的 AgentSenderId（本处理器不拼任何前缀）。

internal fun ControlPlane.pluginDispatch(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    val entry = pluginEntryOrRespond(exchange, pluginId) ?: return
    val prompt = obj["prompt"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    if (prompt.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "prompt is required (an empty task text would be rejected by the protocol anyway)"))
        return
    }
    val wsPath = obj["wsPath"]?.jsonPrimitiveOrNull()?.contentOrNull
    val requestedSessionId = obj["sessionId"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    var sessionId: String? = null
    var status = "finished"
    var error: String? = null
    val collected = mutableListOf<JsonObject>()
    // 按住一条处理器线程等**整段**插件任务跑完 —— 这是全文件最长的一次按。
    // 有虚拟线程时只花一条，JDK < 21 的固定池则真的少一条；所以预算按派单租约同档（10 min），
    // 超了由租约机制收场，不在这里杀（杀了就没有卡、没有落账，只剩一个 502）。
    CarrierHold.hold("pluginDispatch.executePluginTask", CarrierHold.PLUGIN_TASK_MS) {
        val outcome = executePluginTask(entry, prompt, wsPath, requestedSessionId) { eventType, cursor, summary, body ->
            collected += buildJsonObject {
                put("cursor", cursor)
                put("eventType", eventType)
                summary?.let { put("summary", it) }
                body?.let { put("body", it) }
            }
        }
        sessionId = outcome.first
        status = outcome.second
        error = outcome.third
    }
    val sid = sessionId
    if (sid == null) {
        respond(
            exchange,
            502,
            errorJson("plugin-unreachable", "cannot open a session with \"" + pluginId + "\": " + (error ?: "unknown")),
        )
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            put("sessionId", sid)
            put("status", status)
            putJsonArray("events") { collected.forEach { add(it) } }
            put("note", "events are recorded in the session ledger (GET /v1/agent/sessions?sessionId=…)")
        },
    )
}

/**
 * 本机插件派单的**唯一实现**：HTTP 的 `/v1/plugin/task` 与 M4 执行桥
 * （`AgentInvokeExecutor` 的 desktopApp 实现）都走它 —— 两处各写一份，
 * 迟早漂成「面板派单有台账、执行桥派单没台账」那种分叉。
 *
 * @return (sessionId, status, error)；sessionId = null 表示会话都开不出来（插件不可达）。
 * [onEvent] 收 (eventType, cursor, summary, body)：cursor 是台账里的**真实**游标
 * （按会话续走，不从头重数）—— HTTP 面要靠它拼回事件行，重数会把上次的事件覆盖掉
 * （PluginDispatchTest 钉的就是它）。body 是**原始 JSON 文本**（卡片事件就是卡片
 * JSON 本身）—— 故意给原语而不是 JsonObject：执行桥住在 desktopApp，那边的编译
 * 类路径上没有 kotlinx-serialization（既有纪律），让它只碰 String，解析留给消费侧。
 */
suspend fun ControlPlane.executePluginTask(
    entry: PluginRegistry.Entry,
    rawPrompt: String,
    wsPath: String?,
    requestedSessionId: String?,
    /** 会话标题（divider 上显示的那个）。缺省才从 prompt 截取 —— prompt 首行可能是
     *  系统前导或 @ 提派 token，当标题就是把 plumbing 摊给用户（真机 divider 出现过
     *  「You are working strictly inside…」），所以**任务路径必须显式传**。 */
    sessionTitle: String? = null,
    /** Tenon 会话键（提案 §9 阶段三对齐键）：审批卡/回卡按它归会话。 */
    convId: String? = null,
    /** 任务产物（沙箱 patch 等）的结构化回调：路径只走这里，不进台账帧体。
     *  排在 [onEvent] **前面** —— 既有调用点的尾部 lambda 必须继续绑到 onEvent。 */
    onArtifact: ((path: String, name: String, bytes: Long, mime: String, sha256: String) -> Unit)? = null,
    onEvent: (eventType: String, cursor: Long, summary: String?, body: String?) -> Unit,
): Triple<String?, String, String?> {
    // 自保门（T-G7，§7.12）：CPU 超阈值拒单避让 —— 如实回因，不静默不排队
    // （排队的任务会在宿主喘过气前堆成新的过载）。
    val cpuLoad = loadProbe?.invoke()
    if (com.kapp.tenon.agent.HostLoadGuard.shouldRefuse(cpuLoad)) {
        return Triple(
            null, "error",
            "host overloaded (cpu=" + ((cpuLoad ?: 0.0) * 100).toInt() + "%) — refusing new tasks politely",
        )
    }
    // §7.7 上下文裁剪（T-G6）：超预算的 prompt 头尾保留 + 如实标注 —— 上游长
    // 上下文（前序日志/大段引用）不原样灌给执行侧。
    val prompt = com.kapp.tenon.core.protocol.TaskPromptBudget.clamp(rawPrompt)
    val driver = driverFor(entry)
    // 会话：接续调用方点名的，或开一个新的。开不出来（插件不可达）→ 如实上抛路径回，
    // 不编一个 sessionId 进台账。
    val sessionId = requestedSessionId ?: run {
        val handle = try {
            driver.openSession(wsPath ?: "", null)
        } catch (t: Throwable) {
            return Triple(null, "error", t.message ?: t::class.simpleName)
        }
        handle.sessionId
    }

    // 会话进台账（busy → done/error），身份字段全部来自注册的 manifest。
    val resolvedTitle = sessionTitle?.takeIf { it.isNotBlank() } ?: prompt.take(80)
    sessions.recordSession(
        nodeId = runtime.nodeId,
        sessionId = sessionId,
        agentKind = entry.pluginId,
        agentId = entry.manifest.leader?.agentId,
        state = AgentSessionStates.BUSY,
        title = resolvedTitle,
        wsPath = wsPath,
        outbound = true,
    )
    // 会话对齐键登记（提案 §9 阶段三）：同 executeCliTask——派单侧知道的事实
    // 必须在这里落账，hook 审批才有会话归属可查。
    convId?.let { sessions.bindConversation(sessionId, it) }

    // 游标按会话续走：台账按游标排序、同游标就地替换 —— 每次派单从 1 重数会把
    // 上一次的事件覆盖掉（测试 PluginDispatchTest.consecutiveDispatches 钉的就是它）。
    val cursor = pluginEventCursors.computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
    var endedInError = false

    driver.executeTask(sessionId, prompt) { event ->
        val (eventType, summary, body) = when (event) {
            is DriverEvent.ThoughtDelta -> {
                liveAgentEvents.tryEmit(LiveAgentEvent(runtime.nodeId, sessionId, "thought.delta", event.delta, System.currentTimeMillis()))
                Triple("thought.delta", null, event.delta)
            }
            is DriverEvent.TokenDelta -> {
                liveAgentEvents.tryEmit(LiveAgentEvent(runtime.nodeId, sessionId, "token.delta", event.delta, System.currentTimeMillis()))
                Triple("token.delta", null, event.delta)
            }
            is DriverEvent.ToolCall -> Triple("tool.started", event.tool, event.inputJson)
            is DriverEvent.Card -> Triple("card", null, event.cardJson)
            is DriverEvent.Artifact -> {
                // 产物（T-3b）：结构化回调给桥（桌面执行桥拿路径发群文件），台账里
                // 落展示事实（name + sha）+ **本机路径** —— 路径不进协议帧，但
                // 合流动作（merge-patch）要在本机按它找回 patch 文件。
                onArtifact?.invoke(event.path, event.name, event.bytes, event.mime, event.sha256)
                Triple(
                    "artifact", event.name,
                    "sha256=" + event.sha256 + " bytes=" + event.bytes + " path=" + event.path,
                )
            }
            is DriverEvent.Finished -> Triple("finished", event.finishReason, null)
            // 被动限流感知：只有真报了 429 / 配额耗尽才记冷却，普通错误不误伤名册。
            is DriverEvent.Error -> {
                DriverCooldownTracker.Shared.captureIfRateLimited(entry.pluginId, event.message)
                Triple("error", null, event.message)
            }
        }
        val c = cursor.incrementAndGet()
        sessions.recordEvent(
            nodeId = runtime.nodeId,
            sessionId = sessionId,
            cursor = c,
            eventType = eventType,
            summary = summary,
            body = body,
        )
        onEvent(eventType, c, summary, body)
        if (event is DriverEvent.Error) endedInError = true
    }

    sessions.recordSession(
        nodeId = runtime.nodeId,
        sessionId = sessionId,
        agentKind = entry.pluginId,
        agentId = entry.manifest.leader?.agentId,
        state = if (endedInError) AgentSessionStates.ERROR else AgentSessionStates.DONE,
        title = resolvedTitle,
        wsPath = wsPath,
        outbound = true,
    )
    return Triple(sessionId, if (endedInError) "error" else "finished", null)
}

/**
 * 摘掉本机所有在跑的沙箱任务（入站「停」的确定性落点），返回个数。
 *
 * 为什么要这一条而不是只靠取消协程：取消是"请它自己停"，而 CLI 可能卡在阻塞读上；
 * 真机实测：卡片已经翻成 `Aborted by user request`，同一个沙箱里的
 * `codex exec` 七分钟后仍在跑。这条直接对 `Process` 动手，是「停」的最后一道。
 */
suspend fun ControlPlane.abortRunningSandboxes(): Int = cliDrivers?.abortRunningSandboxes() ?: 0

/** 本机当前有没有在跑的沙箱任务（决定入站「停」是不是该被消费）。 */
fun ControlPlane.hasRunningSandboxes(): Boolean = (cliDrivers?.runningSandboxCount() ?: 0) > 0

/** §9 StopAck 的「还剩几个」读数（[hasRunningSandboxes] 的计数版，同一事实源）。 */
fun ControlPlane.runningSandboxes(): Int = cliDrivers?.runningSandboxCount() ?: 0

/**
 * E1：群内派单的 **CLI 驱动解析** —— 用 agent 键去匹配已注册驱动的 `driverId`。
 *
 * 别名口径**不在这里定义**：`DriverKeys.driverIdAliases` 是唯一来源（键本身 /
 * `键-cli` / 去掉 `-cli` 再拼 / `antigravity → agy-cli`），岗位档案的
 * `AgentProfiles.normalizeKey` 读的是同一个对象。两处各写一份的症状是
 * 「派单跑得到、查档案查不到」，而两边都能编译、都不会报错。
 * 找不到 = null（调用方走插件路径或 no-plugin）。
 */
fun ControlPlane.resolveCliDriver(agentKey: String): com.kapp.tenon.agent.drivers.AgentDriver? {
    val aliases = com.kapp.tenon.agent.drivers.DriverKeys.driverIdAliases(agentKey)
    if (aliases.isEmpty() || cliDrivers == null) return null
    return cliDrivers!!.listDrivers().firstOrNull { driver -> driver.driverId in aliases }
}

/**
 * E1：CLI 驱动任务执行 —— 与 [executePluginTask] 共享同一套事件映射、台账、
 * 实时流与产物回调（两处各写一份迟早漂）。差异只在驱动来源：插件经 HTTP
 * 桥，CLI 是本机子进程（沙箱语义在驱动内）。
 */
suspend fun ControlPlane.executeCliTask(
    agentKey: String,
    prompt: String,
    wsPath: String?,
    requestedSessionId: String?,
    /** 会话标题（divider 上显示的那个）。缺省才从 prompt 截取 —— prompt 首行可能是
     *  系统前导或 @ 提派 token，当标题就是把 plumbing 摊给用户，任务路径必须显式传。 */
    sessionTitle: String? = null,
    /** Tenon 会话键（提案 §9 阶段三对齐键）：审批卡/回卡按它归会话。 */
    convId: String? = null,
    /**
     * 只读通道的**执行前**工具白名单（提案 §7.1 第 3 条）：null = 不设限（普通派单）。
     *
     * 登记在这个会话上、由 hook 审批路由读 —— 装了 hook 的 CLI 在工具执行**之前**
     * 会问一次，那一次才有「拒绝」。没有 hook 的驱动照旧只走事后判负（`tool.started`），
     * 所以传了它不等于「写盘不可能」，只是把能拦的那部分拦在落地之前。
     */
    toolPolicy: ((String?) -> Boolean)? = null,
    onArtifact: ((path: String, name: String, bytes: Long, mime: String, sha256: String) -> Unit)? = null,
    onEvent: (eventType: String, cursor: Long, summary: String?, body: String?) -> Unit,
): Triple<String?, String, String?> {
    val driver = resolveCliDriver(agentKey)
        ?: return Triple(null, "error", "no cli driver for \"$agentKey\"")
    val cpuLoad = loadProbe?.invoke()
    if (com.kapp.tenon.agent.HostLoadGuard.shouldRefuse(cpuLoad)) {
        return Triple(
            null, "error",
            "host overloaded (cpu=" + ((cpuLoad ?: 0.0) * 100).toInt() + "%) — refusing new tasks politely",
        )
    }
    val clipped = com.kapp.tenon.core.protocol.TaskPromptBudget.clamp(prompt)
    val resolvedTitle = sessionTitle?.takeIf { it.isNotBlank() } ?: clipped.take(80)
    val sessionId = requestedSessionId ?: run {
        val handle = try {
            driver.openSession(wsPath ?: "", null)
        } catch (t: Throwable) {
            return Triple(null, "error", t.message ?: t::class.simpleName)
        }
        handle.sessionId
    }
    sessions.recordSession(
        nodeId = runtime.nodeId,
        sessionId = sessionId,
        agentKind = driver.driverId,
        agentId = null,
        state = com.kapp.tenon.core.protocol.AgentSessionStates.BUSY,
        title = resolvedTitle,
        wsPath = wsPath,
        outbound = true,
    )
    // 会话对齐键登记（提案 §9 阶段三）：hook 审批带真实 sessionId 来时经
    // conversationOf 反查，审批卡才有会话归属——派单侧知道的事实必须在这里落账。
    convId?.let { sessions.bindConversation(sessionId, it) }
    // 只读通道的执行前判据（§7.1 第 3 条）：**起进程之前**登记 —— 首个 PreToolUse
    // 到达时这条必须已经在表里，晚一步就等于又退回「事后判负」。`finally` 里必清：
    // 不清会把「只读」泄漏给同 id 的下一次任务。
    val readOnly = toolPolicy?.let { ReadOnlyToolPolicy(it) }
    if (readOnly != null) readOnlyToolPolicies[sessionId] = readOnly
    val cursor = pluginEventCursors.computeIfAbsent(sessionId) { java.util.concurrent.atomic.AtomicLong(0) }
    var endedInError = false
    try {
        driver.executeTask(sessionId, clipped) { event ->
            val (eventType, summary, body) = when (event) {
                is com.kapp.tenon.agent.drivers.DriverEvent.ThoughtDelta -> {
                    liveAgentEvents.tryEmit(LiveAgentEvent(runtime.nodeId, sessionId, "thought.delta", event.delta, System.currentTimeMillis()))
                    Triple("thought.delta", null, event.delta)
                }
                is com.kapp.tenon.agent.drivers.DriverEvent.TokenDelta -> {
                    liveAgentEvents.tryEmit(LiveAgentEvent(runtime.nodeId, sessionId, "token.delta", event.delta, System.currentTimeMillis()))
                    Triple("token.delta", null, event.delta)
                }
                is com.kapp.tenon.agent.drivers.DriverEvent.ToolCall -> Triple("tool.started", event.tool, event.inputJson)
                is com.kapp.tenon.agent.drivers.DriverEvent.Card -> Triple("card", null, event.cardJson)
                is com.kapp.tenon.agent.drivers.DriverEvent.Artifact -> {
                    onArtifact?.invoke(event.path, event.name, event.bytes, event.mime, event.sha256)
                    Triple("artifact", event.name, "sha256=" + event.sha256 + " bytes=" + event.bytes + " path=" + event.path)
                }
                is com.kapp.tenon.agent.drivers.DriverEvent.Finished -> Triple("finished", event.finishReason, null)
                // 被动限流感知：与插件通路同一份台账实例，宿主内读写一致。
                is com.kapp.tenon.agent.drivers.DriverEvent.Error -> {
                    DriverCooldownTracker.Shared.captureIfRateLimited(agentKey, event.message)
                    Triple("error", null, event.message)
                }
            }
            val c = cursor.incrementAndGet()
            sessions.recordEvent(
                nodeId = runtime.nodeId,
                sessionId = sessionId,
                cursor = c,
                eventType = eventType,
                summary = summary,
                body = body,
            )
            onEvent(eventType, c, summary, body)
            if (event is com.kapp.tenon.agent.drivers.DriverEvent.Error) endedInError = true
        }
    } finally {
        // 只摘**自己那一条**（两参 remove）：同一 sessionId 若被并发派单两次，
        // 后来者已经换掉了表里的值，按 key 删会把它的判据一起摘掉。
        if (readOnly != null) readOnlyToolPolicies.remove(sessionId, readOnly)
    }
    // 只读通道的判负（§7.1 第 3 条）：hook 在工具落地**之前**就拒了，越界的调用因此
    // 没有执行 —— 但「这条只读通道被试图写盘」必须让派单方知道。判负口径与事后那一半
    // 一致（越界即失败卡），不因为拦住了就当没发生：静默放过会让结果卡看起来一切正常，
    // 而任务其实跑在一个已经跑偏的指令上。台账里落一条 error，手机侧看到的失败原因
    // 与结果卡上的是同一句。
    val readOnlyDenial = readOnly?.deniedTool()
        ?.let { "read-only channel denied tool \"" + it + "\"; task aborted" }
    if (readOnlyDenial != null) {
        endedInError = true
        val c = cursor.incrementAndGet()
        sessions.recordEvent(
            nodeId = runtime.nodeId,
            sessionId = sessionId,
            cursor = c,
            eventType = "error",
            summary = null,
            body = readOnlyDenial,
        )
        onEvent("error", c, null, readOnlyDenial)
    }
    sessions.recordSession(
        nodeId = runtime.nodeId,
        sessionId = sessionId,
        agentKind = driver.driverId,
        agentId = null,
        state = if (endedInError) com.kapp.tenon.core.protocol.AgentSessionStates.ERROR else com.kapp.tenon.core.protocol.AgentSessionStates.DONE,
        title = resolvedTitle,
        wsPath = wsPath,
        outbound = true,
    )
    return Triple(sessionId, if (endedInError) "error" else "finished", readOnlyDenial)
}

/**
 * 按 agentId 找**本机**承接该 agent 的插件：leader 的 agentId 优先，组员兜底。
 *
 * 匹配用 **core 的 [com.kapp.tenon.core.protocol.sameAgent]**（大小写不敏感 +
 * 「截到 `-` 之前」的精度容忍），不能用 [com.kapp.tenon.core.protocol.AgentSenderId.slugForSenderId]
 * 当身份匹配器：它的职责是 **senderId 的字段安全**（只留 a-z0-9，其余折 `-`），
 * 大写字母会被它整个抹掉 —— 真机现场：老席位声明的 agentId 是 `DSH`（工作区 kind
 * 的大小写原样入驻），slug 归一后是**空串**，插件明明在线却答 no-plugin。
 * 协议层为「入驻写 `DSH`、会话报 `dsh-029b`」这类精度差专门定了 [sameAgent]
 * —— 身份匹配只有这一个来源。
 */
fun ControlPlane.pluginForAgent(agentId: String): PluginRegistry.Entry? {
    val wanted = agentId.trim()
    if (wanted.isEmpty()) return null
    val online = plugins.list().filter { it.status == "online" }
    return online.firstOrNull { entry ->
        entry.manifest.leader?.agentId?.let { com.kapp.tenon.core.protocol.sameAgent(it, null, wanted) } == true
    } ?: online.firstOrNull { entry ->
        entry.manifest.members.any { com.kapp.tenon.core.protocol.sameAgent(it.agentId, null, wanted) }
    }
}

internal fun ControlPlane.pluginAction(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    val entry = pluginEntryOrRespond(exchange, pluginId) ?: return
    val sessionId = obj["sessionId"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    val actionId = obj["actionId"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    val decidedBy = obj["decidedBy"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    if (sessionId.isEmpty() || actionId.isEmpty() || decidedBy.isEmpty()) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "action needs pluginId / sessionId / actionId / decidedBy (the host needs to know who decided)"),
        )
        return
    }
    try {
        CarrierHold.hold("pluginAction", CarrierHold.PLUGIN_MS) {
            pluginAction(entry, sessionId, actionId, decidedBy)
        }
    } catch (t: Throwable) {
        respond(
            exchange,
            502,
            errorJson("plugin-unreachable", "action could not reach \"" + pluginId + "\": " + (t.message ?: (t::class.simpleName ?: "unknown"))),
        )
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            put("sessionId", sessionId)
            put("actionId", actionId)
        },
    )
}

/**
 * 卡片动作进插件的**唯一实现**：HTTP 的 `/v1/plugin/action` 与 M4 宿主动作桥
 * （`onHostCardAction` 的 desktopApp 实现）都走它 —— 与 [executePluginTask]
 * 同一条「两处各写一份迟早漂」的对账纪律。动作不可达时抛给调用方如实处理。
 */
suspend fun ControlPlane.pluginAction(entry: PluginRegistry.Entry, sessionId: String, actionId: String, decidedBy: String) {
    driverFor(entry).handleAction(sessionId, actionId, decidedBy)
}

/**
 * 这个 agent 最近一次会话（卡片动作没有会话上下文时的解析来源）：动作必须落在
 * 会话上，而卡片帧里不带 sessionId（它是群流上的展示物）。按 [pluginForAgent]
 * 解析出的插件找**它名下**最近更新的会话；找不到 ⇒ null，转发方据此如实放弃 ——
 * 不编一个 id 给插件。
 */
fun ControlPlane.latestSessionForAgent(agentId: String): AgentSessionRegistry.SessionRecord? {
    val entry = pluginForAgent(agentId) ?: return null
    return sessions.sessions()
        .filter { it.nodeId == runtime.nodeId && it.agentKind == entry.pluginId }
        .maxByOrNull { it.updatedAt }
}

/**
 * 岗位档案的 **record 信号**（G1，真机实测）：本机 CLI 驱动**最近一次会话**
 * 的终态（done / error / busy…）。真机现场：LLM planner 把审核棒指给了未登录的
 * claude —— 二进制在位 ≠ 健康，链走到一半才死。「最近一次会话是 error」是当下
 * 唯一廉价可得的健康事实，选棒方（启发式 / planner 名册）据此回避；
 * 没有会话史 ⇒ null = 无从判死，不冤枉。
 */
fun ControlPlane.recentCliSessionState(agentKey: String): String? {
    val driver = resolveCliDriver(agentKey) ?: return null
    return sessions.sessions()
        .filter { it.nodeId == runtime.nodeId && it.agentKind == driver.driverId && it.state != null }
        .maxByOrNull { it.updatedAt }?.state
}

private fun ControlPlane.driverFor(entry: PluginRegistry.Entry): ExternalPluginDriver =
    ExternalPluginDriver(
        driverId = entry.pluginId,
        displayName = entry.manifest.name,
        baseUrl = entry.baseUrl,
        manifest = entry.manifest,
        onConnectionLost = { reason -> plugins.markOffline(entry.pluginId, reason) },
    )
