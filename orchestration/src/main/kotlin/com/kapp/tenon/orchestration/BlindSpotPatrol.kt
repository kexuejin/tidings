// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 盲区巡检 —— **只记录，不自动建 Task**（编排模型适配设计 Step 6）。
 *
 * 对应拓扑机制是「周期巡检通道 + 卡点聚类」：执行类与决策类卡点都有人盯着，
 * 唯独盲区（总是败在同一类地方、但每次都没人回头看）靠周期巡检才浮出来。
 * 我们的对位事实源是**终局卡**：工单受阻、委派被门拒绝、派单退回 —— 这些每次都
 * 有横幅文案，但散在群聊里，没人聚合。
 *
 * 这里只做两件事：
 * 1. **记录**：终局事实（kind/title/detail）追加进 `$base/YYYY-MM.jsonl`（按月分文件）；
 * 2. **巡检**：每小时把最近 24h 的计数聚合**打一行日志**。不建 Task、不弹通知 ——
 *    等记录攒出稳定的失败聚类，再决定要不要升级成自动巡检报告。
 *
 * 记录永远不抛：巡检是观测面，观测面自己崩了不许把工单拖死。
 */
object BlindSpotPatrol {

    const val KIND_BLOCKED = "blocked"
    const val KIND_DELEGATION_REJECTED = "delegation-rejected"
    const val KIND_HANDBACK = "handback"

    /** 一条终局事实。[detail] 截断由调用方负责（这里只管落盘）。 */
    data class Entry(val at: Long, val kind: String, val title: String?, val detail: String?)

    /** 按月分文件：`YYYY-MM.jsonl`（本地时区）。 */
    fun fileFor(baseDir: File, at: Long): File {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val name = "%04d-%02d.jsonl".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
        return File(baseDir, name)
    }

    /** 追加一条记录；任何失败都吞掉（观测面不许拖死工单）。 */
    fun record(baseDir: File, entry: Entry) {
        runCatching {
            baseDir.mkdirs()
            val line = buildJsonObject {
                put("at", entry.at)
                put("kind", entry.kind)
                put("title", entry.title.orEmpty())
                put("detail", entry.detail.orEmpty())
            }.toString()
            fileFor(baseDir, entry.at).appendText(line + "\n")
        }
    }

    /**
     * 聚合最近 [sinceMillis] 之前的记录：`blocked=3 delegation-rejected=1`。
     * 读不进来的行跳过（截断的尾行、旧格式都不该让巡检崩掉）。
     */
    fun summarize(baseDir: File, sinceMillis: Long): Map<String, Int> {
        val files = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyMap()
        val counts = linkedMapOf<String, Int>()
        for (file in files) {
            for (line in runCatching { file.readLines() }.getOrDefault(emptyList())) {
                val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val at = (obj["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                if (at < sinceMillis) continue
                val kind = (obj["kind"] as? JsonPrimitive)?.content ?: continue
                counts[kind] = (counts[kind] ?: 0) + 1
            }
        }
        return counts
    }

    /** 每小时把最近 24h 的计数打一行日志（只记录阶段：聚合的唯一出口是日志）。 */
    fun startPatrol(baseDir: File, scope: CoroutineScope, intervalMillis: Long = 3_600_000L): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMillis)
                val counts = runCatching { summarize(baseDir, System.currentTimeMillis() - 24 * 3_600_000L) }
                    .getOrDefault(emptyMap())
                if (counts.isNotEmpty()) {
                    RelayLog.info("blindspot", "last24h " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
            }
        }

    /** 解析一行 JSONL（测试与将来导诊断包共用）。 */
    fun entryOf(line: String): Entry? = runCatching {
        val obj: JsonObject = Json.parseToJsonElement(line).jsonObject
        Entry(
            at = obj["at"]!!.jsonPrimitive.content.toLongOrNull() ?: return null,
            kind = obj["kind"]!!.jsonPrimitive.content,
            title = obj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            detail = obj["detail"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()
}
