// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手机端的诊断包：与桌面茶话间同一套骨架（`summary.txt` + `environment.json` + `logs/<name>.jsonl`），
 * 由本仓自己生成。
 *
 * ## 为什么要一个包，而不是再加一个 `run-as cat` 的文件
 *
 * 现状是排查要靠 `adb shell run-as` 一条条 cat `files/relay/diag/link.json` /
 * `stores/queue.log` / `stores/messages.log` —— 用户没有 adb，报告问题的人只能描述
 * "它就是不工作"。诊断包把同一批读数打成一个 zip 交给系统分享面板 / 保存，用户点一下
 * 就能把它发出来。桌面端早就有这条出口，手机端一直没有。
 *
 * ## 边界（与桌面端一致）
 *
 * - **不含聊天正文与文件内容**：信封只保留结构性字段（`id`/`type`/`from`/`ts`/payload 的
 *   `kind`），`payload.text`、文件名、路径一律不落包；认不出信封结构的行**整行丢弃**，
 *   不做"尽量保留"的猜测。
 * - **有上限**：每份日志按行数 + 字节数双限，保留最新的一段；源文件缺失时在 summary 里
 *   如实写 missing，不编空文件。
 * - 日志行数/字节数的统计进 summary，读包的人能看出"这份是不是被截过"。
 *
 * ## 为什么这个文件只写英文
 *
 * `summary.txt` 是给维护者读的日志附件，不是界面文案；而生产源码里的硬编码中文会被
 * `:cjkLiteralGate` 判红（用户可见文案必须进 `composeResources/values*`）。界面上的
 * 按钮/提示语仍然走资源文件，这里不重复一遍。测试文件里可以自由写中文。
 */
internal object DiagnosticsPackage {

    /** 每个日志保留的最大行数（最新的在最后）。 */
    const val MAX_LOG_LINES: Int = 2_000

    /** 每个日志保留的最大字节数。messages.log 在真机上到过十几 MB，必须按字节限。 */
    const val MAX_LOG_BYTES: Int = 256 * 1024
}

