// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

/**
 * 按 nodeId 存**对端的 TLS pin**（配对码里带的 tlsPin / pin）。
 *
 * K2 的信任模型是「双向固定」：每一端都要验对端证书的 SPKI 指纹。手机侧的 pin 来自扫码，
 * 所以必须像候选端点一样**落盘**、并在配对时刷新 —— 否则重启后 pin 就丢了，公网长连接
 * 只能退化成"没验就信"（fail-open），那正是 task-13 明确禁止的。
 *
 * 只存 wire 形态的字符串（`sha256:<64 hex>`），校验一律走 task-13 的 `TlsPin.parse`：
 * 这里不做第二套解析，免得两处松紧不一致。
 */
interface PeerPinStore {
    fun load(): Map<String, String>

    fun save(pins: Map<String, String>)

    /**
     * pin 的**来源**（task-38）：`pairing` / `tofu`。默认空表 ⇒ 全按 `unknown` 处理。
     *
     * 为什么来源也要落盘：重启后如果来源丢了，`linkPinSource` 就再也看不出"这枚 pin 其实是
     * TOFU 学来的"—— 降级路径又会变成静默。
     */
    fun loadSources(): Map<String, String> = emptyMap()

    fun saveSources(sources: Map<String, String>) {}
}

/** 内存实现：测试与预览用。 */
class InMemoryPeerPinStore(
    initial: Map<String, String> = emptyMap(),
    initialSources: Map<String, String> = emptyMap(),
) : PeerPinStore {
    private var current: Map<String, String> = initial
    private var sources: Map<String, String> = initialSources

    override fun load(): Map<String, String> = current

    override fun save(pins: Map<String, String>) {
        current = pins
    }

    override fun loadSources(): Map<String, String> = sources

    override fun saveSources(sources: Map<String, String>) {
        this.sources = sources
    }
}
