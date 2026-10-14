// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

/**
 * mDNS 服务名与它的 UTF-8 截断 —— 两个纯函数，单独放一个文件只为能被直接单测。
 *
 * 为什么值得抽出来：服务名是**唯一标识**，而它由用户可改的昵称拼成。局域网里两台都叫
 * 「我的手机」的手机只会让 Bonjour 把第二个改名成「我的手机 (2)」，对端看到的名字就
 * 不可预测了；名字超长（中文一个字 3 字节）则会让 `registerService` 直接被拒。
 * 这两件事都不该靠在真机上试出来。
 */

/** DNS-SD 的单个 label 上限是 63 字节（不是 63 个字符）。 */
internal const val MDNS_LABEL_MAX_BYTES: Int = 63

/** 没有昵称时用的前缀。 */
private const val MDNS_FALLBACK_PREFIX: String = "relay"

/**
 * 生成 mDNS 服务名：`<昵称>-<nodeId 前 6 位>`。
 *
 * 后缀不是装饰：它是**唯一性**的来源。昵称可以重复，`nodeId` 不会。
 * 昵称按 UTF-8 字节预算截断（见 [truncateUtf8]），保证整个 label ≤ [MDNS_LABEL_MAX_BYTES]。
 */
internal fun mdnsServiceLabel(nick: String, nodeId: String): String {
    val suffix = nodeId.filter { it.isLetterOrDigit() }.take(SUFFIX_LENGTH).ifEmpty { "node" }
    val budget = MDNS_LABEL_MAX_BYTES - suffix.length - 1
    val head = truncateUtf8(nick.trim(), budget).trim()
    return if (head.isEmpty()) "$MDNS_FALLBACK_PREFIX-$suffix" else "$head-$suffix"
}

/**
 * 截到不超过 [maxBytes] 个 UTF-8 字节，且**不切碎代理对**。
 *
 * 逐字符回退而不是按字节切：按字节切会把一个汉字或一个 emoji 劈成半个字符，
 * 那样的 label 在 DNS-SD 里是非法输入，症状是"发布静默失败"。
 */
internal fun truncateUtf8(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (text.encodeToByteArray().size <= maxBytes) return text
    var end = text.length
    while (end > 0 && text.substring(0, end).encodeToByteArray().size > maxBytes) end--
    val cut = text.substring(0, end)
    // 回退点可能正好落在代理对的中间：孤立的高代理项编码成一个替换字节，
    // 字节数凑巧没超，但名字已经坏了，所以把它丢掉。
    return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

private const val SUFFIX_LENGTH: Int = 6
