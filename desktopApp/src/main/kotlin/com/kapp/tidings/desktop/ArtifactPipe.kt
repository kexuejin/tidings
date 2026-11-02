// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Limits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2P 大产物直投通道（升级计划 §4.2-3 / §7.4 产物自描述契约）的**决策与组装**纯函数。
 *
 * ## 它解决什么
 *
 * 工单卡整体受 [Limits.CARD_BYTES]（24 KiB）字节预算约束，超限**整帧拒收不截断**。
 * 全量补丁 / 测试报告 / 日志动辄几十 KB，塞进卡就是发送侧自己生产一帧注定被拒的帧。
 * 所以产物只有两条合法出路：
 *
 * 1. **卡内**：只允许占 [inlineBudgetLeft] 算出的余量（整卡预算减去卡面已有内容）；
 * 2. **直投**：完整文件走宿主既有的 `purpose=artifact` 免确认文件通路
 *    （`RelayController.sendAgentArtifacts` → core 的 TransferManager → P2P 文件信道），
 *    卡内只留 hunk 摘要与自描述条目。
 *
 * 「发送侧不产出注定被拒的帧」由此成为**不变量**：决策在这一个纯函数里，
 * 编排器照决策发，而不是靠调用方自觉。
 *
 * ## 为什么 intent 是稳定 ASCII 键
 *
 * 卡片 JSON 走协议线，渲染端可能是任何语言。intent 写人话（更别说写死中文）会让
 * 旧端显示出一串看不懂的字面量。所以这里只写**稳定键**（`patch-full` 等），
 * 人话在渲染端按键查词条 —— 与 cjkLiteralGate 的口径同源：生产源码不出现硬编码中文。
 */
object ArtifactPipe {

    /**
     * 整卡字节预算的唯一来源：[Limits.CARD_BYTES]（24 KiB）。
     *
     * 刻意不在这里重抄一个数字 —— 上限改动时发送侧决策必须跟着变，
     * 引用本体让「改一处、三处同源」（发送 / 接收 / 队列重放）自动成立。
     */
    val CARD_BYTES: Long = Limits.CARD_BYTES.toLong()

    /** 产物条目上的人类可读意图键：完整补丁（供审阅合并）。 */
    const val INTENT_PATCH_FULL = "patch-full"

    /** 产物条目上的人类可读意图键：结构化测试报告。 */
    const val INTENT_TEST_REPORT = "test-report"

    /** 产物条目上的人类可读意图键：运行日志。 */
    const val INTENT_RUN_LOG = "run-log"

    /** 产物条目上的人类可读意图键：网页原型。 */
    const val INTENT_PROTOTYPE = "prototype"

    /**
     * 卡内还剩多少字节给产物内容（等宽字节数，与协议侧同一把尺）。
     *
     * [cardBodyBytes] 是**已测得**的卡面字节数（banner / 阶段项 / diff hunk / checks
     * 等序列化后的合计）。预算不足时返回 0 而不是负数：负余量没有意义，
     * 调用方只需要「还能不能放」这一个答案。
     */
    fun inlineBudgetLeft(cardBodyBytes: Long): Long =
        (CARD_BYTES - cardBodyBytes).coerceAtLeast(0L)

    /**
     * 这份产物是否必须走直投（P2P 文件通路）。
     *
     * 判据是**严格大于**：产物恰好等于余量时放得下，不算超 —— 与协议侧
     * `utf8ByteLength(...) > Limits.CARD_BYTES` 的拒绝边界一致，
     * 两把尺对不上就会出现「发送侧判放得下、接收侧整帧拒收」的错位。
     */
    fun needsPipeDelivery(artifactBytes: Long, cardBodyBytes: Long): Boolean =
        artifactBytes > inlineBudgetLeft(cardBodyBytes)

    /**
     * 产物名 → 意图键。已知名字给键，认不出的返回 null ——
     * 渲染端对 null 的处置是「不画意图行」，而不是编一个通用词（诚实优于好看）。
     */
    fun intentForArtifactName(name: String): String? = when {
        name == "patch.diff" || name.endsWith(".diff") || name.endsWith(".patch") -> INTENT_PATCH_FULL
        name == "report.json" || name == "report.md" -> INTENT_TEST_REPORT
        name == "task.log" || name.endsWith(".log") -> INTENT_RUN_LOG
        name.endsWith(".html") -> INTENT_PROTOTYPE
        else -> null
    }

