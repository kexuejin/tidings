// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 执行桥的任务请求（字段接原语；wsId 是目标工作区，路径永不出宿主）。 */
data class AgentInvokeTaskRequest(
    val agentId: String,
    val prompt: String,
    val wsId: String?,
    val groupId: String?,
    /** 1:1 直派的回卡会话（发起方的单聊）；null = 群派单（看板卡走群）。 */
    val convId: String? = null,
)

/** 执行桥的结果：status 用协议的封闭取值域（[com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates]）。 */
data class AgentInvokeExecution(
    val status: String,
    val summary: String? = null,
    val error: String? = null,
    /** 执行产出的卡片（组员的卡；Leader 上群时会把它收进自己的 cardId 域）。 */
    val card: JsonObject? = null,
) {
    companion object {
        /**
         * 成功结果，卡片来自事件流的 **JSON 文本**。壳层（desktopApp）的编译类路径上
         * 没有 kotlinx-serialization（既有纪律），执行桥拿到的卡片只能是一段 String ——
         * 解析在这层完成，壳层永远不点名 JsonObject；解析不出的卡片如实丢弃
         * （结果仍算 done，看板卡少一段内容，不谎报失败）。
         */
        fun done(summary: String?, cardJsonText: String?): AgentInvokeExecution = AgentInvokeExecution(
            status = com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE,
            summary = summary,
            card = cardJsonText?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            },
        )

        /**
         * 非成功结果**带卡片**（中止、失败、超时）。
         *
         * 为什么要有它：调用方（域层派单处）的反馈判据是「有没有卡」——有卡就不再
         * 补一条横幅。此前失败/中止一律不带卡，于是用户按了停止，群里弹出一句
         * 「没有完成这次任务」：卡片明明已经把「已取消」写清楚了，横幅又把它说成
         * 机器的故障。卡片是回执，横幅只是卡片缺席时的兜底。
         *
         * 与 [done] 同一条纪律：只收 JSON **文本**，解析留在这层 —— 壳层的编译类路径
         * 上没有 kotlinx-serialization。
         */
        fun withCard(
            status: String,
            summary: String?,
            error: String?,
            cardJsonText: String?,
        ): AgentInvokeExecution = AgentInvokeExecution(
            status = status,
            summary = summary,
            error = error,
            card = cardJsonText?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            },
        )
    }
}

/**
 * 执行器桥：desktopApp 接到 ControlPlane 的插件派单（`/v1/plugin/task` 语义）；
 * 测试接假实现。**不许**在这个桥里拼发送者 —— 身份只有 AgentSenderId 一个来源。
 */
interface AgentInvokeExecutor {
    suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution
}

/**
 * 插件在线取值域（S4-4b）：域层自己认得的两个词。取值与 agentNode `PluginRegistry`
 * 的 `STATUS_ONLINE` / `STATUS_OFFLINE` 同形，由壳层装桥时映射进来（:app 不依赖
 * :agentNode，映射落在 desktopApp；两套常量的同值关系由桌面测试钉死）。
 */
object PluginPresence {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}
