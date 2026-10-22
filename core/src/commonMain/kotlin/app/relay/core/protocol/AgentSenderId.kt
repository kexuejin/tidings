// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * agent 作为群消息发送者时的**身份**：`(hostNodeId, agentId)`。
 *
 * ## 为什么必须带设备
 *
 * agent 不是"一个全局的东西"，而是**某台设备上的一个工具** —— 每台设备可以有自己的
 * agent，两台设备也可以有同名的 agent（都叫 `dsh`）。只写 `agent:<kind>` 时这两个
 * 会在 senderId 里**完全一样**，于是被合并成同一个人。
 *
 * 真机数据（桌面 relay.db）：同一个 agent 在库里是两种拼写 ——
 * `agent:DSH` 860 条 / `agent:dsh` 794 条，只差大小写。那不是两台机器，是一次拼写
 * 修订留下的版本边界；但它证明了**身份没有被归一化**。
 *
 * ## 三种形态与兼容
 *
 * | 形态 | 含义 | 谁在写 |
 * |---|---|---|
 * | `agent:<hostNodeId>:<agentId>` | 完整身份（本函数产出） | 新代码 |
 * | `agent:<kind>` | 老形态，没有设备 | 旧版本对端 |
 * | `<nodeId>` | 人的设备 | 用户自己发言 |
 *
 * 读侧两种都要认（[app.relay.app.resolveSenderIdentity] 已分支处理）：老形态靠
 * `agentKindsByHost` 反查宿主，查不到就只显示 kind。**写侧只产完整形态。**
 *
 * ## 长度预算
 *
 * `Limits.ID = 64`。`agent:`(6) + UUID(36) + `:`(1) = 43，**留给 agentId 只有 21 个字符**。
 * 超了会让整帧被 `isStr(o["senderId"], Limits.ID)` 拒收 —— 而发送侧拿不到任何错误，
 * 表现为"对方没收到"。所以这里**主动截断**，让"不产出注定被拒的帧"成为不变量。
 */
object AgentSenderId {

    /** `agentId` 在完整形态里可用的字符数（见上面长度预算）。 */
    const val MAX_AGENT_ID = Limits.ID - 6 - 36 - 1

    /**
     * 产出一个 agent 的**发送者身份**。
     *
     * @param hostNodeId 托管它的设备（本机发消息时就是本机 nodeId）。
     * @param agentId agent 自己的身份名；空则退回 [kind]。
     * @param kind 驱动类型（`dsh` / `native` …），只作 [agentId] 缺失时的兜底与去重提示。
     */
    fun of(hostNodeId: String, agentId: String?, kind: String? = null): String {
        val host = hostNodeId.trim()
        val raw = (agentId?.trim()?.takeIf { it.isNotBlank() }
            ?: kind?.trim()?.takeIf { it.isNotBlank() }
            ?: "dsh").lowercase()
        // **截断要按字节，且必须同时消掉冒号 —— 两件事都源自同一处不对称。**
        //
        // ① 按字节：`Limits.ID` 校验的是 UTF-8 长度，而 `take(21)` 数的是**字符**。
        //    21 个中文 = 45 字节，`agent:` + UUID(36) + `:` + 45 = **88 > 64** ⇒
        //    `isStr(o["senderId"], Limits.ID)` 为假 ⇒ **整帧拒收**，而发送侧拿不到任何
        //    错误（本类 KDoc 记的就是这个症状：「表现为对方没收到」）。
        //
        // ② 去掉冒号：`parse` 用 `substringAfterLast(':')` 切，而 `of` 不过滤冒号 ——
        //    写进去 `agent:host:my:agent`、读回来是 `(host, "agent")`，身份被悄悄改写。
        //    同一个缺陷的方向是**两个不同的 agent 合并成一个人**。
        //
        // 归一化成 ASCII 短名（与 `AgentMemberName.slug` 同一套字符集）之后，两个问题一起
        // 消失：没有多字节字符，也没有分隔符。
        val name = slugForSenderId(raw).take(MAX_AGENT_ID).trimEnd('-').ifBlank { "dsh" }
        // 宿主 id 缺失时退回老形态：宁可少一个字段，也不要产出 `agent::dsh` 这种怪东西。
        if (host.isBlank()) return AGENT_SENDER_PREFIX + name
        return AGENT_SENDER_PREFIX + host + ":" + name
    }

    /**
     * 把任意 agent 名归一化成能安全放进 `senderId` 的 ASCII 片段。
     *
     * 只保留 `a-z` / `0-9` / `-`：这是 `parse` 能无歧义还原的字符集。其它一律折成 `-`。
     * 与 `AgentMemberName.slug` 同一套规则，但**不共用那份实现**是刻意的：那一份的职责
     * 是「群内可被 @ 的名字」，这一份的职责是「senderId 的字段边界」。两者将来若要放宽
     * （比如允许中文名），放宽的代价不一样 —— 例如这里一旦允许非 ASCII 就必须同时改
     * `Limits.ID` 的字节预算。
     */
    private fun slugForSenderId(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var lastDash = false
        for (ch in raw) {
            val ok = ch in 'a'..'z' || ch in '0'..'9'
            if (ok) {
                sb.append(ch)
                lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-')
                lastDash = true
            }
        }
        return sb.toString().trimEnd('-')
    }

    /**
     * 从任意形态里解出 `(hostNodeId, agentId)`。
     *
     * 老形态 `agent:<kind>` 的 host 为 null —— 调用方靠 `agentKindsByHost` 反查。
     */
    fun parse(senderId: String?): Pair<String?, String>? {
        val id = senderId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!id.startsWith(AGENT_SENDER_PREFIX)) return null
        val rest = id.removePrefix(AGENT_SENDER_PREFIX).trim()
        if (rest.isEmpty()) return null
        return if (rest.contains(':')) {
            val host = rest.substringBefore(':').trim().ifBlank { null }
            val agentId = rest.substringAfterLast(':').trim()
            if (agentId.isBlank()) null else (host to agentId)
        } else {
            null to rest
        }
    }
}