/** 一个 zip 条目。`path` 用 '/' 分隔、相对包根。 */
internal data class DiagnosticsEntry(val path: String, val bytes: ByteArray) {
    // ByteArray 的 equals 是引用比较；这里让测试的集合断言有意义（同内容即相同）。
    override fun equals(other: Any?): Boolean =
        this === other || (other is DiagnosticsEntry && path == other.path && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()
}

/** 一个对端的摘要（只放身份与连通性，不放聊天内容）。 */
internal data class DiagPeer(
    val nodeId: String,
    val nick: String,
    val platform: String,
    val online: Boolean,
    val isAgent: Boolean,
    val tcpPort: Int,
    /**
     * 这个对端**声明过的全部地址**（LAN / IPv6 / Tailscale / ZeroTier），来自它的档案。
     *
     * ## 为什么诊断包里必须有它
     *
     * 排查「连不上」时最先要回答的是**「对端到底有哪些地址、本机在试哪一个」**。没有这
     * 两个字段时只能去 `stores/known-peers.json` 手工反推 —— 那正是一次 VPN 失联排查里
     * 做过的事：反复读那个文件、比对 peers 列表、再猜本机在拨谁。
     *
     * **空列表是有信息量的**：说明对端版本还不会宣告地址（`Profile.addresses` 是后加的
     * 可选键），那种情况下只能靠「发现包的源地址」那一条，换网就会失联。
     */
    val addresses: List<String> = emptyList(),
    /**
     * 本机最近一次**尝试拨号**的目标（`host:port`）；null = 还没拨过或对端在线。
     *
     * 它与 [addresses] 是两件事：那个说「有哪些可选」，这个说「选了哪个、为什么失败」。
     * 只有前者时看不出「明明有 IPv6 候选却没试」这类问题。
     */
    val dialTarget: String? = null,
)

/** `environment.json` 的输入。都是原语，便于从 UI 状态直接投影而不把 UI 模型拖进导出逻辑。 */
internal data class DiagnosticsEnvironment(
    val appVersion: String,
    val nodeId: String,
    val deviceNick: String,
    val transport: String,
    /** 用于广播 / 配对码 / 同网段判断的那一份（**刻意窄**，不含隧道口）。 */
    val localAddresses: List<String>,
    /**
     * 实际**宣告**给对端的那一份（含 Tailscale / ZeroTier 隧道口），默认同 [localAddresses]。
     *
     * 排查「连不上」时必须看这一份：窄列表里有对方网段、宽列表里没有，正说明那台设备
     * 在 VPN 上才可达 —— 只看窄列表会把排查方向带错。
     */
    val advertisedAddresses: List<String> = localAddresses,
    val vpnActive: Boolean,
    val peerTotal: Int,
    val peerOnline: Int,
    val pending: Int,
    val persistMessages: Boolean,
    val peers: List<DiagPeer>,
)

/** 一份日志的脱敏结果与统计。 */
internal data class SanitizedLog(
    val text: String,
    val kept: Int,
    val dropped: Int,
    val truncated: Boolean,
)

/** 生成好的诊断包：条目 + 人可读的 summary（summary 本身也是条目之一）。 */
internal data class DiagnosticsBundle(
    val entries: List<DiagnosticsEntry>,
    val summary: String,
)

/**
 * 组装诊断包（纯函数：不读盘、不看时钟，全部输入显式传入 —— 这样单测能钉住"包里有哪几个
 * 条目、每条里有什麼"）。
 *
 * [linkJson] 是 `diag/link.json` 的原文；它只含链路读数（nodeId/地址/closeCount/lastError），
 * 不含聊天内容，原样收进包。其余两份是 `stores/` 下的 append-only 日志，必须先脱敏。
 */
internal fun buildDiagnosticsBundle(
    environment: DiagnosticsEnvironment,
    linkJson: String?,
    queueLog: String?,
    messagesLog: String?,
    generatedAtMillis: Long,
): DiagnosticsBundle {
    val queue = sanitizeQueueLog(queueLog)
    val messages = sanitizeMessageLog(messagesLog)

    // 条目顺序是**稳定契约**：summary 在最前，其余按"读的人先看什么"排（环境 → 链路 → 日志）。
    // 测试按这个顺序断言；改顺序要同步改测试（解压的人靠它判断包里少没少东西）。
    val entries = ArrayList<DiagnosticsEntry>(5)
    entries += DiagnosticsEntry("environment.json", environmentJson(environment, generatedAtMillis).encodeToByteArray())
    if (linkJson != null) {
        entries += DiagnosticsEntry("link.json", linkJson.encodeToByteArray())
    }
    entries += DiagnosticsEntry("logs/queue.jsonl", queue.text.encodeToByteArray())
    entries += DiagnosticsEntry("logs/messages.jsonl", messages.text.encodeToByteArray())

    val summary = buildSummary(
        environment = environment,
        generatedAtMillis = generatedAtMillis,
        linkPresent = linkJson != null,
        queue = queue,
        messages = messages,
        entries = entries,
    )
    // summary 放最前面：解压的人先看到它。
    val withSummary = ArrayList<DiagnosticsEntry>(entries.size + 1)
    withSummary += DiagnosticsEntry("summary.txt", summary.encodeToByteArray())
    withSummary += entries
    return DiagnosticsBundle(entries = withSummary, summary = summary)
}

// ---------------------------------------------------------------------------
// 脱敏
// ---------------------------------------------------------------------------

private val diagnosticJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * 把一行信封 JSON 收窄成**只有结构**的白名单。
 *
 * 判据是"这真的是我们自己的信封"：必须有 `id` / `type` / `from` / `ts` / `payload` 五个键，
 * 且类型对得上。任何一条不满足（自由文本、半行、别的系统的 JSON）都返回 null ⇒ 调用方整行丢弃。
 *
 * payload 只留 `kind`：它区分 text / group-text / file-offer / agent-event，是排查"这条为什么
 * 没到"的关键；正文、文件名、路径都在 payload 的其它键里，一个都不带。
 */
internal fun sanitizeEnvelope(raw: String): JsonObject? {
    val obj = runCatching { diagnosticJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return null
    val id = obj.stringOrNull("id") ?: return null
    val type = obj.stringOrNull("type") ?: return null
    val from = obj.stringOrNull("from") ?: return null
    val ts = obj.longOrNull("ts") ?: return null
    val payload = obj["payload"] as? JsonObject ?: return null
    val kind = payload["kind"] as? JsonPrimitive
    return buildJsonObject {
        put("id", id)
        put("type", type)
        put("from", from)
        put("ts", ts)
        if (kind != null) put("kind", kind.content)
    }
}

/**
 * `stores/messages.log` 的一行：`seq\tat\tpeer\tdirection\tenvelopeJson`。
 *
 * `at` 是**落库时刻**（不是信封 `ts`）—— 判"用户当时没收到、后来补发了"只看这一列
 * （AGENTS.md §5）。所以它必须原样留在包里，而正文必须一个字节都不留。
 */
internal fun sanitizeMessageLogLine(line: String): String? {
    val parts = line.split('\t', limit = 5)
    if (parts.size != 5) return null
    val seq = parts[0].toLongOrNull() ?: return null
    val at = parts[1].toLongOrNull() ?: return null
    val peer = parts[2]
    val direction = parts[3]
    val envelope = sanitizeEnvelope(parts[4]) ?: return null
    return buildJsonObject {
        put("seq", seq)
        put("at", at)
        put("peer", peer)
        put("direction", direction)
        put("envelope", envelope)
    }.toString()
}

/** `stores/queue.log` 的一行：`created\tpeerId\tmsgId\tenvelopeJson`。 */
internal fun sanitizeQueueLogLine(line: String): String? {
    val parts = line.split('\t', limit = 4)
    if (parts.size != 4) return null
    val created = parts[0].toLongOrNull() ?: return null
    val peer = parts[1]
    val msgId = parts[2]
    val envelope = sanitizeEnvelope(parts[3]) ?: return null
    return buildJsonObject {
        put("created", created)
        put("peer", peer)
        put("msgId", msgId)
        put("envelope", envelope)
    }.toString()
}

internal fun sanitizeMessageLog(
    text: String?,
    maxLines: Int = DiagnosticsPackage.MAX_LOG_LINES,
    maxBytes: Int = DiagnosticsPackage.MAX_LOG_BYTES,
): SanitizedLog = sanitizeLog(text, maxLines, maxBytes, ::sanitizeMessageLogLine)

internal fun sanitizeQueueLog(
    text: String?,
    maxLines: Int = DiagnosticsPackage.MAX_LOG_LINES,
    maxBytes: Int = DiagnosticsPackage.MAX_LOG_BYTES,
): SanitizedLog = sanitizeLog(text, maxLines, maxBytes, ::sanitizeQueueLogLine)

/**
 * 从末尾（最新）往前收，直到撞上行数或字节上限。
 *
 * 为什么从后往前：诊断要的是"刚刚发生了什么"，不是"三天前第一条是什么"。
 * 保留顺序仍是文件里的原顺序（老→新），读的人从下往上找最近一次。
 */
private fun sanitizeLog(
    text: String?,
    maxLines: Int,
    maxBytes: Int,
    sanitize: (String) -> String?,
): SanitizedLog {
    if (text.isNullOrEmpty()) return SanitizedLog("", 0, 0, truncated = false)
    val lines = text.split('\n').filter { it.isNotEmpty() }
    var kept = 0
    var dropped = 0
    var used = 0
    var truncated = false
    val out = ArrayList<String>()
    for (index in lines.indices.reversed()) {
        val line = sanitize(lines[index])
        if (line == null) {
            dropped++
            continue
        }
        if (kept >= maxLines) {
            truncated = true
            break
        }
        val cost = line.encodeToByteArray().size + 1
        if (used + cost > maxBytes) {
            truncated = true
            break
        }
        out.add(line)
        used += cost
        kept++
    }
    out.reverse()
    return SanitizedLog(out.joinToString("\n"), kept, dropped, truncated)
}

// ---------------------------------------------------------------------------
// environment / summary
// ---------------------------------------------------------------------------

/** 只含可诊断事实的 JSON：版本、身份、网络、对端、待发/落库开关。没有正文。 */
internal fun environmentJson(environment: DiagnosticsEnvironment, generatedAtMillis: Long): String {
    val obj = buildJsonObject {
        put("schema", 1)
        put("generatedAt", isoUtc(generatedAtMillis))
        put("generatedAtMillis", generatedAtMillis)
        put("appVersion", environment.appVersion)
        put("nodeId", environment.nodeId)
        put("deviceNick", environment.deviceNick)
        put("persistMessages", environment.persistMessages)
        put(
            "network",
            buildJsonObject {
                put("transport", environment.transport)
                put("vpnActive", environment.vpnActive)
                // **两份都要导出，且语义分明**：
                //  · `localAddresses` —— 用于广播 / 配对码 / 同网段判断（刻意窄）；
                //  · `advertisedAddresses` —— 实际宣告给对端的（含隧道口）。
                //
                // 排查「连不上」时看后者：窄的有对方网段、宽的有 VPN 地址，正说明那台设备
                // 只在 VPN 上可达。只导窄列表会把排查方向带错。
                put("localAddresses", buildJsonArray { environment.localAddresses.forEach { add(JsonPrimitive(it)) } })
                put(
                    "advertisedAddresses",
                    buildJsonArray { environment.advertisedAddresses.forEach { add(JsonPrimitive(it)) } },
                )
            },
        )
        put(
            "peers",
            buildJsonObject {
                put("total", environment.peerTotal)
                put("online", environment.peerOnline)
                put(
                    "list",
                    buildJsonArray {
                        environment.peers.forEach { peer ->
                            add(
                                buildJsonObject {
                                    put("nodeId", peer.nodeId)
                                    put("nick", peer.nick)
                                    put("platform", peer.platform)
                                    put("online", peer.online)
                                    put("isAgent", peer.isAgent)
                                    put("tcpPort", peer.tcpPort)
                                    // **对端有哪些地址**：排查「连不上」时第一个要看的东西。
                                    // 空数组本身是信息 —— 对端版本还不宣告地址。
                                    put(
                                        "addresses",
                                        buildJsonArray { peer.addresses.forEach { add(JsonPrimitive(it)) } },
                                    )
                                    // **本机在拨哪一个**：与上面配合才能看出「有候选却没试」。
                                    put("dialTarget", peer.dialTarget)
                                },
                            )
                        }
                    },
                )
            },
        )
        put("pending", environment.pending)
        put("logLimits", buildJsonObject {
            put("maxLinesPerLog", DiagnosticsPackage.MAX_LOG_LINES)
            put("maxBytesPerLog", DiagnosticsPackage.MAX_LOG_BYTES)
        })
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
}

private fun buildSummary(
    environment: DiagnosticsEnvironment,
    generatedAtMillis: Long,
    linkPresent: Boolean,
    queue: SanitizedLog,
    messages: SanitizedLog,
    entries: List<DiagnosticsEntry>,
): String = buildString {
    appendLine("Tidings mobile diagnostics")
    appendLine("Include the time, steps and expected result. Export from both peers for transfer issues.")
    appendLine("No chat or file content is included. Logs are bounded; see logLimits below.")
    appendLine()
    appendLine("generated: " + isoUtc(generatedAtMillis))
    appendLine("appVersion: " + environment.appVersion)
    appendLine("nodeId: " + environment.nodeId)
    appendLine("deviceNick: " + environment.deviceNick)
    appendLine("transport: " + environment.transport + (if (environment.vpnActive) " (vpn)" else ""))
    appendLine("addresses: " + environment.localAddresses.size)
    appendLine("peers: " + environment.peerOnline + "/" + environment.peerTotal + " online")
    appendLine("pending (queued envelopes): " + environment.pending)
    appendLine("persistMessages: " + environment.persistMessages)
    appendLine()
    appendLine("entries:")
    for (entry in entries) {
        appendLine("  " + entry.path + "  " + entry.bytes.size + " B")
    }
    appendLine()
    appendLine("sources:")
    appendLine("  diag/link.json: " + (if (linkPresent) "included" else "missing"))
    appendLine("  stores/queue.log: " + logStats(queue))
    appendLine("  stores/messages.log: " + logStats(messages))
}

private fun logStats(log: SanitizedLog): String {
    val parts = ArrayList<String>(3)
    parts += "kept=" + log.kept
    if (log.dropped > 0) parts += "dropped=" + log.dropped + " (unparsable)"
    if (log.truncated) parts += "truncated=true"
    return parts.joinToString(", ")
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

// ---------------------------------------------------------------------------
// 时间（common 里没有 java.time / kotlinx-datetime，自己按 UTC 算）
// ---------------------------------------------------------------------------

/** ISO-8601 UTC，毫秒精度：`2026-10-20T08:49:49.123Z`。 */
internal fun isoUtc(millis: Long): String {
    val dayMillis = 86_400_000L
    var days = millis / dayMillis
    var rem = millis % dayMillis
    if (rem < 0) {
        rem += dayMillis
        days -= 1
    }
    val (year, month, day) = civilFromDays(days)
    val hour = (rem / 3_600_000L).toInt()
    val minute = ((rem / 60_000L) % 60L).toInt()
    val second = ((rem / 1_000L) % 60L).toInt()
    val milli = (rem % 1_000L).toInt()
    return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) + "T" +
        pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2) + "." + pad(milli, 3) + "Z"
}

/**
 * 文件名用的 UTC 时间戳：`20261020-084949`。
 *
 * 不用 ISO 原文：冒号在文件名里是非法/需要转义的字符（Android 上会被 FileProvider 拒绝，
 * Windows 上压根不能建）。秒粒度就够 —— 同一秒内导出两次会覆盖上一份，这是想要的。
 */
internal fun diagStampUtc(millis: Long): String {
    val dayMillis = 86_400_000L
    var days = millis / dayMillis
    var rem = millis % dayMillis
    if (rem < 0) {
        rem += dayMillis
        days -= 1
    }
    val (year, month, day) = civilFromDays(days)
    val hour = (rem / 3_600_000L).toInt()
    val minute = ((rem / 60_000L) % 60L).toInt()
    val second = ((rem / 1_000L) % 60L).toInt()
    return pad(year, 4) + pad(month, 2) + pad(day, 2) + "-" +
        pad(hour, 2) + pad(minute, 2) + pad(second, 2)
}

private fun pad(value: Int, width: Int): String {
    val text = value.toString()
    return if (text.length >= width) text else "0".repeat(width - text.length) + text
}

/** 1970-01-01 起的天数 → 公历年月日（Howard Hinnant 的 civil_from_days）。ZIP 头也要用。 */
internal fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    var year = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = doy - (153L * mp + 2L) / 5L + 1L
    val month = if (mp < 10L) mp + 3L else mp - 9L
    if (month <= 2L) year += 1L
    return Triple(year.toInt(), month.toInt(), day.toInt())
}