    /**
     * 产物名 → mime。与 [com.kapp.tidings.agent.StageChain] 卡片装配的已有取值
     * 同一口径（html / diff 两种），这里补齐文本与结构化两类 —— 直投通道承载的
     * 产物种类比卡片历史上写过的多。
     */
    fun mimeForArtifactName(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".diff") || name.endsWith(".patch") -> "text/x-diff"
        name.endsWith(".md") -> "text/markdown"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".log") || name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * 把已构建的工单卡里的 `artifacts[]` 升级为**自描述**条目：
     * 在 name/bytes/mime 之外补 intent（稳定键）与 delivered（直投是否真的发起了）。
     *
     * 输入是已装配好的卡 JSON（[com.kapp.tidings.agent.StageChain.cardOf] 的产物），
     * 这里**只改 artifacts 一段**，其余键原样保留 —— 卡形状的另一处定义是渲染端
     * 的解析契约，多动一个键就是多一分「两端都不报错但卡面空了」的风险。
     *
     * [deliveredNames] 是**真实发起过直投**的文件名集合（报价被传输层拒收的文件
     * 不在里面）：delivered 写的是投递事实，不是愿望 —— 没投出去的产物如实标 false，
     * 用户才知道「这一行现在还打不开」。
     */
    fun upgradeArtifactsSelfDescribing(
        card: JsonObject,
        deliveredNames: Set<String>,
    ): JsonObject {
        val rawArtifacts = card["artifacts"] as? kotlinx.serialization.json.JsonArray
            ?: return card
        val upgraded = buildJsonArray {
            rawArtifacts.forEach { el ->
                val obj = el as? JsonObject ?: run { add(el); return@forEach }
                val name = (obj["name"] as? JsonPrimitive)?.content ?: run { add(el); return@forEach }
                add(buildJsonObject {
                    obj.forEach { (k, v) -> if (k != "artifacts") put(k, v) }
                    intentForArtifactName(name)?.let { put("intent", it) }
                    if (mimeKeyMissing(obj, name)) put("mime", mimeForArtifactName(name))
                    put("delivered", name in deliveredNames)
                })
            }
        }
        return JsonObject(card.toMap() + ("artifacts" to upgraded))
    }

    /** 旧条目没带 mime（或取值不含扩展名语义）时按名字补一份。 */
    private fun mimeKeyMissing(entry: JsonObject, name: String): Boolean {
        val existing = (entry["mime"] as? JsonPrimitive)?.content
        return existing.isNullOrBlank()
    }

    /**
     * 一次直投的**投递请求**：名字、本机路径、字节数与它应有的自描述元数据。
     * 编排器据此调既有文件通路，测试据合成输入钉死组装判据。
     */
    data class PipeRequest(
        val name: String,
        val path: String,
        val bytes: Long,
    ) {
        /** 卡条目用的意图键（稳定 ASCII，渲染端本地化）。 */
        val intent: String? get() = intentForArtifactName(name)

        /** 卡条目用的 mime。 */
        val mime: String get() = mimeForArtifactName(name)
    }

    /** 从沙箱产物目录收集一份完整产物的直投请求；文件不存在返回 null（宁缺毋假）。 */
    fun requestFor(file: java.io.File): PipeRequest? {
        if (!file.isFile) return null
        return PipeRequest(
            name = file.name,
            path = file.absolutePath,
            bytes = file.length(),
        )
    }

    /** 一组请求序列化后的卡内字节数（衡量「这些元数据挤不挤预算」用）。 */
    fun requestsCardBytes(requests: List<PipeRequest>): Long =
        Codec.utf8ByteLength(
            buildJsonArray {
                requests.forEach { r ->
                    add(buildJsonObject {
                        put("name", r.name)
                        put("bytes", r.bytes)
                        r.intent?.let { put("intent", it) }
                        put("mime", r.mime)
                    })
                }
            }.toString(),
        ).toLong()
}
