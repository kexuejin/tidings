// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `sessionId → convId` 的**宿主本地台账**（F4/C3 硬保证）。
 *
 * ## 为什么必须有它
 *
 * [AgentSessionRegistry.bindConversation] 是**内存有界映射**（会淘汰、重启即失）：
 * 同一 agent + 同一项目目录在两个群都有席位时，派单候选不按群过滤就会**两个群共用
 * 同一条 CLI 会话** —— 在 A 群派的单、上下文里全是 B 群说过的话。裁决（更严档，
 * 无兼容分支）：候选会话必须归属本群；归属未知 ⇒ 不进候选（fail-closed），
 * 不回退「全 agent 候选」。
 *
 * 落盘纪律与 [HostDirectoryStore] / [AgentToolApprovalStore] 同一套：写锁内同锁落盘、
 * 0600 + tmp rename、文件缺失 = 空、损坏 / schemaVersion 未知 = 整份按空（fail-closed）。
 * 读失败 = 空表 = 候选为空，不猜、不派单。
 *
 * 1:1 会话天然无群归属（binding 记的是 peer 会话键），不参与群派单候选过滤。
 */
class SessionConversationLedger(private val file: File) {

    data class Binding(val sessionId: String, val convId: String, val at: Long)

    private val rwLock = ReentrantReadWriteLock()
    /** 插入序 map：超界时淘汰最旧（first entry）—— MAX_BINDINGS 是真实存在的界。 */
    private var bindings: LinkedHashMap<String, String> = LinkedHashMap()

    init {
        load()
    }

    /** 派单对齐时写入（幂等：同 sessionId 覆盖为最新 convId）。 */
    fun record(sessionId: String, convId: String): Boolean {
        val id = sessionId.trim()
        val conv = convId.trim()
        if (id.isEmpty() || conv.isEmpty()) return false
        rwLock.write {
            while (bindings.size >= MAX_BINDINGS) {
                bindings.remove(bindings.keys.firstOrNull())
            }
            bindings.remove(id)
            bindings[id] = conv
            persistLocked()
        }
        return true
    }

    /** 归属查询：null = 台账里没有（归属未知，调用方按 fail-closed 处理）。 */
    fun conversationOf(sessionId: String): String? = rwLock.read { bindings[sessionId.trim()] }

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val version = (root["schemaVersion"] as? JsonPrimitive)?.longOrNull
        if (version != SCHEMA_VERSION) return
        val arr = root["bindings"] as? JsonArray ?: return
        rwLock.write {
            val loaded = LinkedHashMap<String, String>()
            arr.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val sid = (o["sessionId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val conv = (o["convId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                loaded[sid] = conv
            }
            bindings = loaded
        }
    }

    private fun persistLocked() {
        val json = buildJsonObject {
            put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
            put("bindings", buildJsonArray {
                bindings.forEach { (sid, conv) ->
                    add(buildJsonObject {
                        put("sessionId", JsonPrimitive(sid))
                        put("convId", JsonPrimitive(conv))
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString())
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setReadable(false, false)
            file.setReadable(true, true)
        }
    }

    companion object {
        const val SCHEMA_VERSION: Long = 1

        /** 有界化：会话是一有限资源；防坏文件/循环写入撑爆控制面。 */
        const val MAX_BINDINGS = 2000

        /** 与目录档案同目录同生命周期（`host-directories.json` 旁边）；装配缺省。 */
        fun defaultFile(): File = File(com.kapp.tenon.agent.plugins.HostDirs.default(), "session-conversations.json")
    }
}
