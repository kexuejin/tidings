// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * agent 健康账本 —— 岗位档案 record 字段的**持久化**载体（G1 的另一半，真机复验过）。
 *
 * 真机现场：第一单的链把审核棒指给未登录的 claude，error 写进了会话台账；
 * 宿主重启后第二单的 planner **又**指给了 claude —— 因为 `AgentSessionRegistry`
 * 是进程内存，错误史随重启蒸发，`recentCliSessionState` 在新进程里查到的是 null。
 *
 * 这个账本挂在 registry 的 `onRecordSession` 钩子上（与域层落库并行的第二消费者）：
 * - **写**：每个会话状态变更追加一行 JSONL（`agentKind/sessionId/state/updatedAt`），
 *   同一会话同一状态不重写（记录频率由状态变化决定，不由帧量决定）；
 * - **读**：`lastState(agentKind)` 给出该驱动最近一次会话终态 —— 内存台账查不到时
 *   （刚重启），健康检查从这里拿到上一进程留下的错误史。
 *
 * 观测面纪律同 [BlindSpotPatrol]：任何读写失败都吞掉，账本崩了不许拖死派单。
 */
class AgentHealthLedger(private val file: File) {

    /** agentKind → 最近一次 (state, updatedAt)。启动时从文件回放。 */
    private val lastByKind = ConcurrentHashMap<String, Pair<String, Long>>()

    /** (agentKind, sessionId) → 上次已写盘的 state；同值不重写。 */
    private val written = ConcurrentHashMap<String, String>()

    init {
        runCatching {
            if (!file.isFile) return@runCatching
            file.readLines().forEach { line ->
                runCatching {
                    val obj = Json.parseToJsonElement(line).jsonObject
                    val kind = obj["agentKind"]?.jsonPrimitive?.content ?: return@runCatching
                    val state = obj["state"]?.jsonPrimitive?.content ?: return@runCatching
                    val at = obj["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val current = lastByKind[kind]
                    if (current == null || at >= current.second) lastByKind[kind] = state to at
                }
            }
        }
    }

    /** registry 的 `onRecordSession` 钩子直接调这里。 */
    fun record(agentKind: String?, sessionId: String, state: String?, updatedAt: Long) {
        runCatching {
            val kind = agentKind?.takeIf { it.isNotEmpty() } ?: return
            val st = state ?: return
            val key = "$kind/$sessionId"
            if (written[key] == st) return
            written[key] = st
            val current = lastByKind[kind]
            if (current == null || updatedAt >= current.second) lastByKind[kind] = st to updatedAt
            file.parentFile?.mkdirs()
            file.appendText(
                buildJsonObject {
                    put("agentKind", kind)
                    put("sessionId", sessionId)
                    put("state", st)
                    put("updatedAt", updatedAt)
                }.toString() + "\n",
            )
        }
    }

    /** 该驱动最近一次会话终态；没有史 → null（不冤枉）。 */
    fun lastState(agentKind: String): String? = lastByKind[agentKind]?.first
}
